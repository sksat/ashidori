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
(* Ciphertexts are opaque: [id, lo, hi], a fresh id per seal, covering     *)
(* items lo..hi. The server (honest here) keeps one blob per slot.         *)
(*                                                                         *)
(* AtomicEnqueue = FALSE is a sanity variant: the row is written first and *)
(* the seq/progress in a second step, as with the earlier pending files.   *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS NItems, BatchMax, MaxReg, MaxFaults, AtomicEnqueue

None == [none |-> TRUE]
Legacy == 0   \* device id of the legacy pending file (an unknown registration)

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
  faults

vars == <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>

Init ==
  /\ reg = 0 /\ nregs = 0 /\ mark = 0 /\ nextSeq = 0
  /\ outbox = {}
  /\ legacy = TRUE
  /\ pend = None
  /\ ctr = 1
  /\ net = {} /\ server = {} /\ resp = {}
  /\ inflight = None
  \* The legacy file: sealed for another device id; it must never be sent.
  /\ sealed = {<<Legacy, 0, [id |-> 0, lo |-> 1, hi |-> 1]>>}
  /\ faults = 0

Register ==
  /\ reg = 0 /\ nregs < MaxReg
  /\ reg' = nregs + 1 /\ nregs' = nregs + 1
  /\ UNCHANGED <<mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, inflight, sealed, faults>>

\* Unpairing cancels and waits for the running job, then clears progress,
\* seq counters and unsent rows of the registration.
Unpair ==
  /\ reg # 0
  /\ reg' = 0 /\ mark' = 0 /\ nextSeq' = 0 /\ outbox' = {}
  /\ inflight' = None /\ pend' = None
  /\ UNCHANGED <<nregs, legacy, ctr, net, server, resp, sealed, faults>>

\* First step of every run: legacy pending files are discarded (never adopted).
DiscardLegacy ==
  /\ reg # 0 /\ legacy
  /\ legacy' = FALSE
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, pend, ctr, net, server, resp, inflight, sealed, faults>>

Min(a, b) == IF a < b THEN a ELSE b

\* Seal items mark+1..hi into a fresh ciphertext for (reg, nextSeq).
Enqueue ==
  /\ reg # 0 /\ ~legacy /\ mark < NItems /\ pend = None /\ inflight = None
  /\ \E hi \in (mark + 1)..Min(mark + BatchMax, NItems):
       LET ct == [id |-> ctr, lo |-> mark + 1, hi |-> hi]
           row == [dev |-> reg, seq |-> nextSeq, ct |-> ct]
       IN /\ outbox' = outbox \cup {row}
          /\ sealed' = sealed \cup {<<reg, nextSeq, ct>>}
          /\ ctr' = ctr + 1
          /\ IF AtomicEnqueue
               THEN /\ nextSeq' = nextSeq + 1 /\ mark' = hi /\ pend' = None
               ELSE /\ pend' = [hi |-> hi] /\ UNCHANGED <<nextSeq, mark>>
  /\ UNCHANGED <<reg, nregs, legacy, net, server, resp, inflight, faults>>

\* Non-atomic variant only: the second transaction.
EnqueueCommit ==
  /\ pend # None
  /\ nextSeq' = nextSeq + 1 /\ mark' = pend.hi /\ pend' = None
  /\ UNCHANGED <<reg, nregs, outbox, legacy, ctr, net, server, resp, inflight, sealed, faults>>

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

\* Lost response / timeout: the job gives up on this request (retries later).
Timeout ==
  /\ inflight # None /\ faults < MaxFaults
  /\ inflight' = None /\ faults' = faults + 1
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, pend, ctr, net, server, resp, sealed>>

\* Process death: in-memory state is lost, SQLite state survives.
Crash ==
  /\ faults < MaxFaults /\ (inflight # None \/ pend # None)
  /\ inflight' = None /\ pend' = None /\ faults' = faults + 1
  /\ UNCHANGED <<reg, nregs, mark, nextSeq, outbox, legacy, ctr, net, server, resp, sealed>>

Next ==
  \/ Register \/ Unpair \/ DiscardLegacy \/ Enqueue \/ EnqueueCommit
  \/ Send \/ DeliverAny \/ Receive \/ Timeout \/ Crash

Fairness ==
  /\ WF_vars(DiscardLegacy) /\ WF_vars(Enqueue) /\ WF_vars(EnqueueCommit)
  /\ WF_vars(Send) /\ WF_vars(DeliverInflight) /\ WF_vars(Receive)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
TypeOK ==
  /\ reg \in 0..MaxReg /\ mark \in 0..NItems
  /\ faults \in 0..MaxFaults

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

\* Progress marked as sent implies the data is in the outbox or committed.
ProgressBacked ==
  reg # 0 =>
    \A i \in 1..mark :
      \/ \E r \in outbox : r.dev = reg /\ Covers(r.ct, i)
      \/ \E s \in server : s.dev = reg /\ Covers(s.ct, i)

\* Liveness: once faults and re-registrations stop, the current
\* registration's data all reaches the server.
AllUploaded ==
  reg # 0 /\ mark = NItems /\ outbox = {} /\ pend = None
Done == <>[](reg = 0 \/ AllUploaded)
=============================================================================
