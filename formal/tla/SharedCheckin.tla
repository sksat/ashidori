--------------------------- MODULE SharedCheckin ---------------------------
(***************************************************************************)
(* Revisions of one shared check-in (one device, one localId):            *)
(* PUT /v1/checkins/shared/:localId, DELETE ...?rev=, DELETE by-id, and    *)
(* the admin removal (server/src/checkins.ts).                             *)
(*                                                                         *)
(* The client issues operations with strictly increasing revs (gaps        *)
(* allowed). The network delivers every issued operation any number of    *)
(* times, in any order (duplicates, reordering, long delays). The admin    *)
(* may remove the entry at any time.                                       *)
(*                                                                         *)
(* Fixed = TRUE (the design being implemented):                            *)
(*   PUT    applied iff rev > stored rev and not removed; an exact retry   *)
(*          of the PUT that set the current state answers 200.             *)
(*   DELETE applied iff rev > stored rev and not removed; an unknown id    *)
(*          gets a tombstone with rev; a retry of the DELETE that made the *)
(*          tombstone (same rev) answers "deleted"; a removed entry stays. *)
(*   admin  tombstone, removed = TRUE, final.                              *)
(* Fixed = FALSE is the earlier code (sanity variant): DELETE tombstones   *)
(* whatever its rev and raises the rev; DELETE of an unknown id is 404.    *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS MaxRev, Contents, Fixed

None == [none |-> TRUE]
Ops == [rev : 1..MaxRev, kind : {"put", "del"}, content : Contents \cup {"-"}]

VARIABLES
  lastRev,    \* client: highest rev issued
  net,        \* issued operations (deliverable any number of times)
  row,        \* server row: None or [rev, deleted, content, removed]
  delivered,  \* ghost: client operations the server has processed
  resps       \* ghost: [op, code] answers

vars == <<lastRev, net, row, delivered, resps>>

Init == lastRev = 0 /\ net = {} /\ row = None /\ delivered = {} /\ resps = {}

Issue ==
  \E r \in (lastRev + 1)..MaxRev, k \in {"put", "del"}, c \in Contents :
    /\ lastRev' = r
    /\ net' = net \cup {[rev |-> r, kind |-> k, content |-> IF k = "put" THEN c ELSE "-"]}
    /\ UNCHANGED <<row, delivered, resps>>

StateOf(op) == [rev |-> op.rev, deleted |-> op.kind = "del",
                content |-> op.content, removed |-> FALSE]

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
  /\ UNCHANGED <<lastRev, net>>

AdminRemove ==
  /\ row # None
  /\ row' = [rev |-> row.rev, deleted |-> TRUE, content |-> "-", removed |-> TRUE]
  /\ UNCHANGED <<lastRev, net, delivered, resps>>

Next == Issue \/ (\E op \in net : Deliver(op)) \/ AdminRemove

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
MaxOp == CHOOSE op \in delivered : \A o \in delivered : o.rev <= op.rev

\* The state is that of the highest-rev operation the server has seen.
LatestWins ==
  (row # None /\ ~row.removed) =>
    /\ delivered # {}
    /\ row = StateOf(MaxOp)

\* An entry exists only after an operation for it arrived (or never, before).
NoPhantom == row = None => delivered = {}

\* The admin removal is a tombstone and absorbing.
RemovedIsTombstone == (row # None /\ row.removed) => row.deleted
AdminAbsorbing == [][(row # None /\ row.removed) => (row' # None /\ row'.removed /\ row'.deleted)]_vars

\* "stale" is answered only when a later operation exists: a client may
\* treat it as superseded. Exact retries never get "stale".
StaleMeansSuperseded ==
  \A m \in resps : m.code = "stale" => \E o \in delivered : o.rev > m.op.rev
=============================================================================
