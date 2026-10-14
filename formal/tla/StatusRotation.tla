--------------------------- MODULE StatusRotation ---------------------------
(***************************************************************************)
(* Candidates, rotation and run budgets of the status download (core       *)
(* StatusSyncState: take, trim, targets, failed, succeeded, retries,       *)
(* rescanIfDue, rescanned; app PeerStatus.pull), for a listing whose rows  *)
(* are fixed: the genuine statuses and the rows a server made up are all   *)
(* listed from the start and stay listed (review F6). Listing cursors and  *)
(* malformed rows are in StatusDownload.tla.                               *)
(*                                                                         *)
(* Listing: a sequence of status rows [dev, seq, good] of the other        *)
(* devices; row k has cursor k. Pages hold PageSize rows. Seqs differ per  *)
(* device (a made-up row with the seq of a genuine one is in               *)
(* StatusDownload.tla), so the candidate order of the code (seq, newest    *)
(* first) is a total order.                                                *)
(*                                                                         *)
(* A run (one sync job): rescanIfDue; the main walk (at most MaxPages      *)
(* pages, MaxPages - RescanPages while a rescan runs); the rescan (the     *)
(* rest of the pages); one fetch per device (its target); then retries of *)
(* exhausted candidates that are due, at most one per device and          *)
(* MaxRetries in all, those due longest first. Time: one run is a unit;  *)
(* a rescan starts when wanted and Gap runs passed since the previous one, *)
(* or after Week runs. An exhausted candidate is due from the run after   *)
(* it failed (the code waits 1 h, doubling up to a day: later, and the    *)
(* order by due time is kept here as the order of failures).               *)
(*                                                                         *)
(* A fetch of a made-up or broken row fails (hash, size, decryption); a    *)
(* genuine row succeeds, except for MaxFaults faults (a network error:     *)
(* nothing changes; or a 404 / another blob: a failure).                   *)
(*                                                                         *)
(* Rotate = FALSE is the code before the sweep (review F6): candidates are *)
(* kept and tried newest first only.                                       *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS Devs,        \* other devices
          Listing,     \* <<[dev, seq, good], ...>>: row k has cursor k
          PageSize, MaxPages, RescanPages, MaxRetries,
          MaxCand,     \* kept per device: this many still to try, and this many exhausted
          MaxAttempts, \* failures after which a candidate is exhausted
          MaxFaults, Gap, Week,
          Rotate,
          PassBound    \* the genuine status is read within this many completed rescans

None == -1
NoSweep == [lo |-> 1, hi |-> 0]   \* an empty range: no pass
Rows == 1..Len(Listing)
SeqOf(i) == Listing[i].seq
Max(S) == CHOOSE x \in S : \A y \in S : y <= x
Min(S) == CHOOSE x \in S : \A y \in S : x <= y

VARIABLES
  cands,     \* per device: set of [i (row), f (failures)]
  auth,      \* per device: highest seq read (-1: none)
  unres,     \* per device: seq of the newest unreadable status shown (-1: none)
  sweep,     \* per device: [lo, hi] of the seqs exhausted in this pass, or NoSweep
  cursor, rescan, wanted, first, since,
  rq,        \* exhausted candidates <<d, i>>, due longest first
  fresh,     \* entries of rq that failed in this run (not due yet)
  phase, pages, todoF, todoR,   \* the run in progress
  faults,
  listed,    \* ghost: rows ever listed (main walk or rescan)
  tried,     \* ghost: rows whose blob was received and checked
  okSeqs,    \* ghost: <<d, seq>> read successfully
  passes     \* ghost: rescans started (counted up to PassBound + 1)

vars == <<cands, auth, unres, sweep, cursor, rescan, wanted, first, since, rq, fresh, phase, pages, todoF, todoR,
          faults, listed, tried, okSeqs, passes>>

Init ==
  /\ cands = [d \in Devs |-> {}] /\ auth = [d \in Devs |-> -1] /\ unres = [d \in Devs |-> -1]
  /\ sweep = [d \in Devs |-> NoSweep]
  /\ cursor = 0 /\ rescan = None /\ wanted = FALSE /\ first = TRUE /\ since = 0
  /\ rq = <<>> /\ fresh = {}
  /\ phase = "idle" /\ pages = 0 /\ todoF = {} /\ todoR = {}
  /\ faults = 0
  /\ listed = {} /\ tried = {} /\ okSeqs = {} /\ passes = 0

----------------------------------------------------------------------------
\* StatusSyncState, as functions of the state.

Exhausted(c) == c.f >= MaxAttempts
Covers(w, q) == w.lo <= q /\ q <= w.hi
\* rank: outside the sweep before inside it, then newest first.
Better(a, b, w) ==
  LET ca == Rotate /\ Covers(w, SeqOf(a.i))
      cb == Rotate /\ Covers(w, SeqOf(b.i))
  IN (~ca /\ cb) \/ (ca = cb /\ SeqOf(a.i) > SeqOf(b.i))
RECURSIVE Best(_, _, _)
\* The n best of S by Better(_, _, w).
Best(S, n, w) ==
  IF n = 0 \/ S = {} THEN {}
  ELSE LET b == CHOOSE c \in S : \A x \in S : x = c \/ Better(c, x, w)
       IN {b} \cup Best(S \ {b}, n - 1, w)
\* trim: the best MaxCand still to try, and the newest MaxCand exhausted.
Trim(S, w) == Best({c \in S : ~Exhausted(c)}, MaxCand, w) \cup Best({c \in S : Exhausted(c)}, MaxCand, NoSweep)

Live(d) == {c \in cands[d] : SeqOf(c.i) > auth[d] /\ ~Exhausted(c)}
HasTarget(d) == Live(d) # {}
Target(d) == CHOOSE c \in Live(d) : \A x \in Live(d) : x = c \/ Better(c, x, sweep[d])
Problem(d) == unres[d] > auth[d]

\* take: the page's rows in order, each added (and trimmed) unless read
\* already or kept already.
RECURSIVE TakeRows(_, _)
TakeRows(cs, is) ==
  IF is = <<>> THEN cs
  ELSE LET i == Head(is)
           d == Listing[i].dev
           add == SeqOf(i) > auth[d] /\ ~\E c \in cs[d] : c.i = i
           next == IF add THEN [cs EXCEPT ![d] = Trim(@ \cup {[i |-> i, f |-> 0]}, sweep[d])] ELSE cs
       IN TakeRows(next, Tail(is))

\* The page after position p: rows p+1 .. p+PageSize (as a sequence), and
\* whether the server gives a `next`.
Page(p) == [k \in 1..(IF Len(Listing) - p < PageSize THEN Len(Listing) - p ELSE PageSize) |-> p + k]
More(p) == p + PageSize < Len(Listing)
RowsOf(is) == {is[k] : k \in DOMAIN is}

\* Exhausted candidates kept in the due order: entries of dropped or read
\* candidates leave it.
Prune(q, cs, au) == SelectSeq(q, LAMBDA e : \E c \in cs[e[1]] : c.i = e[2] /\ Exhausted(c) /\ SeqOf(c.i) > au[e[1]])

----------------------------------------------------------------------------
\* A run.

\* rescanIfDue: the first walk needs none; later a rescan starts after Week
\* runs, or after Gap runs when one is wanted (`since`: runs since the
\* previous one started).
RunStart ==
  /\ phase = "idle"
  /\ LET s1 == IF since < Week THEN since + 1 ELSE Week
         start == rescan = None /\ ~first /\ (s1 >= Week \/ (wanted /\ s1 >= Gap))
     IN /\ first' = FALSE
        /\ rescan' = IF start THEN 0 ELSE rescan
        /\ passes' = IF start /\ passes <= PassBound THEN passes + 1 ELSE passes
        /\ wanted' = IF start THEN FALSE ELSE wanted
        /\ since' = IF start \/ first THEN 0 ELSE s1
  /\ phase' = "main" /\ pages' = 0 /\ fresh' = {}
  /\ UNCHANGED <<cands, auth, unres, sweep, cursor, rq, todoF, todoR, faults, listed, tried, okSeqs>>

MainBudget == IF rescan # None THEN MaxPages - RescanPages ELSE MaxPages

\* One page of the main walk (listed). The walk goes on while the page had
\* rows and a `next`, and the page budget lasts.
MainPage ==
  /\ phase = "main" /\ pages < MainBudget
  /\ LET is == Page(cursor) IN
       /\ cands' = TakeRows(cands, is)
       /\ cursor' = IF is = <<>> THEN cursor ELSE is[Len(is)]
       /\ listed' = listed \cup RowsOf(is)
       /\ phase' = IF More(cursor) /\ is # <<>> THEN "main" ELSE "rescan"
  /\ pages' = pages + 1
  /\ UNCHANGED <<auth, unres, sweep, rescan, wanted, first, since, rq, fresh, todoF, todoR, faults, tried, okSeqs,
                 passes>>
MainOut ==
  /\ phase = "main" /\ pages >= MainBudget
  /\ phase' = "rescan"
  /\ UNCHANGED <<cands, auth, unres, sweep, cursor, rescan, wanted, first, since, rq, fresh, pages, todoF, todoR,
                 faults, listed, tried, okSeqs, passes>>

\* One page of the rescan (rescanned). At its end the cursor becomes where
\* it ended, and a device's pass ends if it has no row outside its sweep
\* still to try.
RescanPage ==
  /\ phase = "rescan" /\ rescan # None /\ pages < MaxPages
  /\ LET is == Page(rescan)
         to == IF is = <<>> THEN rescan ELSE is[Len(is)]
         cs == TakeRows(cands, is)
         live(d) == {c \in cs[d] : SeqOf(c.i) > auth[d] /\ ~Exhausted(c)}
     IN /\ cands' = cs
        /\ listed' = listed \cup RowsOf(is)
        /\ pages' = pages + 1
        /\ IF More(rescan) /\ is # <<>> /\ to > rescan
             THEN rescan' = to /\ UNCHANGED <<cursor, sweep>>
             ELSE /\ cursor' = to /\ rescan' = None
                  /\ sweep' = [d \in Devs |-> IF \E c \in live(d) : ~Covers(sweep[d], SeqOf(c.i)) THEN sweep[d] ELSE NoSweep]
  /\ UNCHANGED <<auth, unres, wanted, first, since, rq, fresh, phase, todoF, todoR, faults, tried, okSeqs, passes>>
RescanOut ==
  /\ phase = "rescan" /\ (rescan = None \/ pages >= MaxPages)
  /\ phase' = "fetch" /\ todoF' = {d \in Devs : HasTarget(d)}
  /\ UNCHANGED <<cands, auth, unres, sweep, cursor, rescan, wanted, first, since, rq, fresh, pages, todoR,
                 faults, listed, tried, okSeqs, passes>>

\* succeeded for device d, candidate c (and the due order without the
\* candidates it dropped).
Succeed(d, c) ==
  LET a == IF SeqOf(c.i) > auth[d] THEN SeqOf(c.i) ELSE auth[d]
      ncs == [cands EXCEPT ![d] = {x \in @ : SeqOf(x.i) > a}]
      na == [auth EXCEPT ![d] = a]
  IN /\ auth' = na
     /\ sweep' = [sweep EXCEPT ![d] = IF @.hi > a THEN @ ELSE NoSweep]
     /\ cands' = ncs
     /\ unres' = [unres EXCEPT ![d] = IF @ > SeqOf(c.i) THEN @ ELSE -1]
     /\ okSeqs' = okSeqs \cup {<<d, SeqOf(c.i)>>}
     /\ rq' = Prune(rq, ncs, na)
     /\ UNCHANGED <<wanted, fresh>>
\* failed for device d, candidate c. An exhausted candidate goes to the end
\* of the due order (due last); dropped ones leave it. (The failure count
\* is kept up to MaxAttempts + 1: beyond, the code only lengthens the wait,
\* which the due order stands for.)
Fail(d, c) ==
  LET f == IF c.f > MaxAttempts THEN c.f ELSE c.f + 1
      q == SeqOf(c.i)
      w == IF f = MaxAttempts
             THEN IF sweep[d] = NoSweep THEN [lo |-> q, hi |-> q]
                  ELSE [lo |-> Min({sweep[d].lo, q}), hi |-> Max({sweep[d].hi, q})]
             ELSE sweep[d]
      cs == Trim((cands[d] \ {c}) \cup {[c EXCEPT !.f = f]}, IF Rotate THEN w ELSE NoSweep)
      ncs == [cands EXCEPT ![d] = cs]
      kept == \E x \in cs : x.i = c.i /\ Exhausted(x)
      q2 == IF kept THEN Append(SelectSeq(rq, LAMBDA e : e # <<d, c.i>>), <<d, c.i>>) ELSE rq
  IN /\ sweep' = [sweep EXCEPT ![d] = IF Rotate THEN w ELSE NoSweep]
     /\ cands' = ncs
     /\ unres' = [unres EXCEPT ![d] = IF @ > auth[d] /\ @ > q THEN @ ELSE q]
     /\ fresh' = IF f >= MaxAttempts THEN fresh \cup {<<d, c.i>>} ELSE fresh
     /\ rq' = Prune(q2, ncs, auth)
     \* No target left for the device: a rescan is wanted.
     /\ wanted' = (wanted \/ ~\E x \in cs : SeqOf(x.i) > auth[d] /\ ~Exhausted(x))
     /\ UNCHANGED <<auth, okSeqs>>

\* The blob of candidate c of device d: received and read, received and
\* unreadable, or a network error (nothing changes).
FetchOutcome(d, c) ==
  \/ /\ Listing[c.i].good /\ Succeed(d, c) /\ tried' = tried \cup {c.i} /\ UNCHANGED faults
  \/ /\ ~Listing[c.i].good /\ Fail(d, c) /\ tried' = tried \cup {c.i} /\ UNCHANGED faults
  \/ /\ Listing[c.i].good /\ faults < MaxFaults /\ faults' = faults + 1   \* 404 or another blob
     /\ Fail(d, c) /\ tried' = tried \cup {c.i}
  \/ /\ faults < MaxFaults /\ faults' = faults + 1                         \* network error
     /\ UNCHANGED <<cands, auth, unres, sweep, wanted, fresh, okSeqs, tried, rq>>

\* The normal fetch: one per device, its target.
FetchStep ==
  /\ phase = "fetch"
  /\ IF todoF = {}
       THEN /\ phase' = "retry"
            \* retries: per device the one due longest, MaxRetries in all.
            /\ LET due == SelectSeq(rq, LAMBDA e : e \notin fresh)
                   firstOf(k) == ~\E j \in 1..(k - 1) : due[j][1] = due[k][1]
                   picks == SelectSeq([k \in DOMAIN due |-> k], LAMBDA k : firstOf(k))
               IN todoR' = {due[picks[k]] : k \in 1..(IF Len(picks) < MaxRetries THEN Len(picks) ELSE MaxRetries)}
            /\ UNCHANGED <<cands, auth, unres, sweep, wanted, fresh, rq, todoF, faults, tried, okSeqs>>
       ELSE \E d \in todoF :
              /\ todoF' = todoF \ {d}
              /\ IF HasTarget(d)
                   THEN FetchOutcome(d, Target(d))
                   ELSE UNCHANGED <<cands, auth, unres, sweep, wanted, fresh, rq, faults, tried, okSeqs>>
              /\ UNCHANGED <<todoR, phase>>
  /\ UNCHANGED <<cursor, rescan, first, since, pages, listed, passes>>

RetryStep ==
  /\ phase = "retry"
  /\ IF todoR = {}
       THEN phase' = "idle" /\ UNCHANGED <<cands, auth, unres, sweep, wanted, fresh, rq, todoR, faults, tried, okSeqs>>
       ELSE \E e \in todoR :
              /\ todoR' = todoR \ {e}
              /\ LET d == e[1] IN
                   IF \E c \in cands[d] : c.i = e[2] /\ Exhausted(c) /\ SeqOf(c.i) > auth[d]
                     THEN LET c == CHOOSE c \in cands[d] : c.i = e[2] IN
                          FetchOutcome(d, c)
                     ELSE UNCHANGED <<cands, auth, unres, sweep, wanted, fresh, rq, faults, tried, okSeqs>>
              /\ UNCHANGED phase
  /\ UNCHANGED <<cursor, rescan, first, since, pages, todoF, listed, passes>>

Next == RunStart \/ MainPage \/ MainOut \/ RescanPage \/ RescanOut \/ FetchStep \/ RetryStep

Spec == Init /\ [][Next]_vars

\* The liveness assumption: sync jobs keep running (WorkManager runs the
\* hourly job), i.e. runs start again and again. A run that starts again has
\* finished the one before, so this is the fairness of every step. It is a
\* premise of the properties below rather than a fairness condition of Spec:
\* TLC evaluates ENABLED of these actions very slowly.
RunsRecur == []<><<RunStart>>_vars

----------------------------------------------------------------------------
Genuine(d) == {i \in Rows : Listing[i].dev = d /\ Listing[i].good}
Newest(d) == IF Genuine(d) = {} THEN -1 ELSE Max({SeqOf(i) : i \in Genuine(d)})
Settled(d) == auth[d] >= Newest(d)

\* The read seq only comes from a successful read of that very seq.
AuthOnlyOnSuccess == \A d \in Devs : auth[d] = -1 \/ <<d, auth[d]>> \in okSeqs
AuthMonotone == [][\A d \in Devs : auth'[d] >= auth[d]]_vars
CandsBounded == \A d \in Devs : Cardinality(cands[d]) <= 2 * MaxCand

\* No silent skip, against the history of listed rows (not only the kept
\* candidates): if a row newer than the read seq was ever listed for a
\* device, the device has a row to try or shows a problem.
NoSilentSkip ==
  \A d \in Devs :
    (\E i \in listed : Listing[i].dev = d /\ SeqOf(i) > auth[d]) => (HasTarget(d) \/ Problem(d))

\* The genuine status is read within PassBound rescans. (Meaningful with the
\* app's intervals, Gap and Week; with rescans as often as every run, the
\* bound is on runs rather than rescans.)
ReadWithinPasses == \A d \in Devs : Settled(d) \/ passes <= PassBound

\* Liveness: while runs recur, the newest genuine status of every device is read.
StatusLive == RunsRecur => \A d \in Devs : <>[]Settled(d)
\* Liveness: while runs recur, every listed row newer than the read seq is
\* tried at some point.
EveryListedTried ==
  RunsRecur =>
    \A i \in Rows : (i \in listed /\ SeqOf(i) > auth[Listing[i].dev]) ~> (i \in tried \/ SeqOf(i) <= auth[Listing[i].dev])

\* Listings for the configs (written out: TLC re-evaluates a computed one):
\* a genuine seq 0, then made-up seqs 1..n, of device 2 (G: genuine, F(q):
\* made up), and the same for device 3 (H, E(q)).
G == [dev |-> 2, seq |-> 0, good |-> TRUE]
F(q) == [dev |-> 2, seq |-> q, good |-> FALSE]
H == [dev |-> 3, seq |-> 0, good |-> TRUE]
E(q) == [dev |-> 3, seq |-> q, good |-> FALSE]
MCL0 == <<G>>
MCL4 == <<G, F(1), F(2), F(3), F(4)>>
MCL5 == <<G, F(1), F(2), F(3), F(4), F(5)>>
MCL8 == <<G, F(1), F(2), F(3), F(4), F(5), F(6), F(7), F(8)>>
MCL55 == <<G, H, F(1), E(1), F(2), E(2), F(3), E(3), F(4), E(4), F(5), E(5)>>
=============================================================================
