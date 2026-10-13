---------------------------- MODULE FeedResync ----------------------------
(***************************************************************************)
(* GET /v1/checkins/feed (server/src/checkins.ts listFeed) and the client  *)
(* algorithm of docs/sync-protocol.md "Feed": incremental by cursor, and a *)
(* full resync from after=0 whenever followsVersion differs from the value *)
(* stored with the cursor, checked on every page.                          *)
(*                                                                         *)
(* Server: entries of me (user 1) and of other users; every change moves   *)
(* the entry to a new, higher cursor (share, edit, unshare, owner disabled *)
(* or enabled); follows changes bump followsVersion. A page is one D1      *)
(* batch: followsVersion and the rows are read together.                   *)
(* Client: pages of Limit rows; the page is read and processed in one step *)
(* (nothing else is read in between, so a delay does not matter).          *)
(*                                                                         *)
(* CheckEveryPage = FALSE is a sanity variant: the version is checked only *)
(* on the first page of a sync.                                            *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS Entries, Owner, Others, Limit, MaxChanges, CheckEveryPage

Me == 1
Values == {"v1", "v2"}

VARIABLES
  est,       \* entry state: "none" (never shared), a value, or "del" (tombstone)
  ecur,      \* entry cursor (0 = none)
  gcur,      \* highest cursor
  follows,   \* accepted followees of Me
  fv,        \* followsVersion of Me
  active,    \* user status
  changes,
  cur, ver, view, phase, first,   \* client
  applied,   \* ghost: versions of the pages applied since the last reset
  fresh      \* ghost: a sync completed and the server has not changed since

vars == <<est, ecur, gcur, follows, fv, active, changes, cur, ver, view, phase, first, applied, fresh>>
server == <<est, ecur, gcur, follows, fv, active>>
client == <<cur, ver, view, phase, first, applied>>

Init ==
  /\ est = [e \in Entries |-> "none"] /\ ecur = [e \in Entries |-> 0] /\ gcur = 0
  /\ follows = {} /\ fv = 0 /\ active = [u \in Others |-> TRUE] /\ changes = 0
  /\ cur = 0 /\ ver = -1 /\ view = [e \in Entries |-> "absent"] /\ phase = "idle" /\ first = FALSE
  /\ applied = {} /\ fresh = FALSE

Active(u) == u = Me \/ active[u]
Visible(e) == Owner[e] = Me \/ Owner[e] \in follows
\* What a feed item says about an entry.
ItemVal(e) == IF est[e] \in Values /\ Active(Owner[e]) THEN est[e] ELSE "absent"
\* What the client should show.
Truth == [e \in Entries |-> IF Visible(e) THEN ItemVal(e) ELSE "absent"]

ServerStep(change) ==
  /\ changes < MaxChanges /\ change
  /\ changes' = changes + 1 /\ fresh' = FALSE
  /\ UNCHANGED client

Edit(e, v) == ServerStep(
  /\ est[e] # v /\ (v = "del" => est[e] # "none")
  /\ est' = [est EXCEPT ![e] = v] /\ ecur' = [ecur EXCEPT ![e] = gcur + 1] /\ gcur' = gcur + 1
  /\ UNCHANGED <<follows, fv, active>>)

\* Accept, unfollow, removal by the followee: the set changes, the version moves.
Follow(u) == ServerStep(
  /\ u \notin follows /\ follows' = follows \cup {u} /\ fv' = fv + 1
  /\ UNCHANGED <<est, ecur, gcur, active>>)
Unfollow(u) == ServerStep(
  /\ u \in follows /\ follows' = follows \ {u} /\ fv' = fv + 1
  /\ UNCHANGED <<est, ecur, gcur, active>>)

\* Disable or enable a user: all its entries move to new cursors (bumpUserCheckins).
Toggle(u) == ServerStep(
  /\ active' = [active EXCEPT ![u] = ~@]
  /\ LET mine == {e \in Entries : Owner[e] = u /\ ecur[e] # 0}
     IN \E order \in [mine -> 1..Cardinality(mine)] :
          /\ \A a, b \in mine : a # b => order[a] # order[b]
          /\ ecur' = [e \in Entries |-> IF e \in mine THEN gcur + order[e] ELSE ecur[e]]
          /\ gcur' = gcur + Cardinality(mine)
  /\ UNCHANGED <<est, follows, fv>>)

StartSync ==
  /\ phase = "idle" /\ phase' = "sync" /\ first' = TRUE
  /\ UNCHANGED <<server, changes, cur, ver, view, applied, fresh>>

\* Rows after `after` the caller may see, and the first Limit of them.
Rows(after) == {e \in Entries : Visible(e) /\ ecur[e] > after}
Below(e, S) == Cardinality({x \in S : ecur[x] < ecur[e]})
Page(after) == {e \in Rows(after) : Below(e, Rows(after)) < Limit}
MaxCur(S) == IF S = {} THEN 0 ELSE ecur[CHOOSE e \in S : \A x \in S : ecur[x] <= ecur[e]]

Fetch ==
  /\ phase = "sync"
  /\ LET items == Page(cur)
         more == Cardinality(Rows(cur)) > Limit
         check == CheckEveryPage \/ first
     IN IF ver # -1 /\ fv # ver /\ check
          THEN \* Discard the feed and sync again from after=0.
               /\ view' = [e \in Entries |-> "absent"] /\ cur' = 0 /\ ver' = fv
               /\ applied' = {} /\ first' = FALSE
               /\ UNCHANGED <<phase, fresh>>
          ELSE /\ view' = [e \in Entries |-> IF e \in items THEN ItemVal(e) ELSE view[e]]
               /\ cur' = IF items = {} THEN cur ELSE MaxCur(items)
               /\ ver' = fv
               /\ applied' = applied \cup {fv}
               /\ first' = FALSE
               /\ IF more THEN UNCHANGED <<phase, fresh>>
                          ELSE phase' = "idle" /\ fresh' = TRUE
  /\ UNCHANGED <<server, changes>>

Next ==
  \/ \E e \in Entries, v \in Values \cup {"del"} : Edit(e, v)
  \/ \E u \in Others : Follow(u) \/ Unfollow(u) \/ Toggle(u)
  \/ StartSync \/ Fetch

Spec == Init /\ [][Next]_vars /\ WF_vars(StartSync) /\ WF_vars(Fetch)

-----------------------------------------------------------------------------
\* Pages of different followsVersion values are never mixed in one view.
NoMixedVersions == Cardinality(applied) <= 1 /\ (applied # {} => applied = {ver})

\* A sync that completed with no server change since shows exactly the visible set.
ConvergedWhenStable == fresh => view = Truth

\* Liveness: once the server stops changing, the client converges.
Converges == <>[](view = Truth)

MCOwner == [e \in Entries |-> CASE e = 1 -> 1 [] e = 2 -> 2 [] OTHER -> 3]
=============================================================================
