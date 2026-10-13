---------------------------- MODULE Registration ----------------------------
(***************************************************************************)
(* POST /v1/devices/register (server/src/devices.ts registerWithCode) and  *)
(* the client's registration (app Registration, SyncConfig, DurablePrefs). *)
(*                                                                         *)
(* Server steps of one request, each atomic in D1:                         *)
(*   Start    earlier(): a device with this token? 200 if it consumed this *)
(*            code and is not revoked, else 401.                           *)
(*   Batch    one transaction: consume the code if usable (unused,         *)
(*            unexpired, user active, issuer not revoked, user below the   *)
(*            device limit), then insert the device for it. A token that   *)
(*            already exists makes the INSERT fail: the batch rolls back.  *)
(*   Recheck  after the rollback or (fixed) after a 0-row consume:         *)
(*            earlier() again, else 409 "too many devices" or 401.         *)
(* RecheckOnZero = FALSE is the code before the fix (sanity variant).      *)
(*                                                                         *)
(* Client: generates its token, persists it (commit, i.e. on disk before   *)
(* sending, when SyncPersist), then sends (token, code). It may resend the *)
(* same request at any time, also while an earlier copy is still being    *)
(* processed (lost response, timeout). A crash loses memory only.          *)
(* SyncPersist = FALSE models SharedPreferences.apply() (sanity variant).  *)
(*                                                                         *)
(* Each send belongs to an attempt (start or retry; one at a time). The    *)
(* user may cancel the attempt in flight, or unpair (reset), a bounded     *)
(* number of times. Taking in an answer is two steps, a check and the      *)
(* save of the pairing:                                                    *)
(*   CancelSafe = TRUE (the code): the check moves the attempt from        *)
(*     "live" to "saving" in one compare-and-set; a cancel moves it from   *)
(*     "live" to none. Exactly one wins; a cancel that finds it saving     *)
(*     reports that it came too late. Results carry the attempt id, and    *)
(*     the screen acts only on those of the latest attempt.                *)
(*   CancelSafe = FALSE (before the fix, review R2): the check reads the  *)
(*     current attempt under a lock that cancel does not take; a cancel    *)
(*     between the check and the save does not stop the save.              *)
(* Saving the pairing and the key may fail (bounded), after the platform   *)
(* already updated its in-memory copy (SharedPreferences.commit()):        *)
(*   PersistSafe = TRUE (the code): a value is readable only once it is on *)
(*     disk; a failed write is undone in memory.                           *)
(*   PersistSafe = FALSE (before the fix, review R3): the memory keeps the *)
(*     value that the disk does not have.                                  *)
(*                                                                         *)
(* Environment: an attacker with its own token tries the same code; the    *)
(* issuer device is revoked; the code expires; the user is disabled.       *)
(* Not modelled: deletion of used codes after a day and revocation of the  *)
(* new device (both make a retry fail by design).                          *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS HonestTokens,  \* tokens the client may generate, in order (1, 2, ...)
          AttackerToken,
          MaxHonestReqs, MaxAttackerReqs, MaxDevices,
          RecheckOnZero, SyncPersist,
          CancelSafe, PersistSafe,
          MaxCancels,    \* cancels and unpairings by the user
          MaxAttempts,   \* attempt ids (starts, retries and cancels use one each)
          MaxPersistFail \* failed saves of the pairing or the key

Code == "C"                    \* the code the user typed (issued by device Issuer)
Issuer == 0                    \* an existing device of the user, token 0
NoTok == 99
Reqs == 1..(MaxHonestReqs + MaxAttackerReqs)
HonestReq(r) == r <= MaxHonestReqs

VARIABLES
  devices,     \* set of [id, tok]  (all of the one user)
  revoked,     \* revoked device ids
  usedBy,      \* device id that consumed Code, or 0 (unused); Issuer id is 0 but never consumes
  expired, disabled,
  req,         \* req[r] = [pc, tok, res, dev, att]
  memTok, diskTok, nextTok, rejected,
  crashes,     \* faults so far: a crash or a lost response (at most one)
  seen,       \* results the client has processed
  bad,         \* ghost: a code consumed while not usable
  paired,      \* device id of the pairing as the app reads it (0: none)
  diskPaired,  \* ... as stored on disk
  memKey, diskKey, \* the master key as the app reads it / on disk
  cur,         \* attempt in flight: id (live), -id (being saved), 0 (none)
  att,         \* last attempt id used
  latest,      \* the screen's latest attempt (bumped by cancel and reset)
  recv,        \* request whose answer passed the check and is being saved (0: none)
  cancelled,   \* ghost: attempts invalidated by a cancel or reset that returned as such
  dropped,     \* ghost: tokens the user gave up (cancel, unpair)
  events,      \* "paired" results not yet seen by the screen (attempt ids)
  stale,       \* ghost: a cancelled attempt saved, or the screen acted on its result
  cancels, pfails

vars == <<devices, revoked, usedBy, expired, disabled, req, memTok, diskTok, nextTok, rejected,
          crashes, seen, bad, paired, diskPaired, memKey, diskKey, cur, att, latest, recv, cancelled,
          dropped, events, stale, cancels, pfails>>
client == <<memTok, diskTok, nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey,
            cur, att, latest, recv, cancelled, dropped, events, stale, cancels, pfails>>

Init ==
  /\ devices = {[id |-> Issuer, tok |-> 0]}
  /\ revoked = {} /\ usedBy = 0 /\ expired = FALSE /\ disabled = FALSE
  /\ req = [r \in Reqs |-> [pc |-> "idle", tok |-> NoTok, res |-> "none", dev |-> 0, att |-> 0]]
  /\ memTok = NoTok /\ diskTok = NoTok /\ nextTok = 1
  /\ rejected = FALSE /\ crashes = 0
  /\ seen = {} /\ bad = FALSE
  /\ paired = 0 /\ diskPaired = 0 /\ memKey = FALSE /\ diskKey = FALSE
  /\ cur = 0 /\ att = 0 /\ latest = 0 /\ recv = 0
  /\ cancelled = {} /\ dropped = {} /\ events = {} /\ stale = FALSE
  /\ cancels = 0 /\ pfails = 0

TokDev(t) == {d \in devices : d.tok = t}

\* earlier(): the result for a token that already has a device.
Earlier(t) ==
  LET d == CHOOSE d \in TokDev(t) : TRUE
  IN IF usedBy = d.id /\ d.id \notin revoked THEN [res |-> "ok", dev |-> d.id] ELSE [res |-> "invalid", dev |-> 0]

Usable == usedBy = 0 /\ ~expired /\ ~disabled /\ Issuer \notin revoked
Full == Cardinality(devices) >= MaxDevices

Finish(r, out) == req' = [req EXCEPT ![r] = [@ EXCEPT !.pc = "done", !.res = out.res, !.dev = out.dev]]

SStart(r) ==
  /\ req[r].pc = "sent"
  /\ IF TokDev(req[r].tok) # {}
       THEN Finish(r, Earlier(req[r].tok))
       ELSE req' = [req EXCEPT ![r].pc = "batch"]
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED client

SBatch(r) ==
  /\ req[r].pc = "batch"
  /\ LET newId == 100 + r IN
       IF Usable /\ ~Full
         THEN IF TokDev(req[r].tok) # {}
                THEN \* UNIQUE(token_hash) fails: the whole batch rolls back.
                     /\ req' = [req EXCEPT ![r].pc = "recheck"]
                     /\ UNCHANGED <<devices, usedBy, bad>>
                ELSE /\ usedBy' = newId
                     /\ devices' = devices \cup {[id |-> newId, tok |-> req[r].tok]}
                     /\ bad' = (bad \/ ~Usable)
                     /\ Finish(r, [res |-> "created", dev |-> newId])
         ELSE \* 0 rows consumed, 0 rows inserted, no error.
              /\ req' = [req EXCEPT ![r].pc = IF RecheckOnZero THEN "recheck" ELSE "fullcheck"]
              /\ UNCHANGED <<devices, usedBy, bad>>
  /\ UNCHANGED <<revoked, expired, disabled>> /\ UNCHANGED client

SRecheck(r) ==
  /\ req[r].pc = "recheck"
  /\ IF TokDev(req[r].tok) # {}
       THEN Finish(r, Earlier(req[r].tok))
       ELSE req' = [req EXCEPT ![r].pc = "fullcheck"]
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED client

SFullCheck(r) ==
  /\ req[r].pc = "fullcheck"
  /\ Finish(r, [res |-> IF Usable /\ Full THEN "full" ELSE "invalid", dev |-> 0])
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED client

\* ---- client ----

Server_ == <<devices, revoked, usedBy, expired, disabled, bad>>

\* A new attempt id; the screen's latest attempt becomes it.
NewAttempt == att < MaxAttempts /\ att' = att + 1 /\ latest' = att + 1

\* start(): a new token, stored before anything is sent.
Begin ==
  /\ paired = 0 /\ ~rejected /\ memTok = NoTok /\ diskTok = NoTok /\ cur = 0 /\ recv = 0
  /\ nextTok \in HonestTokens
  /\ NewAttempt /\ cur' = att + 1
  /\ memTok' = nextTok /\ nextTok' = nextTok + 1
  /\ diskTok' = IF SyncPersist THEN nextTok ELSE NoTok
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<rejected, crashes, seen, paired, diskPaired, memKey, diskKey, recv, cancelled, dropped, events,
                 stale, cancels, pfails>>

\* retry(): the stored request again, as a new attempt.
Retry ==
  /\ paired = 0 /\ ~rejected /\ memTok # NoTok /\ cur = 0 /\ recv = 0
  /\ NewAttempt /\ cur' = att + 1
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, diskTok, nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey, recv,
                 cancelled, dropped, events, stale, cancels, pfails>>

\* apply(): the write reaches the disk some time later.
DiskWrite ==
  /\ ~SyncPersist /\ memTok # NoTok /\ diskTok = NoTok
  /\ diskTok' = memTok
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey, cur, att, latest, recv,
                 cancelled, dropped, events, stale, cancels, pfails>>

\* After a restart the pending request is read back from disk.
Restore ==
  /\ memTok = NoTok /\ diskTok # NoTok /\ paired = 0 /\ ~rejected
  /\ memTok' = diskTok
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<diskTok, nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey, cur, att, latest, recv,
                 cancelled, dropped, events, stale, cancels, pfails>>

\* The request of the attempt in flight. (The code also resends within an
\* attempt after a network error; an earlier attempt's request that is still
\* being processed gives the same concurrency at the server.)
Send ==
  /\ cur > 0 /\ memTok # NoTok /\ paired = 0 /\ ~rejected
  /\ ~\E r \in Reqs : HonestReq(r) /\ req[r].att = cur
  /\ \E r \in Reqs :
       /\ HonestReq(r) /\ req[r].pc = "idle"
       /\ req' = [req EXCEPT ![r] = [pc |-> "sent", tok |-> memTok, res |-> "none", dev |-> 0, att |-> cur]]
  /\ UNCHANGED Server_ /\ UNCHANGED client

\* The attempt's request timed out (network error): the attempt ends, the
\* request stays stored for a retry, and the server may still process it.
Lost ==
  /\ cur > 0 /\ recv = 0 /\ crashes < 1
  /\ \E r \in Reqs : HonestReq(r) /\ req[r].att = cur /\ req[r].pc # "done"
  /\ cur' = 0 /\ crashes' = crashes + 1
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, diskTok, nextTok, rejected, seen, paired, diskPaired, memKey, diskKey, att, latest,
                 recv, cancelled, dropped, events, stale, cancels, pfails>>

\* finish(), step 1: the answer of request r is for the attempt in flight.
Check ==
  /\ cur > 0 /\ recv = 0
  /\ \E r \in Reqs :
       /\ HonestReq(r) /\ req[r].pc = "done" /\ r \notin seen /\ req[r].att = cur
       /\ seen' = seen \cup {r}
       /\ recv' = r
       /\ cur' = IF CancelSafe THEN -cur ELSE cur
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, diskTok, nextTok, rejected, crashes, paired, diskPaired, memKey, diskKey, att, latest,
                 cancelled, dropped, events, stale, cancels, pfails>>

\* finish(), step 2: save the pairing (it may fail) or the refusal, and end the attempt.
Save ==
  /\ recv # 0
  /\ LET r == recv
         a == req[r].att
         ok == req[r].res \in {"ok", "created"}
     IN /\ recv' = 0
        /\ cur' = IF cur \in {a, -a} THEN 0 ELSE cur
        /\ IF ok
             THEN \/ /\ paired' = req[r].dev /\ diskPaired' = req[r].dev /\ diskTok' = NoTok
                     /\ events' = events \cup {a}
                     /\ stale' = (stale \/ a \in cancelled)
                     /\ UNCHANGED <<rejected, pfails>>
                  \/ /\ pfails < MaxPersistFail /\ pfails' = pfails + 1
                     \* commit() failed: the memory keeps the value unless undone.
                     /\ paired' = IF PersistSafe THEN paired ELSE req[r].dev
                     /\ stale' = (stale \/ (~PersistSafe /\ a \in cancelled))
                     /\ UNCHANGED <<diskPaired, diskTok, events, rejected>>
             ELSE /\ rejected' = TRUE /\ diskTok' = NoTok
                  /\ UNCHANGED <<paired, diskPaired, events, stale, pfails>>
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, nextTok, crashes, seen, memKey, diskKey, att, latest, cancelled, dropped, cancels>>

\* The sync screen takes a "paired" result in: with a self-registered
\* account it goes on to the key at once. The code acts only on the latest
\* attempt's result.
Notify ==
  /\ \E a \in events :
       /\ events' = events \ {a}
       /\ stale' = (stale \/ ((~CancelSafe \/ a = latest) /\ a \in cancelled))
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, diskTok, nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey, cur, att,
                 latest, recv, cancelled, dropped, cancels, pfails>>

\* やめる. The code: only a live attempt is cancelled (one compare-and-set);
\* one being saved is too late. The stored request is given up.
Cancel ==
  /\ cancels < MaxCancels /\ paired = 0 /\ (cur # 0 \/ memTok # NoTok)
  /\ cancels' = cancels + 1
  /\ NewAttempt
  /\ IF CancelSafe /\ cur < 0
       THEN UNCHANGED <<cur, cancelled, memTok, diskTok, dropped>>
       ELSE /\ cur' = 0
            /\ cancelled' = cancelled \cup (IF cur > 0 THEN {cur} ELSE {})
            /\ dropped' = dropped \cup {memTok, diskTok}
            /\ memTok' = NoTok /\ diskTok' = NoTok
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<nextTok, rejected, crashes, seen, paired, diskPaired, memKey, diskKey, recv, events, stale, pfails>>

\* Unpairing (SyncConfig.forget). The code invalidates the attempt in
\* flight first, on the registration executor (so not while an answer is
\* being saved), then clears the pairing, the key and the request.
Unpair ==
  /\ cancels < MaxCancels /\ paired # 0 /\ (CancelSafe => cur >= 0)
  /\ cancels' = cancels + 1
  /\ NewAttempt
  /\ cur' = 0
  /\ cancelled' = cancelled \cup (IF cur > 0 THEN {cur} ELSE {})
  /\ dropped' = dropped \cup {memTok, diskTok} \cup {d.tok : d \in {x \in devices : x.id = paired}}
  /\ memTok' = NoTok /\ diskTok' = NoTok
  /\ paired' = 0 /\ diskPaired' = 0 /\ memKey' = FALSE /\ diskKey' = FALSE
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<nextTok, rejected, crashes, seen, recv, events, stale, pfails>>

\* The key step (confirmKey): only once paired, and only once.
SetKey ==
  /\ paired # 0 /\ ~memKey
  /\ \/ memKey' = TRUE /\ diskKey' = TRUE /\ UNCHANGED pfails
     \/ /\ pfails < MaxPersistFail /\ pfails' = pfails + 1
        /\ memKey' = ~PersistSafe /\ UNCHANGED diskKey
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<memTok, diskTok, nextTok, rejected, crashes, seen, paired, diskPaired, cur, att, latest, recv,
                 cancelled, dropped, events, stale, cancels>>

\* Process death: memory is lost and read back from disk.
Crash ==
  /\ crashes < 1
  /\ crashes' = crashes + 1
  /\ memTok' = NoTok /\ cur' = 0 /\ recv' = 0 /\ events' = {}
  /\ paired' = diskPaired /\ memKey' = diskKey
  /\ UNCHANGED Server_ /\ UNCHANGED req
  /\ UNCHANGED <<diskTok, nextTok, rejected, seen, diskPaired, diskKey, att, latest, cancelled, dropped, stale,
                 cancels, pfails>>

\* ---- environment ----

AttackerSend ==
  \E r \in Reqs :
    /\ ~HonestReq(r) /\ req[r].pc = "idle"
    /\ req' = [req EXCEPT ![r] = [pc |-> "sent", tok |-> AttackerToken, res |-> "none", dev |-> 0, att |-> 0]]
    /\ UNCHANGED Server_ /\ UNCHANGED client

RevokeIssuer ==
  /\ Issuer \notin revoked /\ revoked' = revoked \cup {Issuer}
  /\ UNCHANGED <<devices, usedBy, expired, disabled, req, bad>> /\ UNCHANGED client
Expire ==
  /\ ~expired /\ expired' = TRUE
  /\ UNCHANGED <<devices, revoked, usedBy, disabled, req, bad>> /\ UNCHANGED client
Disable ==
  /\ ~disabled /\ disabled' = TRUE
  /\ UNCHANGED <<devices, revoked, usedBy, expired, req, bad>> /\ UNCHANGED client

Server(r) == SStart(r) \/ SBatch(r) \/ SRecheck(r) \/ SFullCheck(r)

Next ==
  \/ \E r \in Reqs : Server(r)
  \/ Begin \/ Retry \/ DiskWrite \/ Restore \/ Send \/ Lost \/ Check \/ Save \/ Notify
  \/ Cancel \/ Unpair \/ SetKey \/ Crash
  \/ AttackerSend \/ RevokeIssuer \/ Expire \/ Disable

Spec == Init /\ [][Next]_vars
          /\ \A r \in Reqs : WF_vars(Server(r))
          /\ WF_vars(Begin) /\ WF_vars(Retry) /\ WF_vars(DiskWrite) /\ WF_vars(Restore) /\ WF_vars(Send)
          /\ WF_vars(Check) /\ WF_vars(Save)

-----------------------------------------------------------------------------
Ok(r) == req[r].pc = "done" /\ req[r].res \in {"ok", "created"}

\* At most one device per token.
OneDevicePerToken == \A a, b \in devices : a.tok = b.tok => a = b

\* The code is consumed iff a device was created with it, and only while usable.
CodeIffDevice ==
  /\ (usedBy # 0) <=> (\E d \in devices : d.id # Issuer)
  /\ usedBy # 0 => \E d \in devices : d.id = usedBy
  /\ ~bad

\* The same token + code gives the same device. Concurrent or repeated
\* requests with the client's token all succeed once one did (revocation of
\* the new device and code deletion are not modelled).
RetriesAgree ==
  \A r, s \in Reqs :
    (req[r].tok = req[s].tok /\ req[r].tok # NoTok /\ Ok(r) /\ req[s].pc = "done")
      => (Ok(s) /\ req[s].dev = req[r].dev)

\* A device is only returned to the holder of its token.
TokenBound == \A r \in Reqs : Ok(r) => \E d \in devices : d.id = req[r].dev /\ d.tok = req[r].tok

\* A device created for the client's token stays reachable: the client is
\* paired with it (on disk), or still holds the token on disk or in memory,
\* unless the user gave the token up (cancel, unpair).
TokenRecoverable ==
  \A d \in devices :
    d.tok \in HonestTokens =>
      (diskPaired = d.id \/ paired = d.id \/ diskTok = d.tok \/ memTok = d.tok \/ d.tok \in dropped)

\* A cancelled (or reset) attempt never changes the registration or the key,
\* and the screen never acts on its result (review R2).
CancelledNoEffect == ~stale

\* Ready (paired and keyed, as the screen and the sync jobs read it) implies
\* both are on disk (review R3).
ReadyPersisted == (paired # 0 /\ memKey) => (diskPaired = paired /\ diskKey)
\* ... and the pairing the app reads is the one on disk.
PairedPersisted == paired # 0 => diskPaired = paired

\* Liveness: the client ends paired, definitely refused, or with every
\* token given up by the user.
GaveUp == cur = 0 /\ memTok = NoTok /\ diskTok = NoTok /\ nextTok \notin HonestTokens
Settles == <>(paired # 0 \/ rejected \/ GaveUp)
=============================================================================
