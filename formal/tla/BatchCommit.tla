---------------------------- MODULE BatchCommit ----------------------------
(***************************************************************************)
(* Server side of PUT /v1/batches/:stream/:seq (server/src/batches.ts) and *)
(* the orphan sweep (sweepOrphanBlobs).                                    *)
(*                                                                         *)
(* One attempt = one PUT request:                                          *)
(*   Start   read the slot (idempotency / conflict), remember start time   *)
(*   Put     R2 put under a key no other attempt uses (key = attempt id)   *)
(*   Commit  one D1 statement: slot free, device active, user quota and    *)
(*           batch count, server-wide bytes; counters change in the same   *)
(*           statement (triggers)                                          *)
(*   DelOwn  a losing attempt deletes its own object (best effort)         *)
(* Any attempt may stop for good between steps (Worker killed, timeout).   *)
(* Time passes freely between steps: nothing bounds how long an attempt    *)
(* waits between Put and Commit.                                           *)
(*                                                                         *)
(* The sweep deletes objects older than Grace that no row references. It  *)
(* is two steps (list+D1 check, then delete), as in the code.              *)
(*                                                                         *)
(* CommitDeadline = 0 models the current code. CommitDeadline = D > 0      *)
(* models the proposed fix: D1 refuses the commit when now - start > D,    *)
(* with `now` taken by D1 itself and start taken before the R2 put.        *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Devs, Users, Owner, Seqs, Shas, Size, Attempts,
          Quota, MaxBatches, MaxTotal, Grace, CommitDeadline, MaxTime

VARIABLES
  now,       \* one clock for the Worker, D1 and R2 (no skew modelled)
  att,       \* attempt state
  r2,        \* objects [key, dev, seq, sha, t]
  rows,      \* D1 batches [dev, seq, sha, key]
  ubytes, ucount, total,   \* usage counters
  revoked,   \* revoked devices
  cand,      \* keys the sweep found unreferenced and old, to delete
  swept      \* ghost: the sweep deleted a key that a row referenced at that time

vars == <<now, att, r2, rows, ubytes, ucount, total, revoked, cand, swept>>

Init ==
  /\ now = 0
  /\ att = [a \in Attempts |-> [pc |-> "idle", dev |-> CHOOSE d \in Devs : TRUE,
                                seq |-> CHOOSE q \in Seqs : TRUE, sha |-> CHOOSE s \in Shas : TRUE,
                                start |-> 0]]
  /\ r2 = {} /\ rows = {}
  /\ ubytes = [u \in Users |-> 0] /\ ucount = [u \in Users |-> 0] /\ total = 0
  /\ revoked = {}
  /\ cand = {}
  /\ swept = FALSE

SlotTaken(d, q) == \E r \in rows : r.dev = d /\ r.seq = q

Start(a) ==
  /\ att[a].pc = "idle"
  /\ \E d \in Devs, q \in Seqs, s \in Shas :
       \* An existing slot answers 200/409 without writing anything.
       att' = [att EXCEPT ![a] = [pc |-> IF SlotTaken(d, q) THEN "done" ELSE "checked",
                                  dev |-> d, seq |-> q, sha |-> s, start |-> now]]
  /\ UNCHANGED <<now, r2, rows, ubytes, ucount, total, revoked, cand, swept>>

Put(a) ==
  /\ att[a].pc = "checked"
  /\ r2' = r2 \cup {[key |-> a, dev |-> att[a].dev, seq |-> att[a].seq, sha |-> att[a].sha, t |-> now]}
  /\ att' = [att EXCEPT ![a].pc = "put"]
  /\ UNCHANGED <<now, rows, ubytes, ucount, total, revoked, cand, swept>>

Commit(a) ==
  /\ att[a].pc = "put"
  /\ LET x == att[a]
         u == Owner[x.dev]
         ok == /\ ~SlotTaken(x.dev, x.seq)
               /\ x.dev \notin revoked
               /\ ubytes[u] + Size[x.sha] <= Quota
               /\ ucount[u] + 1 <= MaxBatches
               /\ total + Size[x.sha] <= MaxTotal
               /\ (CommitDeadline = 0 \/ now - x.start <= CommitDeadline)
     IN IF ok
          THEN /\ rows' = rows \cup {[dev |-> x.dev, seq |-> x.seq, sha |-> x.sha, key |-> a]}
               /\ ubytes' = [ubytes EXCEPT ![u] = @ + Size[x.sha]]
               /\ ucount' = [ucount EXCEPT ![u] = @ + 1]
               /\ total' = total + Size[x.sha]
               /\ att' = [att EXCEPT ![a].pc = "done"]
          ELSE /\ att' = [att EXCEPT ![a].pc = "lost"]
               /\ UNCHANGED <<rows, ubytes, ucount, total>>
  /\ UNCHANGED <<now, r2, revoked, cand, swept>>

DelOwn(a) ==
  /\ att[a].pc = "lost"
  /\ r2' = {o \in r2 : o.key # a}
  /\ att' = [att EXCEPT ![a].pc = "done"]
  /\ UNCHANGED <<now, rows, ubytes, ucount, total, revoked, cand, swept>>

Stop(a) ==
  /\ att[a].pc \in {"checked", "put", "lost"}
  /\ att' = [att EXCEPT ![a].pc = "done"]
  /\ UNCHANGED <<now, r2, rows, ubytes, ucount, total, revoked, cand, swept>>

Revoke(d) ==
  /\ d \notin revoked
  /\ revoked' = revoked \cup {d}
  /\ UNCHANGED <<now, att, r2, rows, ubytes, ucount, total, cand, swept>>

Tick ==
  /\ now < MaxTime
  /\ now' = now + 1
  /\ UNCHANGED <<att, r2, rows, ubytes, ucount, total, revoked, cand, swept>>

Referenced(k) == \E r \in rows : r.key = k

\* List + D1 lookup: an old object that no row references.
SweepScan(o) ==
  /\ o \in r2 /\ now - o.t > Grace /\ ~Referenced(o.key)
  /\ cand' = cand \cup {o.key}
  /\ UNCHANGED <<now, att, r2, rows, ubytes, ucount, total, revoked, swept>>

SweepDelete(k) ==
  /\ k \in cand
  /\ swept' = (swept \/ Referenced(k))
  /\ r2' = {o \in r2 : o.key # k}
  /\ cand' = cand \ {k}
  /\ UNCHANGED <<now, att, rows, ubytes, ucount, total, revoked>>

Next ==
  \/ \E a \in Attempts : Start(a) \/ Put(a) \/ Commit(a) \/ DelOwn(a) \/ Stop(a)
  \/ \E d \in Devs : Revoke(d)
  \/ Tick
  \/ \E o \in r2 : SweepScan(o)
  \/ \E k \in cand : SweepDelete(k)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
RECURSIVE SumSize(_)
SumSize(S) == IF S = {} THEN 0
              ELSE LET r == CHOOSE r \in S : TRUE IN Size[r.sha] + SumSize(S \ {r})

RowsOf(u) == {r \in rows : Owner[r.dev] = u}

\* A committed row's object exists (and is the committed blob).
CommittedHasObject ==
  \A r \in rows : \E o \in r2 : o.key = r.key /\ o.sha = r.sha

\* The sweep never deletes a committed object.
SweepSafe == ~swept

OneRowPerSlot ==
  \A r1, r2x \in rows : (r1.dev = r2x.dev /\ r1.seq = r2x.seq) => r1 = r2x

CountersMatch ==
  /\ \A u \in Users : ubytes[u] = SumSize(RowsOf(u)) /\ ucount[u] = Cardinality(RowsOf(u))
  /\ total = SumSize(rows)

\* For the configs (TLC cannot write functions in .cfg files).
MCOwner == [d \in Devs |-> IF d = 3 THEN 2 ELSE 1]
MCSize == [s \in Shas |-> s]

UnderCaps ==
  /\ \A u \in Users : ubytes[u] <= Quota /\ ucount[u] <= MaxBatches
  /\ total <= MaxTotal
=============================================================================
