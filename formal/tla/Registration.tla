---------------------------- MODULE Registration ----------------------------
(***************************************************************************)
(* POST /v1/devices/register, server side (server/src/v1.ts route,         *)
(* server/src/devices.ts recoverRegistration, registerWithCode). The       *)
(* client's storage, cancel and unpairing are in RegistrationClient.tla.   *)
(*                                                                         *)
(* One request, each step atomic in D1:                                    *)
(*   Recover   recoverRegistration: the token's device consumed this very  *)
(*             code and is not revoked -> 200, before every gate.          *)
(*   Gate      the edge limiter, the D1 rate limit and the limit variables *)
(*             (an environment that may refuse with 429/503, bounded).     *)
(*   Start     earlier() inside registerWithCode: a device with this token *)
(*             (that did not consume this code) -> 401.                    *)
(*   Batch     one transaction: consume the code if usable (unused,        *)
(*             unexpired by D1's clock in this statement, user active,     *)
(*             issuer not revoked, user below the device limit), insert    *)
(*             the device. A token that already exists makes the INSERT    *)
(*             fail: the batch rolls back (UNIQUE).                        *)
(*   Recheck   after the rollback or a 0-row consume: earlier() again.     *)
(*   FullCheck "too many devices" (409) only to a holder of a usable code, *)
(*             else 401.                                                   *)
(*                                                                         *)
(* Two codes of one user (both issued by device Issuer), a device the user *)
(* registered by another path (OtherTok), honest requests with their token *)
(* and code A (resent any number of times, also concurrently), and an      *)
(* attacker that sends its own token with either code. The issuer may be   *)
(* revoked and a code may expire. Disabling the user is not a separate    *)
(* step: like revoking the issuer, it makes every code unusable.           *)
(*                                                                         *)
(* Mutations (each expected to break one property):                        *)
(*   RecheckOnZero = FALSE   no earlier() after a 0-row consume (N3)       *)
(*   RecoverFirst = FALSE    the gates run before the recovery             *)
(*   ExpiryAtBatch = FALSE   the expiry is judged at a time the Worker     *)
(*                           read before the batch (review 6, item 7)      *)
(*   AtomicBatch = FALSE     the UNIQUE failure does not roll back the     *)
(*                           consume                                       *)
(* Not modelled: deletion of used codes a day after expiry, revocation of  *)
(* the new device (both make a retry fail by design), the 7-day deletion  *)
(* of unused self-registered users.                                        *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS Codes,          \* e.g. {"A", "B"}
          HonestTokens,   \* tokens of honest clients; they use code "A"
          AttackerToken,
          OtherTok,       \* token of a device registered by another path (0: none)
          MaxHonestReqs, MaxAttackerReqs, MaxDevices, MaxGateRefusals,
          RecheckOnZero, RecoverFirst, ExpiryAtBatch, AtomicBatch

Issuer == 0          \* the device that issued both codes (token 0)
OtherId == 50
NoTok == 99
HonestCode == "A"
Reqs == 1..(MaxHonestReqs + MaxAttackerReqs)
HonestReq(r) == r <= MaxHonestReqs

VARIABLES
  devices,   \* set of [id, tok, code] (all of the one user; code "-": not by a code)
  revoked,   \* revoked device ids
  used,      \* code -> device id that consumed it (0: unused)
  expired,   \* code -> expired by D1's clock
  req,      \* req[r] = [pc, tok, code, res, dev, rec, exp]
  refusals,  \* gate refusals so far
  bad        \* ghost: a code consumed while not usable, or consumed without its device

vars == <<devices, revoked, used, expired, req, refusals, bad>>
env == <<revoked, expired>>

Init ==
  /\ devices = {[id |-> Issuer, tok |-> 0, code |-> "-"]}
                 \cup (IF OtherTok # 0 THEN {[id |-> OtherId, tok |-> OtherTok, code |-> "-"]} ELSE {})
  /\ revoked = {} /\ used = [c \in Codes |-> 0]
  /\ expired = [c \in Codes |-> FALSE]
  /\ req = [r \in Reqs |-> [pc |-> "idle", tok |-> NoTok, code |-> "-", res |-> "none", dev |-> 0,
                            rec |-> FALSE, exp |-> FALSE]]
  /\ refusals = 0 /\ bad = FALSE

TokDev(t) == {d \in devices : d.tok = t}
TheDev(t) == CHOOSE d \in TokDev(t) : TRUE

\* The token's device consumed this code and is not revoked.
Recovered(t, c) == TokDev(t) # {} /\ used[c] = TheDev(t).id /\ TheDev(t).id \notin revoked

\* earlier(): the result for a token that has a device.
Earlier(t, c) == IF Recovered(t, c) THEN [res |-> "ok", dev |-> TheDev(t).id] ELSE [res |-> "invalid", dev |-> 0]

\* CODE_USABLE, with the expiry by D1's clock now, or (mutation) as read before.
UsableBy(r) ==
  LET c == req[r].code IN
  /\ used[c] = 0 /\Issuer \notin revoked
  /\ ~(IF ExpiryAtBatch THEN expired[c] ELSE req[r].exp)
TrulyUsable(c) == used[c] = 0 /\ ~expired[c] /\Issuer \notin revoked
Full == Cardinality(devices) >= MaxDevices

Finish(r, out) == req' = [req EXCEPT ![r].pc = "done", ![r].res = out.res, ![r].dev = out.dev]
Goto(r, pc) == req' = [req EXCEPT ![r].pc = pc]
ServerOnly == UNCHANGED <<devices, used, bad>> /\ UNCHANGED env

Send(r) ==
  /\ req[r].pc = "idle"
  /\ \E t \in (IF HonestReq(r) THEN HonestTokens ELSE {AttackerToken}),
        c \in (IF HonestReq(r) THEN {HonestCode} ELSE Codes) :
       req' = [req EXCEPT ![r] = [@ EXCEPT !.pc = "sent", !.tok = t, !.code = c]]
  /\ UNCHANGED <<devices, used, bad, refusals>> /\ UNCHANGED env

\* The route: recovery first (it changes nothing), then the gates. `rec`
\* records whether the request was recoverable when the server took it up.
SRecoverHit(r) ==
  /\ RecoverFirst /\ req[r].pc = "sent" /\ Recovered(req[r].tok, req[r].code)
  /\ req' = [req EXCEPT ![r].pc = "done", ![r].res = "ok", ![r].dev = TheDev(req[r].tok).id, ![r].rec = TRUE]
  /\ ServerOnly /\ UNCHANGED refusals
SRecoverMiss(r) ==
  /\ req[r].pc = "sent" /\ (RecoverFirst => ~Recovered(req[r].tok, req[r].code))
  /\ req' = [req EXCEPT ![r].pc = "gate", ![r].rec = Recovered(req[r].tok, req[r].code)]
  /\ ServerOnly /\ UNCHANGED refusals

SGateRefuse(r) ==
  /\ req[r].pc = "gate" /\ refusals < MaxGateRefusals
  /\ refusals' = refusals + 1
  /\ Finish(r, [res |-> "retry", dev |-> 0])
  /\ ServerOnly
SGatePass(r) ==
  /\ req[r].pc = "gate"
  /\ Goto(r, "start")
  /\ ServerOnly /\ UNCHANGED refusals

\* registerWithCode: earlier(), then the Worker's Date.now() (`exp`: whether
\* the code had expired by then; used only by the mutation).
SStartKnown(r) ==
  /\ req[r].pc = "start" /\ TokDev(req[r].tok) # {}
  /\ Finish(r, Earlier(req[r].tok, req[r].code))
  /\ ServerOnly /\ UNCHANGED refusals
SStartNew(r) ==
  /\ req[r].pc = "start" /\ TokDev(req[r].tok) = {}
  /\ req' = [req EXCEPT ![r].pc = "batch", ![r].exp = expired[req[r].code]]
  /\ ServerOnly /\ UNCHANGED refusals

SBatchCreate(r) ==
  /\ req[r].pc = "batch" /\ UsableBy(r) /\ ~Full /\ TokDev(req[r].tok) = {}
  /\ LET newId == 100 + r IN
       /\ used' = [used EXCEPT ![req[r].code] = newId]
       /\ devices' = devices \cup {[id |-> newId, tok |-> req[r].tok, code |-> req[r].code]}
       /\ bad' = (bad \/ ~TrulyUsable(req[r].code))
       /\ Finish(r, [res |-> "created", dev |-> newId])
  /\ UNCHANGED env /\ UNCHANGED refusals
\* UNIQUE(token_hash) fails: the whole batch rolls back.
SBatchUnique(r) ==
  /\ req[r].pc = "batch" /\ UsableBy(r) /\ ~Full /\ TokDev(req[r].tok) # {}
  /\ Goto(r, "recheck")
  /\ IF AtomicBatch THEN UNCHANGED <<used, bad>>
     ELSE /\ used' = [used EXCEPT ![req[r].code] = 100 + r]   \* consumed for a device never inserted
          /\ bad' = TRUE
  /\ UNCHANGED <<devices, refusals>> /\ UNCHANGED env
\* 0 rows consumed, 0 rows inserted, no error.
SBatchZero(r) ==
  /\ req[r].pc = "batch" /\ ~(UsableBy(r) /\ ~Full)
  /\ IF RecheckOnZero THEN Goto(r, "recheck") ELSE Goto(r, "fullcheck")
  /\ ServerOnly /\ UNCHANGED refusals

SRecheckKnown(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(req[r].tok) # {}
  /\ Finish(r, Earlier(req[r].tok, req[r].code))
  /\ ServerOnly /\ UNCHANGED refusals
SRecheckNew(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(req[r].tok) = {}
  /\ Goto(r, "fullcheck")
  /\ ServerOnly /\ UNCHANGED refusals

\* "too many devices" only to a holder of a code that is usable now.
SFullCheckFull(r) ==
  /\ req[r].pc = "fullcheck" /\ TrulyUsable(req[r].code) /\ Full
  /\ Finish(r, [res |-> "full", dev |-> 0])
  /\ ServerOnly /\ UNCHANGED refusals
SFullCheckInvalid(r) ==
  /\ req[r].pc = "fullcheck" /\ ~(TrulyUsable(req[r].code) /\ Full)
  /\ Finish(r, [res |-> "invalid", dev |-> 0])
  /\ ServerOnly /\ UNCHANGED refusals

RevokeIssuer ==
  /\ Issuer \notin revoked /\ revoked' = revoked \cup {Issuer}
  /\ UNCHANGED <<devices, used, expired, req, refusals, bad>>
Expire(c) ==
  /\ ~expired[c] /\ expired' = [expired EXCEPT ![c] = TRUE]
  /\ UNCHANGED <<devices, revoked, used, req, refusals, bad>>

Server(r) ==
  \/ SRecoverHit(r) \/ SRecoverMiss(r) \/ SGateRefuse(r) \/ SGatePass(r)
  \/ SStartKnown(r) \/ SStartNew(r)
  \/ SBatchCreate(r) \/ SBatchUnique(r) \/ SBatchZero(r)
  \/ SRecheckKnown(r) \/ SRecheckNew(r) \/ SFullCheckFull(r) \/ SFullCheckInvalid(r)

Next ==
  \/ \E r \in Reqs : Send(r) \/ Server(r)
  \/ RevokeIssuer \/ \E c \in Codes : Expire(c)

Spec == Init /\ [][Next]_vars /\ \A r \in Reqs : WF_vars(Server(r))

-----------------------------------------------------------------------------
Ok(r) == req[r].pc = "done" /\ req[r].res \in {"ok", "created"}
\* A final answer: anything but a gate's 429/503 (which the client retries).
Final(r) == req[r].pc = "done" /\ req[r].res # "retry"

OneDevicePerToken == \A a, b \in devices : a.tok = b.tok => a = b

\* A code is consumed exactly by the device created with it, and only while
\* it was usable by D1's clock.
CodeIffDevice ==
  /\ \A c \in Codes : used[c] # 0 => \E d \in devices : d.id = used[c] /\ d.code = c
  /\ \A d \in devices : d.code # "-" => used[d.code] = d.id
  /\ ~bad

\* Requests with the same token and code agree: once one got the device,
\* every other final answer is that device.
RetriesAgree ==
  \A r, s \in Reqs :
    (req[r].tok = req[s].tok /\ req[r].code = req[s].code /\ Ok(r) /\ Final(s))
      => (Ok(s) /\ req[s].dev = req[r].dev)

\* A device id is only returned to the holder of its token.
TokenBound == \A r \in Reqs : Ok(r) => \E d \in devices : d.id = req[r].dev /\ d.tok = req[r].tok

\* A request that was recoverable when the server took it up is never
\* refused by a gate.
RecoverUngated == \A r \in Reqs : (req[r].pc = "done" /\ req[r].res = "retry") => ~req[r].rec

\* Liveness: every request gets an answer.
Responsive == \A r \in Reqs : (req[r].pc = "sent") ~> (req[r].pc = "done")
=============================================================================
