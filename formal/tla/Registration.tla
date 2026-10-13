---------------------------- MODULE Registration ----------------------------
(***************************************************************************)
(* POST /v1/devices/register (server/src/devices.ts registerWithCode) and  *)
(* the client's registration request (app Registration).                   *)
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
(* Environment: an attacker with its own token tries the same code; the    *)
(* issuer device is revoked; the code expires; the user is disabled.       *)
(* Not modelled: deletion of used codes after a day and revocation of the  *)
(* new device (both make a retry fail by design).                          *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS HonestTokens,  \* tokens the client may generate, in order (1, 2, ...)
          AttackerToken,
          MaxHonestReqs, MaxAttackerReqs, MaxDevices,
          RecheckOnZero, SyncPersist

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
  req,         \* req[r] = [pc, tok, res, dev]
  memTok, diskTok, nextTok, paired, rejected, crashes,
  seen,        \* results the client has processed
  bad          \* ghost: a code consumed while not usable

vars == <<devices, revoked, usedBy, expired, disabled, req, memTok, diskTok, nextTok, paired, rejected,
          crashes, seen, bad>>

Init ==
  /\ devices = {[id |-> Issuer, tok |-> 0]}
  /\ revoked = {} /\ usedBy = 0 /\ expired = FALSE /\ disabled = FALSE
  /\ req = [r \in Reqs |-> [pc |-> "idle", tok |-> NoTok, res |-> "none", dev |-> 0]]
  /\ memTok = NoTok /\ diskTok = NoTok /\ nextTok = 1
  /\ paired = 0 /\ rejected = FALSE /\ crashes = 0
  /\ seen = {} /\ bad = FALSE

TokDev(t) == {d \in devices : d.tok = t}

\* earlier(): the result for a token that already has a device.
Earlier(t) ==
  LET d == CHOOSE d \in TokDev(t) : TRUE
  IN IF usedBy = d.id /\ d.id \notin revoked THEN [res |-> "ok", dev |-> d.id] ELSE [res |-> "invalid", dev |-> 0]

Usable == usedBy = 0 /\ ~expired /\ ~disabled /\ Issuer \notin revoked
Full == Cardinality(devices) >= MaxDevices

Finish(r, out) == req' = [req EXCEPT ![r] = [@ EXCEPT !.pc = "done", !.res = out.res, !.dev = out.dev]]

ReqVarsExcept == <<memTok, diskTok, nextTok, paired, rejected, crashes, seen>>

SStart(r) ==
  /\ req[r].pc = "sent"
  /\ IF TokDev(req[r].tok) # {}
       THEN Finish(r, Earlier(req[r].tok))
       ELSE req' = [req EXCEPT ![r].pc = "batch"]
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED ReqVarsExcept

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
  /\ UNCHANGED <<revoked, expired, disabled>> /\ UNCHANGED ReqVarsExcept

SRecheck(r) ==
  /\ req[r].pc = "recheck"
  /\ IF TokDev(req[r].tok) # {}
       THEN Finish(r, Earlier(req[r].tok))
       ELSE req' = [req EXCEPT ![r].pc = "fullcheck"]
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED ReqVarsExcept

SFullCheck(r) ==
  /\ req[r].pc = "fullcheck"
  /\ Finish(r, [res |-> IF Usable /\ Full THEN "full" ELSE "invalid", dev |-> 0])
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED ReqVarsExcept

\* ---- client ----

Begin ==
  /\ paired = 0 /\ ~rejected /\ memTok = NoTok /\ diskTok = NoTok
  /\ nextTok \in HonestTokens
  /\ memTok' = nextTok /\ nextTok' = nextTok + 1
  /\ diskTok' = IF SyncPersist THEN nextTok ELSE NoTok
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, req, paired, rejected, crashes, seen, bad>>

\* apply(): the write reaches the disk some time later.
DiskWrite ==
  /\ ~SyncPersist /\ memTok # NoTok /\ diskTok = NoTok
  /\ diskTok' = memTok
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, req, memTok, nextTok, paired, rejected, crashes, seen, bad>>

\* After a restart the pending request is read back from disk.
Restore ==
  /\ memTok = NoTok /\ diskTok # NoTok /\ paired = 0 /\ ~rejected
  /\ memTok' = diskTok
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, req, diskTok, nextTok, paired, rejected, crashes, seen, bad>>

Send ==
  /\ memTok # NoTok /\ paired = 0 /\ ~rejected
  /\ \E r \in Reqs :
       /\ HonestReq(r) /\ req[r].pc = "idle"
       /\ req' = [req EXCEPT ![r] = [pc |-> "sent", tok |-> memTok, res |-> "none", dev |-> 0]]
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, memTok, diskTok, nextTok, paired, rejected, crashes, seen, bad>>

\* The client reads a response (any copy of its current request).
Receive ==
  /\ paired = 0 /\ ~rejected /\ memTok # NoTok
  /\ \E r \in Reqs :
       /\ HonestReq(r) /\ req[r].pc = "done" /\ r \notin seen /\ req[r].tok = memTok
       /\ seen' = seen \cup {r}
       /\ IF req[r].res \in {"ok", "created"}
            THEN /\ paired' = req[r].dev /\ diskTok' = NoTok /\ UNCHANGED rejected
            ELSE /\ rejected' = TRUE /\ diskTok' = NoTok /\ UNCHANGED paired
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, req, memTok, nextTok, crashes, bad>>

Crash ==
  /\ crashes < 1 /\ memTok # NoTok
  /\ memTok' = NoTok /\ crashes' = crashes + 1
  /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, req, diskTok, nextTok, paired, rejected, seen, bad>>

\* ---- environment ----

AttackerSend ==
  \E r \in Reqs :
    /\ ~HonestReq(r) /\ req[r].pc = "idle"
    /\ req' = [req EXCEPT ![r] = [pc |-> "sent", tok |-> AttackerToken, res |-> "none", dev |-> 0]]
    /\ UNCHANGED <<devices, revoked, usedBy, expired, disabled, bad>> /\ UNCHANGED ReqVarsExcept

RevokeIssuer ==
  /\ Issuer \notin revoked /\ revoked' = revoked \cup {Issuer}
  /\ UNCHANGED <<devices, usedBy, expired, disabled, req, bad>> /\ UNCHANGED ReqVarsExcept
Expire ==
  /\ ~expired /\ expired' = TRUE
  /\ UNCHANGED <<devices, revoked, usedBy, disabled, req, bad>> /\ UNCHANGED ReqVarsExcept
Disable ==
  /\ ~disabled /\ disabled' = TRUE
  /\ UNCHANGED <<devices, revoked, usedBy, expired, req, bad>> /\ UNCHANGED ReqVarsExcept

Server(r) == SStart(r) \/ SBatch(r) \/ SRecheck(r) \/ SFullCheck(r)

Next ==
  \/ \E r \in Reqs : Server(r)
  \/ Begin \/ DiskWrite \/ Restore \/ Send \/ Receive \/ Crash
  \/ AttackerSend \/ RevokeIssuer \/ Expire \/ Disable

Spec == Init /\ [][Next]_vars
          /\ \A r \in Reqs : WF_vars(Server(r))
          /\ WF_vars(Begin) /\ WF_vars(DiskWrite) /\ WF_vars(Restore) /\ WF_vars(Send) /\ WF_vars(Receive)

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
\* paired with it, or still holds the token on disk or in memory.
TokenRecoverable ==
  \A d \in devices :
    d.tok \in HonestTokens => (paired = d.id \/ diskTok = d.tok \/ memTok = d.tok)

\* Liveness: the client ends paired or definitely refused.
Settles == <>(paired # 0 \/ rejected)
=============================================================================
