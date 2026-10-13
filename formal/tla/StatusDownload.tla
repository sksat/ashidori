--------------------------- MODULE StatusDownload ---------------------------
(***************************************************************************)
(* Download of the other devices' status (docs/sync-protocol.md "Other     *)
(* devices' status"; core StatusSyncState, app PeerStatus.pull).           *)
(*                                                                         *)
(* Client state: the listing cursor (only a fetch position, moved only to  *)
(* cursors of listed rows), per device the newest listed status candidate  *)
(* (seq, and the listed hash), the authenticated seq (decrypted and parsed)*)
(* and an unresolved entry (seq, attempts).                                *)
(*                                                                         *)
(* A fetch of the target seq ends in one of:                               *)
(*   network error   nothing changes (retried next run)                    *)
(*   listed blob     opened: success iff the blob is good                  *)
(*   other blob      hash/size differ from the listing: failure            *)
(*   404             failure                                               *)
(* Failures are unresolved, retried up to 3 times, and shown.              *)
(*                                                                         *)
(* Honest server (StatusDownload.cfg): rows are immutable, every listed    *)
(* row has its blob, and a blob is bad only if its uploader made it so     *)
(* (`good` = FALSE). Network errors are bounded.                           *)
(* Hostile = TRUE: it may serve other blobs or 404 (bounded). FakeKinds:   *)
(* it may list one fake row with a huge seq and/or a huge cursor.         *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS Devs, Self, MaxRows, Limit, MaxNetErr,
          Hostile,    \* the server may serve other blobs or 404 (bounded)
          FakeKinds,  \* subset of {"seq", "cursor"}: the fake row it may list
          BigSeq, BigCursor

MaxAttempts == 3
NoUnres == [seq |-> -1, attempts |-> 0]

VARIABLES
  rows,      \* listed rows [cursor, dev, stream, seq, good, fake]
  gcur,      \* cursor of the newest genuine row
  nseq,      \* next status seq per device
  cursor, latest, auth, unres,  \* client (StatusSyncState)
  shown,     \* client: seq of the status shown per device (-1: none)
  okSeqs,    \* ghost: <<dev, seq>> fetched as the listed, good blob
  neterr

vars == <<rows, gcur, nseq, cursor, latest, auth, unres, shown, okSeqs, neterr>>

Init ==
  /\ rows = {} /\ gcur = 0 /\ nseq = [d \in Devs |-> 0]
  /\ cursor = 0
  /\ latest = [d \in Devs |-> -1] /\ auth = [d \in Devs |-> -1] /\ unres = [d \in Devs |-> NoUnres]
  /\ shown = [d \in Devs |-> -1] /\ okSeqs = {} /\ neterr = 0

Genuine == {r \in rows : ~r.fake}

\* A device uploads a status (good or broken) or a batch of another stream.
Upload(d) ==
  /\ Cardinality(Genuine) < MaxRows
  /\ \E s \in {"status", "other"}, g \in BOOLEAN :
       /\ (s = "other" => g)
       /\ rows' = rows \cup {[cursor |-> gcur + 1, dev |-> d, stream |-> s,
                              seq |-> IF s = "status" THEN nseq[d] ELSE 0, good |-> g, fake |-> FALSE]}
       /\ nseq' = IF s = "status" THEN [nseq EXCEPT ![d] = @ + 1] ELSE nseq
  /\ gcur' = gcur + 1
  /\ UNCHANGED <<cursor, latest, auth, unres, shown, okSeqs, neterr>>

\* Hostile only: one fake row, with a seq above every real one or a cursor
\* above every real one.
Fake ==
  /\ Hostile /\ \A r \in rows : ~r.fake
  /\ \E d \in Devs, big \in FakeKinds :
       rows' = rows \cup {[cursor |-> IF big = "cursor" THEN BigCursor ELSE gcur + 1, dev |-> d,
                           stream |-> "status", seq |-> IF big = "seq" THEN BigSeq ELSE nseq[d],
                           good |-> FALSE, fake |-> TRUE]}
       /\ gcur' = IF big = "cursor" THEN gcur ELSE gcur + 1
  /\ UNCHANGED <<nseq, cursor, latest, auth, unres, shown, okSeqs, neterr>>

\* One listing page: rows after the cursor, the first Limit by cursor.
After(c) == {r \in rows : r.cursor > c}
PageOf(c) == {r \in After(c) : Cardinality({x \in After(c) : x.cursor < r.cursor}) < Limit}
MaxOf(S) == CHOOSE x \in S : \A y \in S : y <= x

\* StatusSyncState.listed
List ==
  /\ After(cursor) # {}
  /\ LET items == PageOf(cursor)
         st(d) == {r.seq : r \in {x \in items : x.stream = "status" /\ x.dev = d /\ x.dev # Self}}
     IN /\ cursor' = MaxOf({r.cursor : r \in items} \cup {cursor})
        /\ latest' = [d \in Devs |-> IF st(d) # {} /\ MaxOf(st(d)) > latest[d] THEN MaxOf(st(d)) ELSE latest[d]]
  /\ UNCHANGED <<rows, gcur, nseq, auth, unres, shown, okSeqs, neterr>>

Targets == {d \in Devs : latest[d] > auth[d] /\ ~(unres[d].seq = latest[d] /\ unres[d].attempts >= MaxAttempts)}

\* StatusSyncState.succeeded / failed
Succeed(d, q) ==
  /\ auth' = [auth EXCEPT ![d] = IF q > @ THEN q ELSE @]
  /\ unres' = [unres EXCEPT ![d] = IF @.seq > q THEN @ ELSE NoUnres]
  /\ shown' = [shown EXCEPT ![d] = q]
  /\ okSeqs' = okSeqs \cup {<<d, q>>}
Fail(d, q) ==
  /\ unres' = [unres EXCEPT ![d] = [seq |-> q, attempts |-> IF @.seq = q THEN @.attempts + 1 ELSE 1]]
  /\ UNCHANGED <<auth, shown, okSeqs>>

Fetch(d) ==
  /\ d \in Targets
  /\ LET q == latest[d]
         listed == {r \in rows : r.dev = d /\ r.stream = "status" /\ r.seq = q}
         r == CHOOSE x \in listed : TRUE
     IN \/ /\ neterr < MaxNetErr /\ neterr' = neterr + 1      \* network error: nothing changes
           /\ UNCHANGED <<auth, unres, shown, okSeqs>>
        \/ /\ IF r.good THEN Succeed(d, q) ELSE Fail(d, q)    \* the listed blob
           /\ UNCHANGED neterr
        \/ /\ Hostile /\ neterr < MaxNetErr                  \* another blob, or 404 (bounded)
           /\ neterr' = neterr + 1 /\ Fail(d, q)
  /\ UNCHANGED <<rows, gcur, nseq, cursor, latest>>

Next == (\E d \in Devs : Upload(d) \/ Fetch(d)) \/ Fake \/ List

Spec == Init /\ [][Next]_vars /\ WF_vars(List) /\ \A d \in Devs : WF_vars(Fetch(d))

-----------------------------------------------------------------------------
\* The authenticated seq only comes from a successful read of that very seq.
AuthOnlyOnSuccess == \A d \in Devs : auth[d] = -1 \/ <<d, auth[d]>> \in okSeqs
\* What is shown was read and authenticated.
ShownIsAuthenticated == \A d \in Devs : shown[d] = auth[d]
\* ... and it only increases.
AuthMonotone == [][\A d \in Devs : auth'[d] >= auth[d]]_vars

\* Failures are never silently treated as success: a newest listed status
\* that is not read is still to be fetched, or shown as a problem.
Problem(d) == unres[d].seq > auth[d]
NoSilentSkip == \A d \in Devs : latest[d] > auth[d] => (d \in Targets \/ Problem(d))

\* The cursor only moves to cursors of rows actually listed.
CursorListed == cursor = 0 \/ \E r \in rows : r.cursor = cursor

\* Liveness: the newest genuine status of every device is eventually read,
\* unless its uploader broke it (then it is shown as a problem).
Newest(d) == {r \in Genuine : r.dev = d /\ r.stream = "status" /\
                              \A x \in Genuine : (x.dev = d /\ x.stream = "status") => x.seq <= r.seq}
Settled(d) == \A n \in Newest(d) : auth[d] = n.seq \/ (~n.good /\ Problem(d))
StatusLive == \A d \in Devs \ {Self} : <>[]Settled(d)
=============================================================================
