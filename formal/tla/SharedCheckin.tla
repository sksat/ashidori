--------------------------- MODULE SharedCheckin ---------------------------
(***************************************************************************)
(* Revisions of one shared check-in (one sharing device's localId):        *)
(* PUT /v1/checkins/shared/:localId, DELETE ...?rev=, DELETE by id from    *)
(* another device of the user, and the admin removal                       *)
(* (server/src/checkins.ts upsertCheckin, unshareCheckin,                  *)
(* unshareCheckinById, removeCheckin).                                     *)
(*                                                                         *)
(* Every operation carries the client's rev (a DELETE without one is       *)
(* refused with 400, so it is not an operation here). Devices issue        *)
(* operations with revs from their own clock: strictly increasing per      *)
(* device. With one device that is the user's order of operations. With    *)
(* two (Devices), their clocks may differ: a later operation of one device *)
(* can carry a lower rev than an earlier one of the other, or the same rev *)
(* with other content. The network delivers every issued operation any     *)
(* number of times, in any order. The admin may remove the entry at any    *)
(* time; the removal sets rev to MAX(rev, now).                            *)
(*                                                                         *)
(*   PUT     applied iff rev > stored rev and not removed; an exact retry  *)
(*           of the PUT that set the current state answers 200; else       *)
(*           "stale", or "removed".                                        *)
(*   DELETE  applied iff rev > stored rev and not removed; an unknown id   *)
(*           gets a tombstone with rev; a retry of the DELETE that made    *)
(*           the tombstone (same rev) or a removed entry answers           *)
(*           "deleted"; else "stale".                                      *)
(*   admin   tombstone, removed, final.                                    *)
(* Fixed = FALSE is the earlier DELETE (tombstone whatever its rev, 404    *)
(* for an unknown id). Not modelled: the per-user entry limit (507), feed  *)
(* cursors.                                                                *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Devices, MaxRev, MaxOps, Contents, Fixed

None == [none |-> TRUE]

VARIABLES
  last,       \* per device: highest rev it issued (its clock)
  nops,       \* operations issued so far (also their issue order)
  net,        \* issued operations [dev, rev, kind, content, t] (deliverable any number of times)
  row,        \* server row: None or [rev, deleted, content, removed]
  delivered,  \* ghost: operations the server has processed
  resps       \* ghost: [op, code] answers

vars == <<last, nops, net, row, delivered, resps>>

Init == last = [d \in Devices |-> 0] /\ nops = 0 /\ net = {} /\ row = None /\ delivered = {} /\ resps = {}

\* Device d issues an operation with a rev above its own last one. The DELETE
\* by id comes from any device of the user and acts like the DELETE here.
Issue ==
  \E d \in Devices, r \in 1..MaxRev, k \in {"put", "del"}, c \in Contents :
    /\ nops < MaxOps /\ r > last[d]
    /\ last' = [last EXCEPT ![d] = r]
    /\ nops' = nops + 1
    /\ net' = net \cup {[dev |-> d, rev |-> r, kind |-> k, content |-> IF k = "put" THEN c ELSE "-", t |-> nops + 1]}
    /\ UNCHANGED <<row, delivered, resps>>

StateOf(op) == [rev |-> op.rev, deleted |-> op.kind = "del", content |-> op.content, removed |-> FALSE]

Apply(op) ==
  IF row = None
    THEN IF op.kind = "put" \/ Fixed
           THEN [row |-> StateOf(op), code |-> "ok"]
           ELSE [row |-> row, code |-> "notfound"]
  ELSE IF row.removed
    THEN [row |-> row, code |-> IF op.kind = "put" THEN "removed" ELSE "ok"]
  ELSE IF op.kind = "put"
    THEN IF op.rev > row.rev THEN [row |-> StateOf(op), code |-> "ok"]
         ELSE IF op.rev = row.rev /\ ~row.deleted /\ row.content = op.content
              THEN [row |-> row, code |-> "ok"]          \* exact retry
              ELSE [row |-> row, code |-> "stale"]
  ELSE \* DELETE
    IF Fixed
      THEN IF op.rev > row.rev THEN [row |-> StateOf(op), code |-> "ok"]
           ELSE IF op.rev = row.rev /\ row.deleted THEN [row |-> row, code |-> "ok"]
           ELSE [row |-> row, code |-> "stale"]
      ELSE \* earlier code: tombstone first, then rev = MAX(rev, ?)
           [row |-> [rev |-> IF op.rev > row.rev THEN op.rev ELSE row.rev, deleted |-> TRUE,
                     content |-> "-", removed |-> FALSE],
            code |-> "ok"]

Deliver(op) ==
  /\ LET out == Apply(op) IN
       /\ row' = out.row
       /\ resps' = resps \cup {[op |-> op, code |-> out.code]}
  /\ delivered' = delivered \cup {op}
  /\ UNCHANGED <<last, nops, net>>

\* removeCheckin: rev = MAX(rev, now); `now` is any rev (the admin's clock).
AdminRemove ==
  /\ row # None
  /\ \E now \in 0..MaxRev :
       row' = [rev |-> IF now > row.rev THEN now ELSE row.rev, deleted |-> TRUE, content |-> "-", removed |-> TRUE]
  /\ UNCHANGED <<last, nops, net, delivered, resps>>

DeliverAny == \E op \in net : Deliver(op)

Next == Issue \/ DeliverAny \/ AdminRemove

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
MaxRevOf(S) == CHOOSE r \in {o.rev : o \in S} : \A o \in S : o.rev <= r

\* The state is that of an operation with the highest rev the server has
\* seen (the first delivered of them, when two devices used the same rev).
LatestWins ==
  (row # None /\ ~row.removed) =>
    /\ delivered # {}
    /\ \E o \in delivered : o.rev = MaxRevOf(delivered) /\ row = StateOf(o)

\* An entry exists only after an operation for it arrived.
NoPhantom == row # None => delivered # {}

\* The admin removal is a tombstone and absorbing.
RemovedIsTombstone == (row # None /\ row.removed) => row.deleted
AdminAbsorbing == [][(row # None /\ row.removed) => (row' # None /\ row'.removed /\ row'.deleted)]_vars

\* "stale" is answered only when an operation with a higher rev arrived (so
\* exact retries never get it).
StaleMeansSuperseded ==
  \A m \in resps : m.code = "stale" => \E o \in delivered : o.rev > m.op.rev

\* "stale" is answered only when the user issued a later operation: the
\* client may treat its operation as superseded by the user's own intent.
\* Holds with one device (revs follow the user's order); not with clocks
\* that differ between devices.
StaleMeansLaterOp ==
  \A m \in resps : m.code = "stale" => \E o \in net : o.t > m.op.t

\* Reachability (run.sh reach:): each is expected to be violated.
Unreached_Stale == \A m \in resps : m.code # "stale"
Unreached_Removed == \A m \in resps : m.code # "removed"
\* A DELETE that arrived first left a tombstone.
Unreached_FirstDelete == ~(row # None /\ row.deleted /\ ~row.removed /\ \A o \in delivered : o.kind = "del")
=============================================================================
