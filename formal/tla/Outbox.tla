------------------------------- MODULE Outbox -------------------------------
(***************************************************************************)
(* App outbox and upload of one stream (docs/sync-protocol.md, "Upload     *)
(* outbox on the device", "Batches").                                      *)
(*                                                                         *)
(* Source: items 1..NItems (rows of an editable stream by change counter,  *)
(* or the chunks of archive files; both advance a progress mark).          *)
(* Building a batch claims the next seq, seals a fresh ciphertext for      *)
(* (device id, stream, seq) and stores the outbox row and the new progress *)
(* in one SQLite transaction (chunk-level for archives).                   *)
(* A row is deleted only after 200/201. All runs are serialized, so every  *)
(* client action below is atomic; unpairing waits for the running job and  *)
(* clears all per-registration state.                                      *)
(*                                                                         *)
(* Environment: crashes (in-memory state lost), lost responses (timeouts), *)
(* duplicated and delayed PUTs (a sent request stays deliverable forever,  *)
(* even after unpairing), a full quota (507), unpair/re-register, and a    *)
(* legacy pending file sealed for an unknown device id.                    *)
(*                                                                         *)
(* Ciphertexts are opaque: [id, lo, hi, gen, part], a fresh id per seal,   *)
(* covering items lo..hi, or part `part` of archive generation `gen`. The  *)
(* server (honest here) keeps one blob per slot.                           *)
(*                                                                         *)
(* AtomicEnqueue = FALSE is a sanity variant: the row is written first and *)
(* the seq/progress in a second step, as with the earlier pending files.   *)
(*                                                                         *)
(* Archive = TRUE adds one archive file (app Uploader.uploadArchive),      *)
(* uploaded part by part. The file is replaced (atomic rename) by a larger *)
(* generation up to MaxGen - 1 times, at any moment; generation g has g    *)
(* parts. Progress is persisted with each part: the generation and next    *)
(* part (upgen, uppart), and `up` (handed over) with the last part.        *)
(*   Snapshot = TRUE (the code): the file is opened once; its size,        *)
(*     generation and every part come from that open descriptor, which     *)
(*     keeps the version it opened after a rename.                        *)
(*   Snapshot = FALSE (before the fix, review R1): each step reads the    *)
(*     size from the path, then the generation and the part from the path *)
(*     (checking the generation did not change while reading the part).   *)
(*     A rename between the two reads mixes the versions.                  *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS NItems, BatchMax, MaxReg, MaxFaults, AtomicEnqueue,
          Archive, Snapshot, MaxGen

None == [none |-> TRUE]
Legacy == 0   \* device id of the legacy pending file (an unknown registration)
Parts(g) == g \* parts of archive generation g

VARIABLES
  reg,       \* current registration = its device id; 0 = unpaired
  nregs,     \* registrations so far
  mark,      \* progress: items 1..mark are in a batch (persistent, per registration)
  nextSeq,   \* next seq (persistent, per registration)
  outbox,    \* persistent rows [dev, seq, ct]
  legacy,    \* a legacy pending file exists
  pend,      \* non-atomic variant only: half-done enqueue (in memory)
  ctr,       \* source of fresh ciphertext ids
  net,       \* PUT requests ever sent [dev, seq, ct]; deliverable any number of times
  server,    \* committed slots [dev, seq, ct]
  resp,      \* responses [req, code]
  inflight,  \* the request the running job waits for, or None
  sealed,    \* ghost: every <<dev, seq, ct>> ever sealed
  faults,
  fileGen,   \* archive: generation the file's path names now
  snap,      \* archive, in memory: Snapshot: the generation opened (0: none);
             \*   before the fix: the part count read from the path (0: none)
  upgen, uppart, \* archive progress (persistent, per registration); upgen 0: none
  up,        \* archive handed over (persistent, per registration)
  upOf       \* ghost: the generation whose last part set `up` (0: none)

vars == <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults,
          fileGen, snap, upgen, uppart, up, upOf>>
arch == <<fileGen, snap, upgen, uppart, up, upOf>>

Ct(id, lo, hi, g, p) == [id |-> id, lo |-> lo, hi |-> hi, gen |-> g, part |-> p]

Init ==
  /\ reg = 0 /\ nregs = 0 /\ mark = 0 /\ nextSeq = 0
  /\ outbox = {}
  /\ legacy = TRUE
  /\ pend = None
  /\ ctr = 1
  /\ net = {} /\ server = {} /\ resp = {}
  /\ inflight = None
  \* The legacy file: sealed for another device id; it must never be sent.
  /\ sealed = {<<Legacy, 0, Ct(0, 1, 1, 0, 0)>>}
  /\ faults = 0
  /\ fileGen = 1 /\ snap = 0 /\ upgen = 0 /\ uppart = 0 /\ up = FALSE /\ upOf = 0

Register ==
  /\ reg = 0 /\ nregs < MaxReg
  /\ reg' = nregs + 1 /\ nregs' = nregs + 1
  /\ UNCHANGED <<mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED arch

\* Unpairing cancels and waits for the running job, then clears progress,
\* seq counters and unsent rows of the registration.
Unpair ==
  /\ reg # 0
  /\ reg' = 0 /\ mark' = 0 /\ nextSeq' = 0 /\ outbox' = {}
  /\ inflight' = None /\ pend' = None
  /\ snap' = 0 /\ upgen' = 0 /\ uppart' = 0 /\ up' = FALSE /\ upOf' = 0
  /\ UNCHANGED <<nregs, legacy, ctr, net, server, resp, sealed, faults, fileGen>>

\* First step of every run: legacy pending files are discarded (never adopted).
DiscardLegacy ==
  /\ reg # 0 /\ legacy
  /\ legacy' = FALSE
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, pend, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED arch

Min(a, b) == IF a < b THEN a ELSE b

\* Seal items mark+1..hi into a fresh ciphertext for (reg, nextSeq).
Enqueue ==
  /\ reg # 0 /\ ~legacy /\ mark < NItems /\ pend = None /\ inflight = None
  /\ \E hi \in (mark + 1)..Min(mark + BatchMax, NItems):
       LET ct == Ct(ctr, mark + 1, hi, 0, 0)
           row == [dev |-> reg, seq |-> nextSeq, ct |-> ct]
       IN /\ outbox' = outbox \cup {row}
          /\ sealed' = sealed \cup {<<reg, nextSeq, ct>>}
          /\ ctr' = ctr + 1
          /\ IF AtomicEnqueue
               THEN /\ nextSeq' = nextSeq + 1 /\ mark' = hi /\ pend' = None
               ELSE /\ pend' = [hi |-> hi] /\ UNCHANGED <<nextSeq, mark>>
  /\ UNCHANGED <<reg, nregs, legacy, net, server, resp, inflight, faults>>
  /\ UNCHANGED arch

\* Non-atomic variant only: the second transaction.
EnqueueCommit ==
  /\ pend # None
  /\ nextSeq' = nextSeq + 1 /\ mark' = pend.hi /\ pend' = None
  /\ UNCHANGED <<reg, nregs, outbox, legacy, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED arch

---------------------------------------------------------------------------
\* Archive file.

\* ArchiveStore writes a larger version and renames it over the path.
Replace ==
  /\ Archive /\ fileGen < MaxGen
  /\ fileGen' = fileGen + 1
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED <<snap, upgen, uppart, up, upOf>>

ArchReady == Archive /\ reg # 0 /\ ~legacy /\ ~up /\ pend = None /\ inflight = None

\* Enqueue part p of `parts` of generation g, with its progress, in one transaction.
EnqueuePart(g, p, parts) ==
  LET ct == Ct(ctr, 0, 0, g, p)
      row == [dev |-> reg, seq |-> nextSeq, ct |-> ct]
  IN /\ outbox' = outbox \cup {row}
     /\ sealed' = sealed \cup {<<reg, nextSeq, ct>>}
     /\ ctr' = ctr + 1 /\ nextSeq' = nextSeq + 1
     /\ IF p + 1 = parts
          THEN up' = TRUE /\ upOf' = g /\ upgen' = 0 /\ uppart' = 0
          ELSE UNCHANGED <<up, upOf>> /\ upgen' = g /\ uppart' = p + 1

\* The code: open the file; size and generation from the same descriptor.
ArchOpen ==
  /\ ArchReady /\ Snapshot /\ snap = 0
  /\ snap' = fileGen
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED <<fileGen, upgen, uppart, up, upOf>>

\* The code: the next part of the opened version. Its last part completes
\* the generation only if the parts add up to that version's size.
ArchPart ==
  /\ ArchReady /\ Snapshot /\ snap # 0
  /\ LET g == snap
         p == IF upgen = g THEN uppart ELSE 0
     IN IF p >= Parts(g)
          THEN /\ up' = TRUE /\ upOf' = g /\ upgen' = 0 /\ uppart' = 0
               /\ UNCHANGED <<outbox, sealed, ctr, nextSeq>>
          ELSE EnqueuePart(g, p, Parts(g))
  /\ UNCHANGED <<reg, nregs, mark, legacy, pend, net, server, resp, inflight, faults, fileGen, snap>>

\* Before the fix: `f.length` of the path.
ArchSize ==
  /\ ArchReady /\ ~Snapshot /\ snap = 0
  /\ snap' = Parts(fileGen)
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>
  /\ UNCHANGED <<fileGen, upgen, uppart, up, upOf>>

\* Before the fix: generation(f) and readPart(f) of the path (the check
\* after reading passes when no rename came in between), with the part
\* count from the size read before. The loop reads the size again for the
\* next part.
ArchRead ==
  /\ ArchReady /\ ~Snapshot /\ snap # 0
  /\ LET g == fileGen
         parts == snap
         p == IF upgen = g THEN uppart ELSE 0
     IN IF p >= parts
          THEN /\ up' = TRUE /\ upOf' = g /\ upgen' = 0 /\ uppart' = 0
               /\ UNCHANGED <<outbox, sealed, ctr, nextSeq>>
          ELSE EnqueuePart(g, p, parts)
  /\ snap' = 0
  /\ UNCHANGED <<reg, nregs, mark, legacy, pend, net, server, resp, inflight, faults, fileGen>>

Arch == Replace \/ ArchOpen \/ ArchPart \/ ArchSize \/ ArchRead

---------------------------------------------------------------------------

\* flush: send an outbox row of this registration (a row of another device
\* id would be dropped, never sent).
Send ==
  /\ reg # 0 /\ ~legacy /\ inflight = None /\ pend = None
  /\ \E row \in outbox:
       /\ row.dev = reg
       /\ LET req == [dev |-> row.dev, seq |-> row.seq, ct |-> row.ct]
          IN /\ net' = net \cup {req}
             /\ inflight' = req
             \* A new connection: answers to earlier copies of this request went
             \* to connections that are gone.
             /\ resp' = {m \in resp : m.req # req}
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, server, sealed, faults>>
  /\ UNCHANGED arch

SlotOf(d, q) == {s \in server : s.dev = d /\ s.seq = q}

\* The server handles a (possibly duplicated or late) request.
Deliver(req) ==
  /\ LET slot == SlotOf(req.dev, req.seq) IN
       IF slot = {}
         THEN \/ /\ server' = server \cup {req}
                 /\ resp' = resp \cup {[req |-> req, code |-> 201]}
                 /\ UNCHANGED faults
              \/ /\ faults < MaxFaults     \* quota exceeded: nothing stored
                 /\ resp' = resp \cup {[req |-> req, code |-> 507]}
                 /\ faults' = faults + 1
                 /\ UNCHANGED server
         ELSE /\ resp' = resp \cup {[req |-> req,
                                     code |-> IF (CHOOSE s \in slot : TRUE).ct = req.ct THEN 200 ELSE 409]}
              /\ UNCHANGED <<server, faults>>
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, inflight, sealed>>
  /\ UNCHANGED arch

DeliverAny == \E req \in net : Deliver(req)
DeliverInflight == inflight # None /\ Deliver(inflight)

\* The running job gets the response of its request.
Receive ==
  /\ inflight # None
  /\ \E m \in resp :
       /\ m.req = inflight
       /\ outbox' = IF m.code \in {200, 201}
                      THEN {r \in outbox : ~(r.dev = inflight.dev /\ r.seq = inflight.seq /\ r.ct = inflight.ct)}
                      ELSE outbox
       /\ inflight' = None
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, legacy, pend, ctr, net, server, resp, sealed, faults>>
  /\ UNCHANGED arch

\* Lost response / timeout: the job gives up on this request (retries later).
Timeout ==
  /\ inflight # None /\ faults < MaxFaults
  /\ inflight' = None /\ faults' = faults + 1
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, sealed>>
  /\ UNCHANGED arch

\* Process death: in-memory state is lost, SQLite state survives.
Crash ==
  /\ faults < MaxFaults /\ (inflight # None \/ pend # None \/ snap # 0)
  /\ inflight' = None /\ pend' = None /\ snap' = 0 /\ faults' = faults + 1
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, ctr, net, server, resp, sealed>>
  /\ UNCHANGED <<fileGen, upgen, uppart, up, upOf>>

Next ==
  \/ Register \/ Unpair \/ DiscardLegacy \/ Enqueue \/ EnqueueCommit
  \/ Send \/ DeliverAny \/ Receive \/ Timeout \/ Crash \/ Arch

Fairness ==
  /\ WF_vars(DiscardLegacy) /\ WF_vars(Enqueue) /\ WF_vars(EnqueueCommit)
  /\ WF_vars(Send) /\ WF_vars(DeliverInflight) /\ WF_vars(Receive)
  /\ WF_vars(ArchOpen) /\ WF_vars(ArchPart) /\ WF_vars(ArchSize) /\ WF_vars(ArchRead)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
TypeOK ==
  /\ reg \in 0..MaxReg /\ mark \in 0..NItems
  /\ faults \in 0..MaxFaults
  /\ fileGen \in 1..MaxGen /\ upOf \in 0..MaxGen

\* The same (registration, stream, seq) always carries the same ciphertext.
SameSlotSameCiphertext ==
  \A a, b \in sealed : (a[1] = b[1] /\ a[2] = b[2]) => a[3] = b[3]

\* Only ciphertext sealed for the request's own slot reaches the server;
\* the legacy file is never sent.
OnlySealedSent ==
  \A r \in net : r.dev # Legacy /\ <<r.dev, r.seq, r.ct>> \in sealed

\* An honest client never meets 409 (a different blob in its slot).
NoConflict == \A m \in resp : m.code # 409

Covers(ct, i) == ct.lo <= i /\ i <= ct.hi

\* Data of the current registration is in the outbox or committed.
Backed(P(_)) ==
  \/ \E r \in outbox : r.dev = reg /\ P(r.ct)
  \/ \E s \in server : s.dev = reg /\ P(s.ct)

\* Progress marked as sent implies the data is in the outbox or committed.
ProgressBacked ==
  reg # 0 =>
    /\ \A i \in 1..mark : Backed(LAMBDA ct : Covers(ct, i))
    /\ upgen # 0 => \A p \in 0..(uppart - 1) : Backed(LAMBDA ct : ct.gen = upgen /\ ct.part = p)

\* A completed archive generation has all its parts, of that very
\* generation, in the outbox or on the server (review R1).
ArchiveComplete ==
  (reg # 0 /\ up) => \A p \in 0..(Parts(upOf) - 1) : Backed(LAMBDA ct : ct.gen = upOf /\ ct.part = p)

\* Liveness: once faults and re-registrations stop, the current
\* registration's data all reaches the server.
AllUploaded ==
  reg # 0 /\ mark = NItems /\ outbox = {} /\ pend = None /\ (Archive => up)
Done == <>[](reg = 0 \/ AllUploaded)
=============================================================================
