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
(* Failures are unresolved, retried up to 3 times, and shown.              *)
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
(* liveness below rests on candidates and rescans alone.                   *)
(*                                                                         *)
(* Fixed = FALSE models the design before the fix: one candidate per       *)
(* device, replaced only by a higher seq, and no rescan.                   *)
(*                                                                         *)
(* Honest server: rows are immutable, every listed row has its blob, and a *)
(* blob is bad only if its uploader made it so (`good` = FALSE). Network   *)
(* errors are bounded.                                                     *)
(* Hostile = TRUE: it may serve other blobs or 404 (bounded). FakeKinds:   *)
(* it may list one fake row of each kind, with a huge seq or a huge        *)
(* cursor. The fake rows stay listed.                                      *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS Devs, Self, MaxRows, Limit, MaxNetErr,
          Hostile,    \* the server may serve other blobs or 404 (bounded)
          FakeKinds,  \* subset of {"seq", "cursor"}: the fake rows it may list
          BigSeq, BigCursor,
          Fixed,      \* TRUE: several candidates and rescans (the code)
          MaxCand     \* candidates kept per device (Fixed)

MaxAttempts == 3
NoUnres == [seq |-> -1, attempts |-> 0]

VARIABLES
  rows,      \* listed rows [cursor, dev, stream, seq, good, fake, kind]
  gcur,      \* cursor of the newest genuine row
  nseq,      \* next status seq per device
  cursor, auth, unres,  \* client (StatusSyncState)
  latest,    \* client before the fix: the one candidate per device (seq)
  cands,     \* client (Fixed): candidates per device, [r: row, fails]
  rcur,      \* client (Fixed): rescan position, -1 when none runs
  shown,     \* client: seq of the status shown per device (-1: none)
  okSeqs,    \* ghost: <<dev, seq>> fetched as the listed, good blob
  neterr

vars == <<rows, gcur, nseq, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

Init ==
  /\ rows = {} /\ gcur = 0 /\ nseq = [d \in Devs |-> 0]
  /\ cursor = 0
  /\ latest = [d \in Devs |-> -1] /\ auth = [d \in Devs |-> -1] /\ unres = [d \in Devs |-> NoUnres]
  /\ cands = [d \in Devs |-> {}] /\ rcur = -1
  /\ shown = [d \in Devs |-> -1] /\ okSeqs = {} /\ neterr = 0

Genuine == {r \in rows : ~r.fake}

\* A device uploads a status (good or broken) or a batch of another stream.
Upload(d) ==
  /\ Cardinality(Genuine) < MaxRows
  /\ \E s \in {"status", "other"}, g \in BOOLEAN :
       /\ (s = "other" => g)
       /\ rows' = rows \cup {[cursor |-> gcur + 1, dev |-> d, stream |-> s,
                              seq |-> IF s = "status" THEN nseq[d] ELSE 0, good |-> g, fake |-> FALSE,
                              kind |-> "none"]}
       /\ nseq' = IF s = "status" THEN [nseq EXCEPT ![d] = @ + 1] ELSE nseq
  /\ gcur' = gcur + 1
  /\ UNCHANGED <<cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

\* Hostile only: a fake row, with a seq above every real one or a cursor
\* above every real one; at most one of each kind. (A fake row of the
\* client's own device is ignored like its genuine ones.)
Fake ==
  /\ Hostile
  /\ \E d \in Devs \ {Self}, big \in FakeKinds :
       /\ ~\E r \in rows : r.fake /\ r.kind = big
       /\ rows' = rows \cup {[cursor |-> IF big = "cursor" THEN BigCursor ELSE gcur + 1, dev |-> d,
                              stream |-> "status", seq |-> IF big = "seq" THEN BigSeq ELSE nseq[d],
                              good |-> FALSE, fake |-> TRUE, kind |-> big]}
       /\ gcur' = IF big = "cursor" THEN gcur ELSE gcur + 1
  /\ UNCHANGED <<nseq, cursor, latest, auth, unres, cands, rcur, shown, okSeqs, neterr>>

\* One listing page: rows after the cursor, the first Limit by cursor.
After(c) == {r \in rows : r.cursor > c}
PageOf(c) == {r \in After(c) : Cardinality({x \in After(c) : x.cursor < r.cursor}) < Limit}
MaxOf(S) == CHOOSE x \in S : \A y \in S : y <= x

---------------------------------------------------------------------------
\* Before the fix.

\* StatusSyncState.listed
ListOld ==
  /\ ~Fixed
  /\ After(cursor) # {}
  /\ LET items == PageOf(cursor)
         st(d) == {r.seq : r \in {x \in items : x.stream = "status" /\ x.dev = d /\ x.dev # Self}}
     IN /\ cursor' = MaxOf({r.cursor : r \in items} \cup {cursor})
        /\ latest' = [d \in Devs |-> IF st(d) # {} /\ MaxOf(st(d)) > latest[d] THEN MaxOf(st(d)) ELSE latest[d]]
  /\ UNCHANGED <<rows, gcur, nseq, auth, unres, cands, rcur, shown, okSeqs, neterr>>

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
  /\ ~Fixed
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
  /\ UNCHANGED <<rows, gcur, nseq, cursor, latest, cands, rcur>>

---------------------------------------------------------------------------
\* The code (Fixed).

Exhausted(c) == c.fails >= MaxAttempts
Problem(d) == unres[d].seq > auth[d]

\* StatusSyncState.trim: the newest MaxCand candidates still to try and,
\* separately, the newest MaxCand exhausted ones (so that a rescan does not
\* bring them back as new).
RECURSIVE Keep(_, _)
Keep(S, n) == IF Cardinality(S) <= n THEN S
              ELSE Keep(S \ {CHOOSE c \in S : \A x \in S : c.r.seq <= x.r.seq}, n)
Trim(S) == Keep({c \in S : ~Exhausted(c)}, MaxCand) \cup Keep({c \in S : Exhausted(c)}, MaxCand)

\* StatusSyncState.take
Take(items) ==
  [d \in Devs |->
     LET new == {r \in items : r.stream = "status" /\ r.dev = d /\ d # Self /\ r.seq > auth[d]
                                /\ ~\E c \in cands[d] : c.r = r}
     IN Trim(cands[d] \cup {[r |-> r, fails |-> 0] : r \in new})]

\* StatusSyncState.listed (main walk)
ListFixed ==
  /\ Fixed
  /\ After(cursor) # {}
  /\ LET items == PageOf(cursor)
     IN /\ cursor' = MaxOf({r.cursor : r \in items} \cup {cursor})
        /\ cands' = Take(items)
  /\ UNCHANGED <<rows, gcur, nseq, latest, auth, unres, rcur, shown, okSeqs, neterr>>

\* StatusSyncState.rescanIfDue
StartRescan ==
  /\ Fixed /\ rcur = -1
  /\ rcur' = 0
  /\ UNCHANGED <<rows, gcur, nseq, cursor, latest, auth, unres, cands, shown, okSeqs, neterr>>

\* StatusSyncState.rescanned: one page, or the end of the listing.
RescanStep ==
  /\ Fixed /\ rcur >= 0
  /\ IF After(rcur) # {}
       THEN LET items == PageOf(rcur)
            IN /\ rcur' = MaxOf({r.cursor : r \in items})
               /\ cands' = Take(items)
               /\ UNCHANGED cursor
       ELSE /\ cursor' = rcur /\ rcur' = -1
            /\ UNCHANGED cands
  /\ UNCHANGED <<rows, gcur, nseq, latest, auth, unres, shown, okSeqs, neterr>>

Live(d) == {c \in cands[d] : c.r.seq > auth[d] /\ ~Exhausted(c)}
TargetsFixed == {d \in Devs : Live(d) # {}}
TargetOf(d) == CHOOSE c \in Live(d) : \A x \in Live(d) : x.r.seq <= c.r.seq

SucceedFixed(d, q) ==
  /\ Succeed(d, q)
  /\ cands' = [cands EXCEPT ![d] = {x \in @ : x.r.seq > q /\ x.r.seq > auth[d]}]
\* The newest failure stays shown: an older one does not replace it.
FailFixed(d, c) ==
  LET n == c.fails + 1 IN
  /\ cands' = [cands EXCEPT ![d] = Trim((@ \ {c}) \cup {[r |-> c.r, fails |-> n]})]
  /\ unres' = [unres EXCEPT ![d] = IF Problem(d) /\ @.seq > c.r.seq THEN @ ELSE [seq |-> c.r.seq, attempts |-> n]]
  /\ UNCHANGED <<auth, shown, okSeqs>>

FetchFixed(d) ==
  /\ Fixed
  /\ d \in TargetsFixed
  /\ LET c == TargetOf(d)
     IN \/ /\ neterr < MaxNetErr /\ neterr' = neterr + 1      \* network error: nothing changes
           /\ UNCHANGED <<auth, unres, shown, okSeqs, cands>>
        \/ /\ IF c.r.good THEN SucceedFixed(d, c.r.seq) ELSE FailFixed(d, c)  \* the listed blob
           /\ UNCHANGED neterr
        \/ /\ Hostile /\ neterr < MaxNetErr                  \* another blob, or 404 (bounded)
           /\ neterr' = neterr + 1 /\ FailFixed(d, c)
  /\ UNCHANGED <<rows, gcur, nseq, cursor, latest, rcur>>

---------------------------------------------------------------------------

List == ListOld \/ ListFixed
Fetch(d) == FetchOld(d) \/ FetchFixed(d)
Rescan == StartRescan \/ RescanStep

Next == (\E d \in Devs : Upload(d) \/ Fetch(d)) \/ Fake \/ List \/ Rescan

Spec == Init /\ [][Next]_vars /\ WF_vars(List) /\ WF_vars(StartRescan) /\ WF_vars(RescanStep)
        /\ \A d \in Devs : WF_vars(Fetch(d))

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

\* Candidates are bounded per device.
CandsBounded == \A d \in Devs : Cardinality(cands[d]) <= 2 * MaxCand

\* Liveness: the newest genuine status of every device is eventually read,
\* unless its uploader broke it (then it is shown as a problem).
Newest(d) == {r \in Genuine : r.dev = d /\ r.stream = "status" /\
                              \A x \in Genuine : (x.dev = d /\ x.stream = "status") => x.seq <= r.seq}
Settled(d) == \A n \in Newest(d) : auth[d] = n.seq \/ (~n.good /\ Problem(d))
StatusLive == \A d \in Devs \ {Self} : <>[]Settled(d)
=============================================================================
