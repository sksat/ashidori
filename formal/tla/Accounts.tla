------------------------------ MODULE Accounts ------------------------------
(***************************************************************************)
(* POST /v1/accounts (server/src/v1.ts route; server/src/accounts.ts       *)
(* recoverAccount, createAccount): a device creates its user, idempotent   *)
(* per client-generated token.                                             *)
(*                                                                         *)
(* One request, each step atomic in D1:                                    *)
(*   Recover   recoverAccount: the token's device is the first device of a *)
(*             self-registered user (not revoked) -> 200, before every     *)
(*             gate.                                                       *)
(*   Closed    REGISTRATION is not "open" -> 403 "registration closed".    *)
(*   Gate      the edge limiter, the D1 rate limit, MAX_USERS and limit    *)
(*             variables: may refuse with 429/503 (bounded).               *)
(*   Start     earlier(): a token of any other device -> 409 "token in     *)
(*             use".                                                       *)
(*   Batch     one transaction: INSERT the user if user_count < MaxUsers,  *)
(*             INSERT its first device. A token that already exists makes *)
(*             the device INSERT fail: the batch rolls back (UNIQUE).      *)
(*             No user inserted (at MaxUsers): 0 rows, no error.           *)
(*   Recheck   after the rollback or the 0 rows: earlier() again, else     *)
(*             403 "registration full".                                    *)
(*                                                                         *)
(* Clients: tokens of their own, each request resent any number of times   *)
(* (bounded), also concurrently. OtherTok is the token of a device that    *)
(* is not a first device of a self-registered user (a paired device of an  *)
(* admin-created user, who counts towards MaxUsers). The operator may      *)
(* close registration at any time.                                         *)
(*                                                                         *)
(* Mutations: RecheckOnZero = FALSE (N3: a 0-row insert answers "full"     *)
(* without the recheck), RecoverFirst = FALSE (the mode and the gates come *)
(* before the recovery), RecheckClosed = FALSE (the code before the fix of *)
(* a finding of this model: it answered 403 to a copy that passed the      *)
(* recovery just before a concurrent copy created the account, if the      *)
(* operator closed registration in between; now the recovery is looked up *)
(* again before the 403). Not modelled: the 7-day deletion of unused       *)
(* accounts (a retry after it creates a new account, by design),           *)
(* revocation, admin-created users other than OtherTok's.                  *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Tokens, ReqsPerToken, MaxUsers, OtherTok, MaxGateRefusals, CanClose,
          RecheckOnZero, RecoverFirst, RecheckClosed

AllToks == Tokens \cup (IF OtherTok # 0 THEN {OtherTok} ELSE {})
Reqs == AllToks \X (1..ReqsPerToken)
OtherUser == 99

VARIABLES
  users,     \* set of user ids
  devices,   \* set of [id, tok, user, first]: first = the first device of a self-registered user
  open,      \* REGISTRATION = "open"
  req,       \* req[r] = [pc, res, dev, rec]
  refusals

vars == <<users, devices, open, req, refusals>>

Init ==
  /\ users = IF OtherTok # 0 THEN {OtherUser} ELSE {}
  /\ devices = IF OtherTok # 0 THEN {[id |-> 50, tok |-> OtherTok, user |-> OtherUser, first |-> FALSE]} ELSE {}
  /\ open = TRUE
  /\ req = [r \in Reqs |-> [pc |-> "idle", res |-> "none", dev |-> 0, rec |-> FALSE, known |-> FALSE]]
  /\ refusals = 0

Tok(r) == r[1]
NewId(r) == 10 * r[1] + r[2]
TokDev(t) == {d \in devices : d.tok = t}
TheDev(t) == CHOOSE d \in TokDev(t) : TRUE
Recoverable(t) == TokDev(t) # {} /\ TheDev(t).first
\* earlier() / accountOf: 200 for the first device of a self-registered user, else 409.
Earlier(t) == IF Recoverable(t) THEN [res |-> "ok", dev |-> TheDev(t).id] ELSE [res |-> "inuse", dev |-> 0]

\* `known`: the token's account existed when the answer was given.
Finish(r, out) == req' = [req EXCEPT ![r].pc = "done", ![r].res = out.res, ![r].dev = out.dev,
                                     ![r].known = TokDev(Tok(r)) # {}]
Goto(r, pc) == req' = [req EXCEPT ![r].pc = pc]
Same == UNCHANGED <<users, devices, open, refusals>>

\* Copies of one token's request go out in order (the client resends).
Send(r) ==
  /\ req[r].pc = "idle"
  /\ \A k \in 1..(r[2] - 1) : req[<<r[1], k>>].pc # "idle"
  /\ Goto(r, "sent") /\ Same

SRecoverHit(r) ==
  /\ RecoverFirst /\ req[r].pc = "sent" /\ Recoverable(Tok(r))
  /\ req' = [req EXCEPT ![r].pc = "done", ![r].res = "ok", ![r].dev = TheDev(Tok(r)).id, ![r].rec = TRUE,
                        ![r].known = TRUE]
  /\ Same
SRecoverMiss(r) ==
  /\ req[r].pc = "sent" /\ (RecoverFirst => ~Recoverable(Tok(r)))
  /\ req' = [req EXCEPT ![r].pc = "gate", ![r].rec = Recoverable(Tok(r))]
  /\ Same
\* RecheckClosed (the code; FALSE before the fix of a finding of this
\* model): the recovery is looked up again before the 403, since a
\* concurrent copy of the request may have created the account after the
\* first lookup.
SClosed(r) ==
  /\ req[r].pc = "gate" /\ ~open
  /\ IF RecheckClosed /\ Recoverable(Tok(r))
       THEN Finish(r, Earlier(Tok(r)))
       ELSE Finish(r, [res |-> "closed", dev |-> 0])
  /\ Same
SGateRefuse(r) ==
  /\ req[r].pc = "gate" /\ open /\ refusals < MaxGateRefusals
  /\ refusals' = refusals + 1
  /\ Finish(r, [res |-> "retry", dev |-> 0])
  /\ UNCHANGED <<users, devices, open>>
SGatePass(r) ==
  /\ req[r].pc = "gate" /\ open
  /\ Goto(r, "start") /\ Same

SStartKnown(r) ==
  /\ req[r].pc = "start" /\ TokDev(Tok(r)) # {}
  /\ Finish(r, Earlier(Tok(r))) /\ Same
SStartNew(r) ==
  /\ req[r].pc = "start" /\ TokDev(Tok(r)) = {}
  /\ Goto(r, "batch") /\ Same

SBatchCreate(r) ==
  /\ req[r].pc = "batch" /\ Cardinality(users) < MaxUsers /\ TokDev(Tok(r)) = {}
  /\ users' = users \cup {NewId(r)}
  /\ devices' = devices \cup {[id |-> NewId(r), tok |-> Tok(r), user |-> NewId(r), first |-> TRUE]}
  /\ Finish(r, [res |-> "created", dev |-> NewId(r)])
  /\ UNCHANGED <<open, refusals>>
\* UNIQUE(token_hash) fails: the user INSERT rolls back with it.
SBatchUnique(r) ==
  /\ req[r].pc = "batch" /\ Cardinality(users) < MaxUsers /\ TokDev(Tok(r)) # {}
  /\ Goto(r, "recheck") /\ Same
SBatchFull(r) ==
  /\ req[r].pc = "batch" /\ Cardinality(users) >= MaxUsers
  /\ IF RecheckOnZero THEN Goto(r, "recheck") ELSE Finish(r, [res |-> "full", dev |-> 0])
  /\ Same

SRecheckKnown(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(Tok(r)) # {}
  /\ Finish(r, Earlier(Tok(r))) /\ Same
SRecheckFull(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(Tok(r)) = {}
  /\ Finish(r, [res |-> "full", dev |-> 0]) /\ Same

Close ==
  /\ CanClose /\ open /\ open' = FALSE
  /\ UNCHANGED <<users, devices, req, refusals>>

Server(r) ==
  \/ SRecoverHit(r) \/ SRecoverMiss(r) \/ SClosed(r) \/ SGateRefuse(r) \/ SGatePass(r)
  \/ SStartKnown(r) \/ SStartNew(r) \/ SBatchCreate(r) \/ SBatchUnique(r) \/ SBatchFull(r)
  \/ SRecheckKnown(r) \/ SRecheckFull(r)

Next == (\E r \in Reqs : Send(r) \/ Server(r)) \/ Close

Spec == Init /\ [][Next]_vars /\ \A r \in Reqs : WF_vars(Server(r))

-----------------------------------------------------------------------------
Ok(r) == req[r].pc = "done" /\ req[r].res \in {"ok", "created"}
\* A final answer: anything but a gate's 429/503 (which the client retries).
Final(r) == req[r].pc = "done" /\ req[r].res # "retry"

OneDevicePerToken == \A a, b \in devices : a.tok = b.tok => a = b
UsersUnderMax == Cardinality(users) <= MaxUsers
EveryUserHasItsDevice == \A u \in users : \E d \in devices : d.user = u

\* Retries of one token agree: every final answer given once the token's
\* account exists is that account (no "full", no "closed"). (A copy refused
\* with 403 "closed" before a concurrent copy created the account is not
\* covered: the mode is read by the Worker, not in the batch. The client
\* therefore sends a token it sent before again, for a bounded time, before
\* it takes a 403 as final; a copy after the commit gets the account.)
RetriesAgree ==
  /\ \A s \in Reqs : (Final(s) /\ req[s].known /\ Tok(s) # OtherTok)
                       => (Ok(s) /\ req[s].dev = TheDev(Tok(s)).id)
  /\ \A r, s \in Reqs : (Tok(r) = Tok(s) /\ Ok(r) /\ Ok(s)) => req[s].dev = req[r].dev

\* A request that was recoverable when the server took it up is neither
\* refused by a gate nor by the closed registration.
RecoverUngated ==
  \A r \in Reqs : (req[r].pc = "done" /\ req[r].res \in {"retry", "closed"}) => ~req[r].rec

AllAnswered == \A r \in Reqs : (req[r].pc = "sent") ~> (req[r].pc = "done")
=============================================================================
