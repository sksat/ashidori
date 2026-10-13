--------------------------- MODULE StatusDownload ---------------------------
(***************************************************************************)
(* Download of the other devices' status (docs/sync-protocol.md "Other     *)
(* devices' status"; core StatusSyncState, app PeerStatus.pull).           *)
(*                                                                         *)
(* A fetch of a candidate ends in one of:                                  *)
(*   network error   nothing changes (retried next run)                    *)
(*   listed blob     opened: success iff the blob is good                  *)
(*   other blob      hash/size differ from the listing: failure            *)
(*   404             failure                                               *)
(* Failures are unresolved, retried up to MaxAttempts times (3 in the app) *)
(* and shown.                                                              *)
(*                                                                         *)
(* Fixed = TRUE models the code. Client state: the listing cursor (only a  *)
(* fetch position, moved only to cursors of listed rows), per device the   *)
(* listed candidates newer than the authenticated seq, each with its       *)
(* failure count (the newest MaxCand still to try, and the newest MaxCand  *)
(* exhausted ones, which a rescan therefore does not bring back), the      *)
(* authenticated seq, an unresolved entry (the newest failure), and a      *)
(* rescan position. The target of a device is its newest                   *)
(* candidate that is not exhausted. A rescan walks the listing again from  *)
(* cursor 0 and, at its end, sets the cursor to where it ended. The code   *)
(* starts one weekly, or a day after a suspicious page or after a device   *)
(* ran out of candidates; here it may start at any time, and fairness      *)
(* makes it recur. The cursor-jump rule of the code applies only to short  *)
(* pages; with Limit = 1 every page is full, so it is not modelled and the *)
(* liveness below rests on candidates, retries and rescans alone.          *)
(*                                                                         *)
(* RetryExhausted = TRUE (the code; FALSE before the fix, review R5): an   *)
(* exhausted candidate is retried later, from a budget separate from the   *)
(* normal fetches. The code waits 1 hour, doubling up to a day, so every   *)
(* exhausted candidate is tried again within a day; here every exhausted   *)
(* candidate is retried once per round, and fairness makes rounds recur.   *)
(*                                                                         *)
(* CheckCursor = TRUE (the code; FALSE before the fix, review R4): a row   *)
(* whose cursor the API would not take back as `after` (more than 15       *)
(* digits, here > CursorMax) is malformed, so its page is refused and the  *)
(* cursor never takes it; a refused main walk does not stop the rescan or  *)
(* the fetches. Before the fix such a cursor was stored; the server then   *)
(* refused every listing from it, and that error ended the run before the  *)
(* rescan and the fetches.                                                 *)
(*                                                                         *)
(* Fixed = FALSE models the design before review #7: one candidate per     *)
(* device, replaced only by a higher seq, and no rescan.                   *)
(*                                                                         *)
(* Honest server: rows are immutable, every listed row has its blob, and a *)
(* blob is bad only if its uploader made it so (`good` = FALSE). Network   *)
(* errors are bounded.                                                     *)
(* Hostile = TRUE: it may serve other blobs or 404 (bounded by MaxNetErr,  *)
(* shared with network errors). Fakes: the fake rows it may list, each     *)
(* once (see FakeTab): a huge seq ("seq"), the same seq as a genuine       *)
(* status with another hash ("same"), a huge cursor ("cursor"), or a       *)
(* cursor out of the API's range ("bad"). Fake rows stay listed, except    *)
(* "bad" ones, which the server eventually stops listing (the attack       *)
(* stops; review R4 is about recovering after that).                       *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS Devs, Self, MaxRows, Limit, MaxNetErr,
          Hostile,    \* the server may serve other blobs or 404 (bounded)
          Fakes,      \* subset of DOMAIN FakeTab: the fake rows it may list
          BigSeq, BigCursor, CursorMax,
          Fixed,      \* TRUE: several candidates and rescans (the code)
          MaxCand,    \* candidates kept per device (Fixed)
          RetryExhausted, CheckCursor,
          MaxAttempts, \* failures after which a candidate is exhausted (3 in the app)
          Uploads     \* subset of {"other", "broken", "self"}: devices also upload
                      \* batches of another stream, broken statuses, and the client
                      \* device its own (ignored) statuses

ASSUME MaxAttempts \in Nat \ {0}
NoUnres == [seq |-> -1, attempts |-> 0]

\* The fake rows a hostile server may list (device, kind, offset).
FakeTab ==
  [s2 |-> [dev |-> 2, kind |-> "seq", n |-> 0],    t2 |-> [dev |-> 2, kind |-> "seq", n |-> 1],
   x2 |-> [dev |-> 2, kind |-> "same", n |-> 0],   y2 |-> [dev |-> 2, kind |-> "same", n |-> 1],
   c2 |-> [dev |-> 2, kind |-> "cursor", n |-> 0], b2 |-> [dev |-> 2, kind |-> "bad", n |-> 0],
   u2 |-> [dev |-> 2, kind |-> "seq", n |-> 4],    v2 |-> [dev |-> 2, kind |-> "seq", n |-> 5],
   s3 |-> [dev |-> 3, kind |-> "seq", n |-> 2],    t3 |-> [dev |-> 3, kind |-> "seq", n |-> 3],
   x3 |-> [dev |-> 3, kind |-> "same", n |-> 2],   y3 |-> [dev |-> 3, kind |-> "same", n |-> 3],
   c3 |-> [dev |-> 3, kind |-> "cursor", n |-> 1], b3 |-> [dev |-> 3, kind |-> "bad", n |-> 1]]

VARIABLES
  rows,      \* listed rows [cursor, dev, stream, seq, good, fake]
  gcur,      \* cursor of the newest genuine row
  nseq,      \* next status seq per device
  usedF,     \* fake rows listed so far (names)
  cursor, auth, unres,  \* client (StatusSyncState)
  latest,    \* client before the fix: the one candidate per device (seq)
  cands,     \* client (Fixed): candidates per device, [r: row, fails, rr: retried this round]
  rcur,      \* client (Fixed): rescan position, -1 when none runs
  shown,     \* client: seq of the status shown per device (-1: none)
  okSeqs,    \* ghost: <<dev, seq>> fetched as the listed, good blob
  neterr

vars == <<rows, gcur, nseq, usedF, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

Init ==
  /\ rows = {} /\ gcur = 0 /\ nseq = [d \in Devs |-> 0] /\ usedF = {}
  /\ cursor = 0
  /\ latest = [d \in Devs |-> -1] /\ auth = [d \in Devs |-> -1] /\ unres = [d \in Devs |-> NoUnres]
  /\ cands = [d \in Devs |-> {}] /\ rcur = -1
  /\ shown = [d \in Devs |-> -1] /\ okSeqs = {} /\ neterr = 0

Genuine == {r \in rows : ~r.fake}
OutOfRange(c) == c > CursorMax

\* A device uploads a status (good or broken) or a batch of another stream.
Upload(d) ==
  /\ Cardinality(Genuine) < MaxRows
  /\ d = Self => "self" \in Uploads
  /\ \E s \in IF "other" \in Uploads THEN {"status", "other"} ELSE {"status"},
        g \in IF "broken" \in Uploads THEN BOOLEAN ELSE {TRUE} :
       /\ (s = "other" => g)
       /\ rows' = rows \cup {[cursor |-> gcur + 1, dev |-> d, stream |-> s,
                              seq |-> IF s = "status" THEN nseq[d] ELSE 0, good |-> g, fake |-> FALSE]}
       /\ nseq' = IF s = "status" THEN [nseq EXCEPT ![d] = @ + 1] ELSE nseq
  /\ gcur' = gcur + 1
  /\ UNCHANGED <<usedF, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

\* A fake row (never of the client's own device: those are ignored like
\* its genuine ones). "same" takes the seq of the newest genuine status of
\* the device, or the seq its next one will take.
Fake ==
  \E f \in Fakes \ usedF :
    LET t == FakeTab[f]
        seqs == CASE t.kind = "seq"  -> {BigSeq + t.n}
                  [] t.kind = "same" -> {q \in {nseq[t.dev] - 1, nseq[t.dev]} : q >= 0}
                  [] OTHER           -> {nseq[t.dev]}
        cur == CASE t.kind = "cursor" -> BigCursor + t.n
                 [] t.kind = "bad"    -> CursorMax + 1 + t.n
                 [] OTHER             -> gcur + 1
    IN /\ \E q \in seqs :
            rows' = rows \cup {[cursor |-> cur, dev |-> t.dev, stream |-> "status", seq |-> q,
                                good |-> FALSE, fake |-> TRUE]}
       /\ usedF' = usedF \cup {f}
       /\ gcur' = IF t.kind \in {"cursor", "bad"} THEN gcur ELSE gcur + 1
  /\ UNCHANGED <<nseq, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

\* The attack stops: rows with a cursor out of range are no longer listed.
StopBad ==
  /\ \E r \in rows : OutOfRange(r.cursor)
  /\ rows' = {r \in rows : ~OutOfRange(r.cursor)}
  /\ UNCHANGED <<gcur, nseq, usedF, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

\* One listing page: rows after the cursor, the first Limit by cursor.
After(c) == {r \in rows : r.cursor > c}
PageOf(c) == {r \in After(c) : Cardinality({x \in After(c) : x.cursor < r.cursor}) < Limit}
MaxOf(S) == CHOOSE x \in S : \A y \in S : y <= x

\* The code refuses a page with a row out of range (malformed).
PageOk(c) == CheckCursor => \A r \in PageOf(c) : ~OutOfRange(r.cursor)
\* Before the fix: the server refuses a stored cursor out of range (400),
\* which ended the run before the rescan and the fetches.
MainRefused == ~CheckCursor /\ OutOfRange(cursor)
RunEnds == MainRefused \/ (~CheckCursor /\ OutOfRange(rcur))

---------------------------------------------------------------------------
\* Before review #7.

\* StatusSyncState.listed
ListOld ==
  /\ ~Fixed /\ ~MainRefused
  /\ After(cursor) # {} /\ PageOk(cursor)
  /\ LET items == PageOf(cursor)
         st(d) == {r.seq : r \in {x \in items : x.stream = "status" /\ x.dev = d /\ x.dev # Self}}
     IN /\ cursor' = MaxOf({r.cursor : r \in items} \cup {cursor})
        /\ latest' = [d \in Devs |-> IF st(d) # {} /\ MaxOf(st(d)) > latest[d] THEN MaxOf(st(d)) ELSE latest[d]]
  /\ UNCHANGED <<rows, gcur, nseq, usedF, auth, unres, cands, rcur, shown, okSeqs, neterr>>

TargetsOld == {d \in Devs : latest[d] > auth[d] /\ ~(unres[d].seq = latest[d] /\ unres[d].attempts >= MaxAttempts)}

\* StatusSyncState.succeeded / failed
Succeed(d, q) ==
  /\ auth' = [auth EXCEPT ![d] = IF q > @ THEN q ELSE @]
  /\ unres' = [unres EXCEPT ![d] = IF @.seq > q THEN @ ELSE NoUnres]
  /\ shown' = [shown EXCEPT ![d] = q]
  /\ okSeqs' = okSeqs \cup {<<d, q>>}
Fail(d, q) ==
  /\ unres' = [unres EXCEPT ![d] = [seq |-> q, attempts |-> IF @.seq = q THEN @.attempts + 1 ELSE 1]]
  /\ UNCHANGED <<auth, shown, okSeqs>>

FetchOld(d) ==
  /\ ~Fixed /\ ~RunEnds
  /\ d \in TargetsOld
  /\ LET q == latest[d]
         listed == {r \in rows : r.dev = d /\ r.stream = "status" /\ r.seq = q}
         r == CHOOSE x \in listed : TRUE
     IN \/ /\ neterr < MaxNetErr /\ neterr' = neterr + 1      \* network error: nothing changes
           /\ UNCHANGED <<auth, unres, shown, okSeqs>>
        \/ /\ IF r.good THEN Succeed(d, q) ELSE Fail(d, q)    \* the listed blob
           /\ UNCHANGED neterr
        \/ /\ Hostile /\ neterr < MaxNetErr                  \* another blob, or 404 (bounded)
           /\ neterr' = neterr + 1 /\ Fail(d, q)
  /\ UNCHANGED <<rows, gcur, nseq, usedF, cursor, latest, cands, rcur>>

---------------------------------------------------------------------------
\* The code (Fixed).

Exhausted(c) == c.fails >= MaxAttempts
Problem(d) == unres[d].seq > auth[d]

\* The code keeps candidates in a list sorted by seq, newest first; among
\* equal seqs a stable sort keeps the order in which they were added, and a
\* failed candidate is added again at the end. Since at most one row is
\* taken per page (Limit = 1), `late` (added after another candidate of the
\* same seq that is still kept) gives that order.
Before(a, b) == a.r.seq > b.r.seq \/ (a.r.seq = b.r.seq /\ ~a.late /\ b.late)
\* Candidate c is added at the end of the candidates S of its seq.
AddLast(S, c) ==
  {IF x.r.seq = c.r.seq THEN [x EXCEPT !.late = FALSE] ELSE x : x \in S}
    \cup {[c EXCEPT !.late = \E x \in S : x.r.seq = c.r.seq]}

\* StatusSyncState.trim: the first MaxCand candidates still to try and,
\* separately, the first MaxCand exhausted ones (so that a rescan does not
\* bring them back as new).
RECURSIVE Keep(_, _)
Keep(S, n) == IF Cardinality(S) <= n THEN S
              ELSE Keep(S \ {CHOOSE c \in S : \A x \in S : ~Before(c, x)}, n)
Trim(S) == Keep({c \in S : ~Exhausted(c)}, MaxCand) \cup Keep({c \in S : Exhausted(c)}, MaxCand)

\* StatusSyncState.take (at most one new row per device and page)
Take(items) ==
  [d \in Devs |->
     LET new == {r \in items : r.stream = "status" /\ r.dev = d /\ d # Self /\ r.seq > auth[d]
                                /\ ~\E c \in cands[d] : c.r = r}
     IN IF new = {} THEN cands[d]
        ELSE Trim(AddLast(cands[d], [r |-> CHOOSE r \in new : TRUE, fails |-> 0, rr |-> FALSE, late |-> FALSE]))]

\* StatusSyncState.listed (main walk)
ListFixed ==
  /\ Fixed /\ ~MainRefused
  /\ After(cursor) # {} /\ PageOk(cursor)
  /\ LET items == PageOf(cursor)
     IN /\ cursor' = MaxOf({r.cursor : r \in items} \cup {cursor})
        /\ cands' = Take(items)
  /\ UNCHANGED <<rows, gcur, nseq, usedF, latest, auth, unres, rcur, shown, okSeqs, neterr>>

\* StatusSyncState.rescanIfDue
StartRescan ==
  /\ Fixed /\ rcur = -1 /\ ~MainRefused
  /\ rcur' = 0
  /\ UNCHANGED <<rows, gcur, nseq, usedF, cursor, latest, auth, unres, cands, shown, okSeqs, neterr>>

\* StatusSyncState.rescanned: one page, or the end of the listing.
RescanStep ==
  /\ Fixed /\ rcur >= 0 /\ ~RunEnds
  /\ IF After(rcur) # {}
       THEN LET items == PageOf(rcur)
            IN /\ PageOk(rcur)
               /\ rcur' = MaxOf({r.cursor : r \in items})
               /\ cands' = Take(items)
               /\ UNCHANGED cursor
       ELSE /\ cursor' = rcur /\ rcur' = -1
            /\ UNCHANGED cands
  /\ UNCHANGED <<rows, gcur, nseq, usedF, latest, auth, unres, shown, okSeqs, neterr>>

Live(d) == {c \in cands[d] : c.r.seq > auth[d] /\ ~Exhausted(c)}
TargetsFixed == {d \in Devs : Live(d) # {}}
TargetOf(d) == CHOOSE c \in Live(d) : \A x \in Live(d) : ~Before(x, c)

SucceedFixed(d, q) ==
  /\ Succeed(d, q)
  /\ cands' = [cands EXCEPT ![d] = {x \in @ : x.r.seq > q /\ x.r.seq > auth[d]}]
\* The newest failure stays shown: an older one does not replace it. A
\* retried exhausted candidate keeps its count and is marked for this round.
FailFixed(d, c) ==
  LET n == IF c.fails < MaxAttempts THEN c.fails + 1 ELSE MaxAttempts IN
  /\ cands' = [cands EXCEPT ![d] = Trim(AddLast(@ \ {c}, [c EXCEPT !.fails = n, !.rr = Exhausted(c)]))]
  /\ unres' = [unres EXCEPT ![d] = IF Problem(d) /\ @.seq > c.r.seq THEN @ ELSE [seq |-> c.r.seq, attempts |-> n]]
  /\ UNCHANGED <<auth, shown, okSeqs>>

\* Fetching candidate c of device d.
FetchCand(d, c) ==
  \/ /\ neterr < MaxNetErr /\ neterr' = neterr + 1      \* network error: nothing changes
     /\ UNCHANGED <<auth, unres, shown, okSeqs, cands>>
  \/ /\ IF c.r.good THEN SucceedFixed(d, c.r.seq) ELSE FailFixed(d, c)  \* the listed blob
     /\ UNCHANGED neterr
  \/ /\ Hostile /\ neterr < MaxNetErr                  \* another blob, or 404 (bounded)
     /\ neterr' = neterr + 1 /\ FailFixed(d, c)

FetchFixed(d) ==
  /\ Fixed /\ ~RunEnds
  /\ d \in TargetsFixed
  /\ FetchCand(d, TargetOf(d))
  /\ UNCHANGED <<rows, gcur, nseq, usedF, cursor, latest, rcur>>

\* StatusSyncState.retries: an exhausted candidate not yet retried in this
\* round; when all were, a new round starts.
Due(d) == {c \in cands[d] : c.r.seq > auth[d] /\ Exhausted(c) /\ ~c.rr}
RetryFetch(d) ==
  /\ Fixed /\ RetryExhausted /\ ~RunEnds
  /\ IF Due(d) # {}
       THEN /\ \E c \in Due(d) : FetchCand(d, c)
            /\ UNCHANGED <<rows, gcur, nseq, usedF, cursor, latest, rcur>>
       ELSE /\ \E c \in cands[d] : c.rr
            /\ cands' = [cands EXCEPT ![d] = {[x EXCEPT !.rr = FALSE] : x \in @}]
            /\ UNCHANGED <<rows, gcur, nseq, usedF, cursor, latest, auth, unres, rcur, shown, okSeqs, neterr>>

---------------------------------------------------------------------------

List == ListOld \/ ListFixed
Fetch(d) == FetchOld(d) \/ FetchFixed(d)
Rescan == StartRescan \/ RescanStep

Next == (\E d \in Devs : Upload(d) \/ Fetch(d) \/ RetryFetch(d)) \/ Fake \/ StopBad \/ List \/ Rescan

Spec == Init /\ [][Next]_vars /\ WF_vars(List) /\ WF_vars(StartRescan) /\ WF_vars(RescanStep) /\ WF_vars(StopBad)
        /\ \A d \in Devs : WF_vars(Fetch(d)) /\ WF_vars(RetryFetch(d))

-----------------------------------------------------------------------------
\* The authenticated seq only comes from a successful read of that very seq.
AuthOnlyOnSuccess == \A d \in Devs : auth[d] = -1 \/ <<d, auth[d]>> \in okSeqs
\* What is shown was read and authenticated.
ShownIsAuthenticated == \A d \in Devs : shown[d] = auth[d]
\* ... and it only increases.
AuthMonotone == [][\A d \in Devs : auth'[d] >= auth[d]]_vars

\* Failures are never silently treated as success: a listed candidate that
\* is not read is still to be fetched (the device has a target), or shown as
\* a problem.
NoSilentSkip ==
  \A d \in Devs :
    IF Fixed
      THEN (\E c \in cands[d] : c.r.seq > auth[d]) => (d \in TargetsFixed \/ Problem(d))
      ELSE latest[d] > auth[d] => (d \in TargetsOld \/ Problem(d))

\* The cursor (and a rescan's position) only moves to cursors of rows actually listed.
CursorListed ==
  /\ cursor = 0 \/ \E r \in rows : r.cursor = cursor
  /\ rcur \in {-1, 0} \/ \E r \in rows : r.cursor = rcur

\* The stored cursor and rescan position can always be sent back as `after` (review R4).
CursorValid == ~OutOfRange(cursor) /\ ~OutOfRange(rcur)

\* Candidates are bounded per device.
CandsBounded == \A d \in Devs : Cardinality(cands[d]) <= 2 * MaxCand

\* Liveness: once the faults are over, the newest genuine status of every
\* device is eventually read, unless its uploader broke it (then it is shown
\* as a problem). (Reviews #7, R4, R5.)
Newest(d) == {r \in Genuine : r.dev = d /\ r.stream = "status" /\
                              \A x \in Genuine : (x.dev = d /\ x.stream = "status") => x.seq <= r.seq}
Settled(d) == \A n \in Newest(d) : auth[d] = n.seq \/ (~n.good /\ Problem(d))
StatusLive == \A d \in Devs \ {Self} : <>[]Settled(d)
=============================================================================
