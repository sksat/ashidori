---------------------------- MODULE DetailMigrate ----------------------------
(***************************************************************************)
(* Migration of the old detail log into fragments                         *)
(* (docs/design/detail-log-format.md, "移行").                            *)
(*                                                                         *)
(* State at upgrade: raw_lines holds rows r1, r2, r3 (r3 arrived after r2 *)
(* but has an earlier time) and, from a legacy import the old version did *)
(* not finish, the row x1; the legacy text file still holds x1 and x2.     *)
(*                                                                         *)
(* The design: raw_lines is renamed to a frozen source table T1, so its   *)
(* rowids are never reused; a row that arrives later (r4, with an old     *)
(* time) goes to a new table T2, another source. The legacy file, whose   *)
(* rows may already be in T1 (its provenance cannot be established), is  *)
(* quarantined: kept, not migrated, not deduplicated by content.          *)
(* An input is a range of ids of one source (T1 in two batches by rowid,  *)
(* T2): its fragment is written durably (the steps DetailLog checks are   *)
(* one action here), committed to the ledger (one row per input), and     *)
(* only then are exactly those ids deleted. After a restart the deletion  *)
(* runs again; it must not touch anything else.                           *)
(*                                                                         *)
(* Mutations: FrozenSource = FALSE (later rows go into T1 and reuse the    *)
(* rowid of a deleted row: max(rowid) + 1 of an emptied table); ByIds =   *)
(* FALSE (a batch of T1 is deleted by its time range); Quarantine = FALSE *)
(* (the legacy file is migrated as a source); OldImport = TRUE (the old   *)
(* code: import the file into raw_lines, then delete it); Unique = FALSE  *)
(* (an input committed before is committed again).                       *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS MaxCrash, FrozenSource, ByIds, Quarantine, OldImport, Unique

Rows == {"r1", "r2", "r3"}
LateRow == "r4"
Lines == {"x1", "x2"}
Samples == Rows \cup {LateRow} \cup Lines
Time(s) == CASE s = "r1" -> 1 [] s = "r3" -> 2 [] s = "r4" -> 2 [] s = "r2" -> 3 [] OTHER -> 4
Inputs == {"T1a", "T1b", "T2", "F"}
Zero == [s \in Samples |-> 0]
Plus(a, b) == [s \in Samples |-> a[s] + b[s]]
Times(k, a) == [s \in Samples |-> k * a[s]]
MaxId(t) == IF t = {} THEN 0 ELSE CHOOSE m \in {r[1] : r \in t} : \A r \in t : r[1] <= m
Ids(t) == {r[1] : r \in t}
SamplesOf(t) == {r[2] : r \in t}
\* The rows of a fragment as a multiset (a sample in two rows counts twice).
RowBag(t) == [s \in Samples |-> Cardinality({r \in t : r[2] = s})]

VARIABLES
  t1, t2,     \* tables: sets of <<rowid, sample>>
  f,          \* lines in the legacy file ({} once deleted)
  q,          \* lines in quarantine
  ph,         \* per input: "idle", "built" (volatile)
  frozen,     \* per input: rows taken (volatile until the commit)
  imported,   \* OldImport: the file was inserted into T1 (volatile until the file is deleted)
  ledger,     \* per input: [n, c, ids, tmin, tmax]
  acked,      \* samples that exist in a source
  lateDone,
  lateBetween,\* history: r4 arrived after T1a was committed and before its rows were deleted
  failedIn,   \* history: inputs whose build was lost in a crash
  retried,    \* history: such inputs built again
  crashes

vars == <<t1, t2, f, q, ph, frozen, imported, ledger, acked, lateDone, lateBetween, failedIn, retried, crashes>>

NoRow == [n |-> 0, c |-> Zero, ids |-> {}, tmin |-> 0, tmax |-> 0]
Src(i) == CASE i = "T1a" -> {r \in t1 : r[1] <= 2}
            [] i = "T1b" -> {r \in t1 : r[1] >= 3}
            [] i = "T2" -> t2
            [] OTHER -> IF Quarantine THEN {} ELSE {<<Time(x), x>> : x \in f}

Init ==
  /\ t1 = {<<1, "r1">>, <<2, "r2">>, <<3, "r3">>, <<4, "x1">>} /\ t2 = {}
  /\ f = IF Quarantine /\ ~OldImport THEN {} ELSE Lines
  /\ q = IF Quarantine /\ ~OldImport THEN Lines ELSE {}
  /\ ph = [i \in Inputs |-> "idle"] /\ frozen = [i \in Inputs |-> {}] /\ imported = FALSE
  /\ ledger = [i \in Inputs |-> NoRow]
  /\ acked = Rows \cup Lines /\ lateDone = FALSE /\ lateBetween = FALSE
  /\ failedIn = {} /\ retried = {} /\ crashes = 0

InsertLate ==
  /\ ~lateDone /\ lateDone' = TRUE /\ acked' = acked \cup {LateRow}
  /\ lateBetween' = (ledger["T1a"].n >= 1 /\ ledger["T1a"].ids \cap Ids(t1) # {})
  /\ IF FrozenSource THEN t2' = t2 \cup {<<MaxId(t2) + 1, LateRow>>} /\ UNCHANGED t1
                     ELSE t1' = t1 \cup {<<MaxId(t1) + 1, LateRow>>} /\ UNCHANGED t2
  /\ UNCHANGED <<f, q, ph, frozen, imported, ledger, failedIn, retried, crashes>>

Build(i) ==
  /\ ph[i] = "idle" /\ Src(i) # {} /\ (Unique => ledger[i].n = 0)
  /\ frozen' = [frozen EXCEPT ![i] = Src(i)] /\ ph' = [ph EXCEPT ![i] = "built"]
  /\ retried' = IF i \in failedIn THEN retried \cup {i} ELSE retried
  /\ UNCHANGED <<t1, t2, f, q, imported, ledger, acked, lateDone, lateBetween, failedIn, crashes>>

Commit(i) ==
  /\ ph[i] = "built"
  /\ LET rows == frozen[i]
         ts == {Time(r[2]) : r \in rows}
     IN ledger' = [ledger EXCEPT ![i] = [n |-> @.n + 1, c |-> RowBag(rows), ids |-> Ids(rows),
                                         tmin |-> CHOOSE m \in ts : \A t \in ts : m <= t,
                                         tmax |-> CHOOSE m \in ts : \A t \in ts : t <= m]]
  /\ ph' = [ph EXCEPT ![i] = "idle"]
  /\ UNCHANGED <<t1, t2, f, q, frozen, imported, acked, lateDone, lateBetween, failedIn, retried, crashes>>

\* Deletes what the ledger row of an input covers; runs again after any restart.
Table(i) == IF i = "T2" THEN t2 ELSE t1
Gone(i) ==
  IF ByIds \/ i = "T2" THEN {r \in Table(i) : r[1] \in ledger[i].ids}
  ELSE {r \in t1 : Time(r[2]) >= ledger[i].tmin /\ Time(r[2]) <= ledger[i].tmax}
DeleteRows(i) ==
  /\ i \in {"T1a", "T1b", "T2"} /\ ledger[i].n >= 1 /\ Gone(i) # {}
  /\ IF i = "T2" THEN t2' = t2 \ Gone(i) /\ UNCHANGED t1 ELSE t1' = t1 \ Gone(i) /\ UNCHANGED t2
  /\ UNCHANGED <<f, q, ph, frozen, imported, ledger, acked, lateDone, lateBetween, failedIn, retried, crashes>>

DeleteFile ==
  /\ f # {} /\ (IF OldImport THEN imported ELSE ledger["F"].n >= 1)
  /\ f' = {} /\ imported' = FALSE
  /\ UNCHANGED <<t1, t2, q, ph, frozen, ledger, acked, lateDone, lateBetween, failedIn, retried, crashes>>

\* The old code: insert the file's lines into raw_lines, then delete the file.
ImportIntoTable ==
  /\ OldImport /\ f # {} /\ ~imported
  /\ t1' = t1 \cup {<<MaxId(t1) + 1, "x1">>, <<MaxId(t1) + 2, "x2">>}
  /\ imported' = TRUE
  /\ UNCHANGED <<t2, f, q, ph, frozen, ledger, acked, lateDone, lateBetween, failedIn, retried, crashes>>

Crash ==
  /\ crashes < MaxCrash /\ crashes' = crashes + 1
  /\ failedIn' = failedIn \cup {i \in Inputs : ph[i] = "built"}
  /\ ph' = [i \in Inputs |-> "idle"] /\ frozen' = [i \in Inputs |-> {}] /\ imported' = FALSE
  /\ UNCHANGED <<t1, t2, f, q, ledger, acked, lateDone, lateBetween, retried>>

Next ==
  \/ InsertLate \/ DeleteFile \/ ImportIntoTable \/ Crash
  \/ \E i \in Inputs : Build(i) \/ Commit(i) \/ DeleteRows(i)

Spec == Init /\ [][Next]_vars

Published == Plus(Plus(Times(ledger["T1a"].n, ledger["T1a"].c), Times(ledger["T1b"].n, ledger["T1b"].c)),
                  Plus(Times(ledger["T2"].n, ledger["T2"].c), Times(ledger["F"].n, ledger["F"].c)))
InSource == SamplesOf(t1) \cup SamplesOf(t2) \cup f \cup q

TypeOK == crashes \in 0..MaxCrash
Durability == \A s \in acked : s \in InSource \/ Published[s] = 1
NoDup == \A s \in Samples : Published[s] <= 1

Unreached_LateBetween == ~(lateBetween /\ Published[LateRow] = 1 /\ t1 = {})
Unreached_RetrySame == ~(\E i \in retried : ledger[i].n >= 1)
Unreached_OutOfOrderBatch == ~(ledger["T1a"].n >= 1 /\ ledger["T1b"].n >= 1 /\ t1 = {} /\ Published["r3"] = 1)
Unreached_QuarantineKept == ~(t1 = {} /\ q = Lines /\ Published["x1"] = 1)
==============================================================================
