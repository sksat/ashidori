------------------------------ MODULE DetailLog ------------------------------
(***************************************************************************)
(* Storage of the detail log (docs/design/detail-log-format.md, proposal): *)
(* journal -> fragments -> day generation -> deletion of inputs and old    *)
(* generations, with recovery and readers. The migration of raw_lines and *)
(* of legacy text files is in DetailMigrate.                              *)
(*                                                                         *)
(* Logger (app process): samples 1..N get consecutive sequence numbers    *)
(* (their ids) in arrival order. Each sample belongs to a series (odd and *)
(* even ids); the series are buffered separately, so a series can reach   *)
(* the file before an older sample of another series (short writes take  *)
(* the oldest samples of one series). The buffer may also drop a sample  *)
(* (overflow); the LOSS frame that says so may itself be lost, so the     *)
(* model does not rely on it. A new journal records its first sequence   *)
(* number in its header and has its directory entry fsynced before use.  *)
(*                                                                         *)
(* Loss ranges are computed from the file alone: the sequence numbers     *)
(* missing between the first and the last one present (gaps), and        *)
(* everything after the last one (the tail). Recovery of a journal whose  *)
(* writer died commits this to the ledger before closing the journal;    *)
(* the finaliser commits it with the journal's fragments.                *)
(*                                                                         *)
(* Finaliser (its own process): a closed journal yields one child per day *)
(* it covers (samples <= DayBreak are day 1), written to temporary files, *)
(* read back (a short write the writer did not notice fails this), fsynced,*)
(* renamed, the directory fsynced, and committed to the ledger in one     *)
(* transaction with the journal's manifest and loss record. Only then is *)
(* the journal deleted. Day generations (day 1) freeze the live children, *)
(* write one file, make it durable, publish it and consume their inputs  *)
(* in one transaction, and delete only the frozen inputs and the previous *)
(* generation, after readers that are choosing files.                    *)
(*                                                                         *)
(* Reader: reads the published generation and the unconsumed children in *)
(* one ledger read transaction, under the shared lock, and keeps the lock *)
(* until all listed files are open.                                       *)
(*                                                                         *)
(* Environment: app process death (buffer and reader lost, the journal    *)
(* awaits recovery), finaliser death and I/O errors (some children left   *)
(* fsynced or renamed), power loss (unsynced journal data survives only  *)
(* in part, unsynced files and directory entries are lost, ledger commits *)
(* not yet durable are lost), and failures during recovery.              *)
(*                                                                         *)
(* Each mutation flag set to FALSE removes one step; its config must fail. *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS
  N,              \* samples 1..N, delivered in this order
  DayBreak,       \* samples 1..DayBreak belong to day 1, the others to day 2
  NJ,             \* journals 1..NJ (ids are never reused)
  MaxGen,         \* generations of day 1
  MaxCrash,       \* failures of any kind (also a dropped sample), in total
  CreateSync,     \* fsync the directory after creating a journal, before using it
  FileFsync,      \* fsync a fragment or generation file before renaming it
  DirFsync,       \* fsync the directory before the ledger commit
  LedgerFull,     \* ledger commits are durable (WAL, synchronous=FULL)
  LedgerFirst,    \* delete a journal only after its ledger commit
  ParentManifest, \* commit all of a journal's children with its manifest at once
  LossFirst,      \* recovery records the loss before closing the journal
  ScanAtFinal,    \* finalisation records the loss its scan of the journal finds
  GapScan,        \* loss records include the gaps, not only the tail
  ReadBack,       \* verify written files before going on
  Unique,         \* a finalised journal is never finalised again
  AtomicConsume,  \* publish a generation and consume its inputs in one transaction
  OnlyFrozen,     \* a generation deletes only its frozen inputs
  GcLock,         \* deletion of files waits for readers choosing files
  OneSnapshot     \* a reader lists the generation and the children in one read transaction

Samples == 1..N
Journals == 1..NJ
Days == {1, 2}
Day(s) == IF s <= DayBreak THEN 1 ELSE 2
Series(s) == s % 2
Frags == Journals \X Days
GFile(g) == <<0, g>>
Files == Frags \cup {GFile(g) : g \in 1..MaxGen}
Named == {"named", "namedS", "namedV", "namedVS"}
Max(S) == IF S = {} THEN 0 ELSE CHOOSE m \in S : \A t \in S : t <= m
Oldest(S, k) == {s \in S : Cardinality({t \in S : t < s}) < k}

\* Multisets of samples as total functions to counts.
Zero == [s \in Samples |-> 0]
Bag(S) == [s \in Samples |-> IF s \in S THEN 1 ELSE 0]
Plus(a, b) == [s \in Samples |-> a[s] + b[s]]
Times(k, a) == [s \in Samples |-> k * a[s]]

NoRow == [n |-> 0, c |-> Zero, consumed |-> FALSE]
NoFile == [st |-> "none", c |-> Zero]
L0 == [frag |-> [x \in Frags |-> NoRow], fin |-> {}, pub |-> 0,
       gpub |-> [g \in 1..MaxGen |-> {}], loss |-> {}]

VARIABLES
  next,      \* next sample (sequence number) to deliver
  acked,     \* samples delivered to the logger
  jof,       \* journal a sample was delivered to (0 = not yet)
  mem,       \* logger buffers, all series (volatile)
  jst,       \* journal: "none", "creating", "open", "dead", "closed", "gone", "vanished"
  jnd,       \* the journal's directory entry is durable
  jfirst,    \* first sequence number of the journal (in its header)
  jw, jd,    \* samples written to the journal file, and fsynced
  file,      \* child and generation files: [st, c]
  ph,        \* finaliser step per journal (volatile)
  gph,       \* finaliser step per generation (volatile)
  ginp,      \* inputs frozen for generation g (volatile)
  lcur,      \* ledger as read now
  ldur,      \* ledger as it survives a power loss
  rst,       \* reader: "idle", "listed", "done", "failed"
  rfiles,    \* files the reader listed
  rsnap,     \* published rows when the reader listed them
  rgot,      \* rows the reader got
  lock,      \* the reader holds the shared lock
  persisted, \* history: samples once fsynced to a journal
  failed,    \* history: journals whose finalisation failed midway
  retried,   \* history: journals built again after such a failure
  late,      \* history: a day-1 child committed while a generation was being built
  crashes

vars == <<next, acked, jof, mem, jst, jnd, jfirst, jw, jd, file, ph, gph, ginp, lcur, ldur,
          rst, rfiles, rsnap, rgot, lock, persisted, failed, retried, late, crashes>>
logvars == <<next, acked, jof, mem, jst, jnd, jfirst, jw, jd, persisted>>
finvars == <<file, ph, gph, ginp, failed, retried, late>>
lvars == <<lcur, ldur>>
rvars == <<rst, rfiles, rsnap, rgot, lock>>

Cur == IF \E j \in Journals : jst[j] = "open" THEN CHOOSE j \in Journals : jst[j] = "open" ELSE 0
Commit(l) == lcur' = l /\ ldur' = IF LedgerFull THEN l ELSE ldur
Copies(x) == lcur.frag[x].n
RECURSIVE SumFiles(_)
\* Rows of the files in S; a child counts once per ledger row that points to it.
SumFiles(S) == IF S = {} THEN Zero ELSE
  LET f == CHOOSE f \in S : TRUE
      k == IF f \in Frags THEN Copies(f) ELSE 1
  IN Plus(Times(k, file[f].c), SumFiles(S \ {f}))
LiveFrags == {x \in Frags : Copies(x) >= 1 /\ ~lcur.frag[x].consumed /\ file[x].st \in Named}
PubFiles == LiveFrags \cup (IF lcur.pub > 0 /\ file[GFile(lcur.pub)].st \in Named THEN {GFile(lcur.pub)} ELSE {})
GenC == IF lcur.pub > 0 /\ file[GFile(lcur.pub)].st \in Named THEN file[GFile(lcur.pub)].c ELSE Zero
Published == SumFiles(PubFiles)
Children(j) == {d \in Days : \E s \in jw[j] : Day(s) = d}
Content(j, d) == Bag({s \in jw[j] : Day(s) = d})
\* The loss record a scan of the journal file yields: <<journal, gaps, last present>>.
Gaps(j) == IF GapScan THEN {k \in jfirst[j]..Max(jw[j]) : k \notin jw[j]} ELSE {}
LossRec(j) == <<j, Gaps(j), IF jw[j] = {} THEN jfirst[j] - 1 ELSE Max(jw[j])>>
Covered(s) == \E r \in lcur.loss : r[1] = jof[s] /\ (s \in r[2] \/ s > r[3])

Init ==
  /\ next = 1 /\ acked = {} /\ jof = [s \in Samples |-> 0] /\ mem = {}
  /\ jst = [j \in Journals |-> "none"] /\ jnd = [j \in Journals |-> FALSE] /\ jfirst = [j \in Journals |-> 1]
  /\ jw = [j \in Journals |-> {}] /\ jd = [j \in Journals |-> {}]
  /\ file = [f \in Files |-> NoFile]
  /\ ph = [j \in Journals |-> "idle"] /\ gph = [g \in 1..MaxGen |-> "idle"] /\ ginp = [g \in 1..MaxGen |-> {}]
  /\ lcur = L0 /\ ldur = L0
  /\ rst = "idle" /\ rfiles = {} /\ rsnap = Zero /\ rgot = Zero /\ lock = FALSE
  /\ persisted = {} /\ failed = {} /\ retried = {} /\ late = FALSE /\ crashes = 0

------------------------------------------------------------------------------
(* Logger *)

Create(j) ==
  /\ jst[j] = "none" /\ \A k \in Journals : jst[k] \notin {"creating", "open"}
  /\ \A k \in 1..(j - 1) : jst[k] # "none"
  /\ jst' = [jst EXCEPT ![j] = IF CreateSync THEN "creating" ELSE "open"]
  /\ jfirst' = [jfirst EXCEPT ![j] = next]
  /\ UNCHANGED <<next, acked, jof, mem, jnd, jw, jd, persisted, finvars, lvars, rvars, crashes>>

CreateDirSync(j) ==
  /\ CreateSync /\ jst[j] = "creating"
  /\ jst' = [jst EXCEPT ![j] = "open"] /\ jnd' = [jnd EXCEPT ![j] = TRUE]
  /\ UNCHANGED <<next, acked, jof, mem, jfirst, jw, jd, persisted, finvars, lvars, rvars, crashes>>

Deliver ==
  /\ next <= N /\ Cur # 0
  /\ acked' = acked \cup {next} /\ mem' = mem \cup {next} /\ jof' = [jof EXCEPT ![next] = Cur]
  /\ next' = next + 1
  /\ UNCHANGED <<jst, jnd, jfirst, jw, jd, persisted, finvars, lvars, rvars, crashes>>

\* The k oldest buffered samples of one series reach the file.
Write ==
  /\ Cur # 0
  /\ \E r \in {0, 1}, k \in 1..N :
       LET buf == {s \in mem : Series(s) = r} IN
       /\ k <= Cardinality(buf)
       /\ jw' = [jw EXCEPT ![Cur] = @ \cup Oldest(buf, k)] /\ mem' = mem \ Oldest(buf, k)
  /\ UNCHANGED <<next, acked, jof, jst, jnd, jfirst, jd, persisted, finvars, lvars, rvars, crashes>>

\* The buffer overflows and drops its oldest sample.
Drop ==
  /\ crashes < MaxCrash /\ mem # {} /\ crashes' = crashes + 1
  /\ mem' = mem \ Oldest(mem, 1)
  /\ UNCHANGED <<next, acked, jof, jst, jnd, jfirst, jw, jd, persisted, finvars, lvars, rvars>>

Sync ==
  /\ Cur # 0 /\ jd[Cur] # jw[Cur]
  /\ jd' = [jd EXCEPT ![Cur] = jw[Cur]] /\ persisted' = persisted \cup jw[Cur]
  /\ UNCHANGED <<next, acked, jof, mem, jst, jnd, jfirst, jw, finvars, lvars, rvars, crashes>>

\* CLOSE frame, fsync, rename, directory fsync. A crash in between leaves an
\* open journal without a writer, which recovery handles.
Rotate ==
  /\ Cur # 0 /\ mem = {} /\ jd[Cur] = jw[Cur] /\ jw[Cur] # {}
  /\ jst' = [jst EXCEPT ![Cur] = "closed"]
  /\ UNCHANGED <<next, acked, jof, mem, jnd, jfirst, jw, jd, persisted, finvars, lvars, rvars, crashes>>

------------------------------------------------------------------------------
(* Recovery of a journal whose writer died *)

Logged(j) == LossRec(j) \in lcur.loss

RecLoss(j) ==
  /\ jst[j] = "dead" /\ ~Logged(j)
  /\ Commit([lcur EXCEPT !.loss = @ \cup {LossRec(j)}])
  /\ UNCHANGED <<logvars, finvars, rvars, crashes>>

RecClose(j) ==
  /\ jst[j] = "dead" /\ (LossFirst => Logged(j))
  /\ jd' = [jd EXCEPT ![j] = jw[j]] /\ persisted' = persisted \cup jw[j]
  /\ jst' = [jst EXCEPT ![j] = "closed"]
  /\ UNCHANGED <<next, acked, jof, mem, jnd, jfirst, jw, finvars, lvars, rvars, crashes>>

------------------------------------------------------------------------------
(* Finaliser: children of one journal *)

Mine(j, f) == f \in Frags /\ f[1] = j /\ f[2] \in Children(j)
SetChildren(j, from, to) == [f \in Files |-> IF Mine(j, f) /\ file[f].st = from THEN [file[f] EXCEPT !.st = to] ELSE file[f]]

\* Writes the children; a write may come up short without the writer noticing
\* (then the file lacks rows). That is a failure (counted).
Build(j) ==
  /\ jst[j] = "closed" /\ ph[j] = "idle" /\ (Unique => j \notin lcur.fin)
  /\ \E short \in SUBSET Children(j) :
       /\ short # {} => crashes < MaxCrash
       /\ crashes' = IF short = {} THEN crashes ELSE crashes + 1
       /\ file' = [f \in Files |-> IF Mine(j, f)
                                   THEN [st |-> "tmp", c |-> IF f[2] \in short THEN Zero ELSE Content(j, f[2])]
                                   ELSE file[f]]
  /\ retried' = IF j \in failed THEN retried \cup {j} ELSE retried
  /\ ph' = [ph EXCEPT ![j] = "built"]
  /\ UNCHANGED <<logvars, gph, ginp, failed, late, lvars, rvars>>

Verify(j) ==
  /\ ph[j] = "built"
  /\ ReadBack => \A d \in Children(j) : file[<<j, d>>].c = Content(j, d)
  /\ file' = IF FileFsync THEN SetChildren(j, "tmp", "tmpS") ELSE file
  /\ ph' = [ph EXCEPT ![j] = "synced"]
  /\ UNCHANGED <<logvars, gph, ginp, failed, retried, late, lvars, rvars, crashes>>

VerifyFails(j) ==
  /\ ReadBack /\ ph[j] = "built"
  /\ \E d \in Children(j) : file[<<j, d>>].c # Content(j, d)
  /\ ph' = [ph EXCEPT ![j] = "idle"] /\ failed' = failed \cup {j}
  /\ UNCHANGED <<logvars, file, gph, ginp, retried, late, lvars, rvars, crashes>>

Rename(j) ==
  /\ ph[j] = "synced"
  /\ file' = IF FileFsync THEN SetChildren(j, "tmpS", "named") ELSE SetChildren(j, "tmp", "namedV")
  /\ ph' = [ph EXCEPT ![j] = "renamed"]
  /\ UNCHANGED <<logvars, gph, ginp, failed, retried, late, lvars, rvars, crashes>>

DirSyncF(j) ==
  /\ DirFsync /\ ph[j] = "renamed"
  /\ file' = [f \in Files |-> IF Mine(j, f)
                              THEN [file[f] EXCEPT !.st = CASE @ = "named" -> "namedS" [] @ = "namedV" -> "namedVS" [] OTHER -> @]
                              ELSE file[f]]
  /\ ph' = [ph EXCEPT ![j] = "durable"]
  /\ UNCHANGED <<logvars, gph, ginp, failed, retried, late, lvars, rvars, crashes>>

Building == \E g \in 1..MaxGen : gph[g] # "idle"

\* With ParentManifest: all children, the manifest and the loss record at once.
\* Without (mutation): one child at a time, the journal finalised with the first.
CommitF(j) ==
  /\ ph[j] = IF DirFsync THEN "durable" ELSE "renamed"
  /\ \E ds \in SUBSET Children(j) :
       /\ IF ParentManifest THEN ds = Children(j)
          ELSE Cardinality(ds) = 1 /\ \A d \in ds : Copies(<<j, d>>) = 0
       /\ Commit([[[lcur EXCEPT !.frag = [x \in Frags |-> IF x[1] = j /\ x[2] \in ds
                                           THEN [n |-> lcur.frag[x].n + 1, c |-> Content(j, x[2]), consumed |-> FALSE]
                                           ELSE lcur.frag[x]]]
                   EXCEPT !.fin = @ \cup {j}] EXCEPT !.loss = IF ScanAtFinal THEN @ \cup {LossRec(j)} ELSE @])
       /\ late' = (late \/ (1 \in ds /\ Building))
       /\ ph' = [ph EXCEPT ![j] = IF ParentManifest \/ ds = Children(j) THEN "idle" ELSE @]
  /\ UNCHANGED <<logvars, file, gph, ginp, failed, retried, rvars, crashes>>

\* Without LedgerFirst (mutation): as soon as the children have their names.
DeleteJournal(j) ==
  /\ jst[j] = "closed" /\ (j \in lcur.fin \/ (~LedgerFirst /\ ph[j] \in {"renamed", "durable"}))
  /\ jst' = [jst EXCEPT ![j] = "gone"] /\ jw' = [jw EXCEPT ![j] = {}] /\ jd' = [jd EXCEPT ![j] = {}]
  /\ UNCHANGED <<next, acked, jof, mem, jnd, jfirst, persisted, finvars, lvars, rvars, crashes>>

------------------------------------------------------------------------------
(* Finaliser: generations of day 1 *)

Day1Live == {x \in LiveFrags : x[2] = 1}

GFreeze(g) ==
  /\ g = lcur.pub + 1 /\ gph[g] = "idle" /\ Day1Live # {}
  /\ ginp' = [ginp EXCEPT ![g] = Day1Live]
  /\ file' = [file EXCEPT ![GFile(g)] = [st |-> "tmp", c |-> Plus(GenC, SumFiles(Day1Live))]]
  /\ gph' = [gph EXCEPT ![g] = "built"]
  /\ UNCHANGED <<logvars, ph, failed, retried, late, lvars, rvars, crashes>>

GSync(g) ==
  /\ gph[g] = "built"
  /\ file' = [file EXCEPT ![GFile(g)].st = IF FileFsync THEN "tmpS" ELSE @]
  /\ gph' = [gph EXCEPT ![g] = "synced"]
  /\ UNCHANGED <<logvars, ph, ginp, failed, retried, late, lvars, rvars, crashes>>

GRename(g) ==
  /\ gph[g] = "synced"
  /\ file' = [file EXCEPT ![GFile(g)].st = IF FileFsync THEN "named" ELSE "namedV"]
  /\ gph' = [gph EXCEPT ![g] = "renamed"]
  /\ UNCHANGED <<logvars, ph, ginp, failed, retried, late, lvars, rvars, crashes>>

GDirSync(g) ==
  /\ DirFsync /\ gph[g] = "renamed"
  /\ file' = [file EXCEPT ![GFile(g)].st = IF @ = "named" THEN "namedS" ELSE "namedVS"]
  /\ gph' = [gph EXCEPT ![g] = "durable"]
  /\ UNCHANGED <<logvars, ph, ginp, failed, retried, late, lvars, rvars, crashes>>

GPublish(g) ==
  /\ g = lcur.pub + 1 /\ gph[g] = IF DirFsync THEN "durable" ELSE "renamed"
  /\ Commit([lcur EXCEPT !.pub = g, !.gpub[g] = ginp[g],
                         !.frag = IF AtomicConsume
                                  THEN [x \in Frags |-> IF x \in ginp[g] THEN [lcur.frag[x] EXCEPT !.consumed = TRUE] ELSE lcur.frag[x]]
                                  ELSE lcur.frag])
  /\ gph' = [gph EXCEPT ![g] = "idle"]
  /\ UNCHANGED <<logvars, file, ph, ginp, failed, retried, late, rvars, crashes>>

GConsume ==
  /\ ~AtomicConsume /\ lcur.pub > 0 /\ \E x \in lcur.gpub[lcur.pub] : ~lcur.frag[x].consumed
  /\ Commit([lcur EXCEPT !.frag = [x \in Frags |-> IF x \in lcur.gpub[lcur.pub] THEN [lcur.frag[x] EXCEPT !.consumed = TRUE] ELSE lcur.frag[x]]])
  /\ UNCHANGED <<logvars, finvars, rvars, crashes>>

GC ==
  LET del == IF OnlyFrozen THEN lcur.gpub[lcur.pub] ELSE {x \in Frags : x[2] = 1 /\ Copies(x) >= 1}
      old == IF lcur.pub > 1 THEN {GFile(lcur.pub - 1)} ELSE {}
  IN /\ lcur.pub > 0 /\ \A x \in lcur.gpub[lcur.pub] : lcur.frag[x].consumed
     /\ GcLock => ~lock
     /\ \E f \in del \cup old : file[f].st # "none"
     /\ file' = [f \in Files |-> IF f \in del \cup old THEN NoFile ELSE file[f]]
     /\ UNCHANGED <<logvars, ph, gph, ginp, failed, retried, late, lvars, rvars, crashes>>

LedgerCheckpoint ==
  /\ ~LedgerFull /\ ldur # lcur
  /\ ldur' = lcur
  /\ UNCHANGED <<logvars, finvars, lcur, rvars, crashes>>

------------------------------------------------------------------------------
(* Reader *)

\* One read transaction: the published generation and the unconsumed children.
\* Without OneSnapshot (mutation): only the generation here; the children are
\* listed again when the files are opened.
RList ==
  /\ rst = "idle" /\ PubFiles # {}
  /\ rst' = "listed" /\ lock' = TRUE /\ rsnap' = Published
  /\ rfiles' = IF OneSnapshot THEN PubFiles ELSE PubFiles \ Frags
  /\ UNCHANGED <<rgot, logvars, finvars, lvars, crashes>>

ROpen ==
  /\ rst = "listed"
  /\ LET fs == IF OneSnapshot THEN rfiles ELSE rfiles \cup LiveFrags IN
     /\ rst' = IF \A f \in fs : file[f].st \in Named THEN "done" ELSE "failed"
     /\ rgot' = SumFiles(fs)
  /\ lock' = FALSE
  /\ UNCHANGED <<rfiles, rsnap, logvars, finvars, lvars, crashes>>

RDone ==
  /\ rst = "done"
  /\ rst' = "idle" /\ rfiles' = {} /\ rsnap' = Zero /\ rgot' = Zero
  /\ UNCHANGED <<lock, logvars, finvars, lvars, crashes>>

------------------------------------------------------------------------------
(* Failures *)

Die(js) == [j \in Journals |-> IF js[j] \in {"creating", "open"} THEN "dead" ELSE js[j]]
Busy == {j \in Journals : ph[j] # "idle"}
ResetFin ==
  /\ ph' = [j \in Journals |-> "idle"] /\ gph' = [g \in 1..MaxGen |-> "idle"] /\ ginp' = [g \in 1..MaxGen |-> {}]
ReaderLost == rst' = "idle" /\ rfiles' = {} /\ rsnap' = Zero /\ rgot' = Zero /\ lock' = FALSE

AppCrash ==
  /\ crashes < MaxCrash /\ crashes' = crashes + 1
  /\ mem' = {} /\ jst' = Die(jst)
  /\ ReaderLost
  /\ UNCHANGED <<next, acked, jof, jnd, jfirst, jw, jd, persisted, finvars, lvars>>

\* The finaliser dies or a step fails with an I/O error, after it fsynced or
\* renamed only some of the children.
FinFail ==
  /\ crashes < MaxCrash /\ crashes' = crashes + 1
  /\ Busy # {} \/ (\E g \in 1..MaxGen : gph[g] # "idle")
  /\ \E fs \in SUBSET {f \in Frags : f[1] \in Busy /\ file[f].st = "tmpS"},
        ft \in SUBSET {f \in Frags : f[1] \in Busy /\ file[f].st = "tmp"} :
       file' = [f \in Files |-> IF f \in fs THEN [file[f] EXCEPT !.st = "named"]
                                ELSE IF f \in ft /\ FileFsync THEN [file[f] EXCEPT !.st = "tmpS"]
                                ELSE file[f]]
  /\ failed' = failed \cup Busy
  /\ ResetFin
  /\ UNCHANGED <<logvars, retried, late, lvars, rvars>>

AfterPowerLoss(fl) ==
  CASE fl.st = "tmp" -> NoFile
    [] fl.st = "named" -> [fl EXCEPT !.st = "tmpS"]
    [] fl.st = "namedV" -> NoFile
    [] fl.st = "namedVS" -> [st |-> "namedS", c |-> Zero]
    [] OTHER -> fl

\* Unsynced journal data survives in part; a journal whose name is not
\* durable disappears.
PowerLoss ==
  /\ crashes < MaxCrash /\ crashes' = crashes + 1
  /\ \E keep \in SUBSET UNION {jw[j] \ jd[j] : j \in Journals} :
       jw' = [j \in Journals |-> IF ~jnd[j] /\ jst[j] \in {"creating", "open", "dead"} THEN {}
                                 ELSE jd[j] \cup (jw[j] \cap keep)]
  /\ jd' = [j \in Journals |-> IF ~jnd[j] /\ jst[j] \in {"creating", "open", "dead"} THEN {} ELSE jd[j]]
  /\ jst' = [j \in Journals |-> IF ~jnd[j] /\ jst[j] \in {"creating", "open", "dead"} THEN "vanished"
                               ELSE IF jst[j] = "open" THEN "dead" ELSE jst[j]]
  /\ mem' = {}
  /\ file' = [f \in Files |-> AfterPowerLoss(file[f])]
  /\ lcur' = ldur
  /\ failed' = failed \cup Busy
  /\ ResetFin
  /\ ReaderLost
  /\ UNCHANGED <<next, acked, jof, jnd, jfirst, persisted, retried, late, ldur>>

Fin(j) == Build(j) \/ Verify(j) \/ VerifyFails(j) \/ Rename(j) \/ DirSyncF(j) \/ CommitF(j)
          \/ DeleteJournal(j) \/ RecLoss(j) \/ RecClose(j)

Next ==
  \/ \E j \in Journals : Create(j) \/ CreateDirSync(j) \/ Fin(j)
  \/ Deliver \/ Write \/ Drop \/ Sync \/ Rotate
  \/ \E g \in 1..MaxGen : GFreeze(g) \/ GSync(g) \/ GRename(g) \/ GDirSync(g) \/ GPublish(g)
  \/ GConsume \/ GC \/ LedgerCheckpoint \/ RList \/ ROpen \/ RDone
  \/ AppCrash \/ FinFail \/ PowerLoss

Spec == Init /\ [][Next]_vars

\* Liveness: once failures stop, recovery and finalisation make progress.
LiveSpec == Spec /\ \A j \in Journals : WF_vars(Fin(j))

------------------------------------------------------------------------------
(* Properties *)

DurableInputs == UNION {jd[j] : j \in {k \in Journals : jnd[k] /\ jst[k] \in {"open", "dead", "closed"}}}
JournalContent == UNION {jw[j] : j \in Journals}

TypeOK ==
  /\ next \in 1..(N + 1) /\ acked \subseteq Samples /\ mem \subseteq Samples
  /\ \A j \in Journals : jd[j] \subseteq jw[j]
  /\ lcur.pub \in 0..MaxGen /\ crashes \in 0..MaxCrash

\* Durability: a sample once fsynced is still in a durable journal or in
\* exactly one published row.
Durability == \A s \in persisted : s \in DurableInputs \/ Published[s] = 1

\* No sample is published twice (counting rows).
NoDup == \A s \in Samples : Published[s] <= 1

\* Loss reporting: once a sample's journal is gone, the sample is published
\* or covered by a loss record in the ledger. (Until then the journal holds
\* what its scan will report; LossResolved below makes sure that happens.)
LossReported ==
  \A s \in acked : jst[jof[s]] \in {"gone", "vanished"} => (Published[s] >= 1 \/ Covered(s))

\* A loss record never covers a sample that was fsynced, is in a journal, or
\* is published.
LossOnlyUnsaved ==
  \A s \in acked : Covered(s) => (s \notin persisted /\ s \notin JournalContent /\ Published[s] = 0)

\* A reader opens every file it listed and gets exactly the rows that were
\* published when it listed them.
ReaderOK == rst # "failed" /\ (rst = "done" => rgot = rsnap)

\* Reachability witnesses (formal/run.sh reach:): each must be violated.
Unreached_LateAfterFreeze == ~(late /\ lcur.pub = MaxGen)
Unreached_RetrySame == ~(\E j \in retried : jst[j] = "gone")
Unreached_MultiDay == ~(\E j \in Journals : jst[j] = "gone" /\ \A d \in Days : Copies(<<j, d>>) >= 1)
Unreached_GapReported == ~(\E s \in acked : jst[jof[s]] = "gone" /\ \E r \in lcur.loss : r[1] = jof[s] /\ s \in r[2])
Unreached_ReaderAcrossPublish == ~(rst = "done" /\ lcur.pub = MaxGen /\ rsnap # Published)

\* Liveness (DetailLog_live): every dead or closed journal is eventually deleted.
JournalsResolved == \A j \in Journals : (jst[j] \in {"dead", "closed"}) ~> (jst[j] = "gone")
==============================================================================
