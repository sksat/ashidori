------------------------------ MODULE Invites ------------------------------
(***************************************************************************)
(* POST /v1/accounts with an invite (server/src/v1.ts route;               *)
(* server/src/accounts.ts recoverAccount, createAccount): the first device *)
(* of a person consumes an admin's invite and creates the user, under the  *)
(* username chosen on the device, and itself. Idempotent per               *)
(* client-generated token.                                                 *)
(*                                                                         *)
(* One request, each step atomic in D1:                                    *)
(*   Recover   the token's device is the first device of the user this     *)
(*             invite created -> 200, before every gate.                   *)
(*   Batch     one transaction (Atomic = TRUE, the code): consume the      *)
(*             invite if usable (unused, not revoked, not expired by D1's  *)
(*             clock), INSERT the user, INSERT its device. A token or a    *)
(*             username that exists makes an INSERT fail and the whole     *)
(*             batch roll back (UNIQUE); an unusable invite inserts 0 rows.*)
(*   Recheck   after the rollback or the 0 rows: the token's account, if  *)
(*             there is one now (a concurrent copy made it), else 401      *)
(*             (invite) or 409 (username).                                 *)
(* Without an invite (open registration, Inv = 0) the batch is the same    *)
(* without the consumption; MAX_USERS and REGISTRATION are Accounts.tla's. *)
(*                                                                         *)
(* Scenario: tokens 1 and 2 race for invite 1 with different usernames;    *)
(* tokens 1 and 3 race for username 1 with different invites. With         *)
(* WithOpen, token 4 registers without an invite and races token 2 for     *)
(* username 2. Token 1 resends its request (ReqsPerToken copies, also      *)
(* concurrently). The admin may revoke invite 1 while unused, and it may   *)
(* expire, at any time.                                                    *)
(*                                                                         *)
(* Mutation: Atomic = FALSE consumes the invite in one transaction and     *)
(* creates the user and the device in a later one: a username conflict     *)
(* (or a crash) in between leaves a used invite without a user, and a      *)
(* concurrent copy is refused although its account is created later.      *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS ReqsPerToken, WithOpen, CanRevoke, CanExpire, Atomic

Tokens == IF WithOpen THEN {1, 2, 3, 4} ELSE {1, 2, 3}
Invites == {1, 2}
\* The invite (0: none) and the username each token's request carries.
Inv(t) == CASE t = 1 -> 1 [] t = 2 -> 1 [] t = 3 -> 2 [] t = 4 -> 0
Name(t) == CASE t = 1 -> 1 [] t = 2 -> 2 [] t = 3 -> 1 [] t = 4 -> 2
\* Token 1's request is sent ReqsPerToken times (its copies race each
\* other); the others once (their copies would only repeat token 1's races).
Reqs == ({1} \X (1..ReqsPerToken)) \cup ((Tokens \ {1}) \X {1})

VARIABLES
  users,    \* set of [id, name, inv]
  devices,  \* set of [id, tok, user]
  used,     \* used[i]: the user the invite created, 0 if unused
  revoked,  \* revoked[i]
  expired,  \* expired[i], by D1's clock
  req       \* req[r] = [pc, res, dev, why, known]

vars == <<users, devices, used, revoked, expired, req>>

Init ==
  /\ users = {}
  /\ devices = {}
  /\ used = [i \in Invites |-> 0]
  /\ revoked = [i \in Invites |-> FALSE]
  /\ expired = [i \in Invites |-> FALSE]
  /\ req = [r \in Reqs |-> [pc |-> "idle", res |-> "none", dev |-> 0, why |-> "none", known |-> FALSE]]

Tok(r) == r[1]
NewId(r) == 10 * r[1] + r[2]
TokDev(t) == {d \in devices : d.tok = t}
TheDev(t) == CHOOSE d \in TokDev(t) : TRUE
UserOf(d) == CHOOSE u \in users : u.id = d.user
Usable(i) == used[i] = 0 /\ ~revoked[i] /\ ~expired[i]
NameTaken(n) == \E u \in users : u.name = n
\* No invite (open registration), or a usable one. (An IF: TLC would split a disjunction.)
InviteOk(t) == IF Inv(t) = 0 THEN TRUE ELSE Usable(Inv(t))
\* accountOf: the token's device is the first device of a user created by
\* the same kind of request (this invite, or none).
Recoverable(t) == TokDev(t) # {} /\ UserOf(TheDev(t)).inv = Inv(t)
Earlier(t) == IF Recoverable(t) THEN [res |-> "ok", dev |-> TheDev(t).id] ELSE [res |-> "inuse", dev |-> 0]

Finish(r, out) == req' = [req EXCEPT ![r].pc = "done", ![r].res = out.res, ![r].dev = out.dev,
                                     ![r].known = TokDev(Tok(r)) # {}]
Goto(r, pc) == req' = [req EXCEPT ![r].pc = pc]
Refuse(r, why) == req' = [req EXCEPT ![r].pc = "recheck", ![r].why = why]
Keep == UNCHANGED <<users, devices, used, revoked, expired>>

\* Creates the user and its device; consumes the invite (if any) for it.
Create(r) ==
  /\ users' = users \cup {[id |-> NewId(r), name |-> Name(Tok(r)), inv |-> Inv(Tok(r))]}
  /\ devices' = devices \cup {[id |-> NewId(r), tok |-> Tok(r), user |-> NewId(r)]}

\* Copies of one token's request go out in order (the client resends).
Send(r) ==
  /\ req[r].pc = "idle"
  /\ \A k \in 1..(r[2] - 1) : req[<<r[1], k>>].pc # "idle"
  /\ Goto(r, "sent") /\ Keep

SRecoverHit(r) ==
  /\ req[r].pc = "sent" /\ Recoverable(Tok(r))
  /\ Finish(r, Earlier(Tok(r))) /\ Keep
SRecoverMiss(r) ==
  /\ req[r].pc = "sent" /\ ~Recoverable(Tok(r))
  /\ Goto(r, "batch") /\ Keep

\* ---- Atomic (the code): one batch ----
SBatchToken(r) ==
  /\ Atomic /\ req[r].pc = "batch" /\ TokDev(Tok(r)) # {}
  /\ Refuse(r, "inuse") /\ Keep
SBatchInvalid(r) ==
  /\ Atomic /\ req[r].pc = "batch" /\ TokDev(Tok(r)) = {}
  /\ Inv(Tok(r)) # 0 /\ ~Usable(Inv(Tok(r)))
  /\ Refuse(r, "invalid") /\ Keep
SBatchTaken(r) ==
  /\ Atomic /\ req[r].pc = "batch" /\ TokDev(Tok(r)) = {}
  /\ InviteOk(Tok(r)) /\ NameTaken(Name(Tok(r)))
  /\ Refuse(r, "taken") /\ Keep
SBatchCreate(r) ==
  /\ Atomic /\ req[r].pc = "batch" /\ TokDev(Tok(r)) = {}
  /\ InviteOk(Tok(r)) /\ ~NameTaken(Name(Tok(r)))
  /\ used' = IF Inv(Tok(r)) = 0 THEN used ELSE [used EXCEPT ![Inv(Tok(r))] = NewId(r)]
  /\ Create(r)
  /\ Finish(r, [res |-> "created", dev |-> NewId(r)])
  /\ UNCHANGED <<revoked, expired>>

\* ---- Not atomic (mutation): consume, then create in a later transaction ----
SConsume(r) ==
  /\ ~Atomic /\ req[r].pc = "batch"
  /\ IF TokDev(Tok(r)) # {} THEN Refuse(r, "inuse") /\ Keep
     ELSE IF Inv(Tok(r)) = 0 THEN Goto(r, "create") /\ Keep
     ELSE IF Usable(Inv(Tok(r)))
       THEN /\ used' = [used EXCEPT ![Inv(Tok(r))] = NewId(r)]
            /\ Goto(r, "create")
            /\ UNCHANGED <<users, devices, revoked, expired>>
       ELSE Refuse(r, "invalid") /\ Keep
SCreate(r) ==
  /\ ~Atomic /\ req[r].pc = "create"
  /\ IF TokDev(Tok(r)) # {} THEN Refuse(r, "inuse") /\ Keep
     ELSE IF NameTaken(Name(Tok(r))) THEN Refuse(r, "taken") /\ Keep
     ELSE /\ Create(r)
          /\ Finish(r, [res |-> "created", dev |-> NewId(r)])
          /\ UNCHANGED <<used, revoked, expired>>

\* After the rollback or the 0 rows: the token's account, else the refusal.
SRecheckKnown(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(Tok(r)) # {}
  /\ Finish(r, Earlier(Tok(r))) /\ Keep
SRecheckRefused(r) ==
  /\ req[r].pc = "recheck" /\ TokDev(Tok(r)) = {}
  /\ Finish(r, [res |-> req[r].why, dev |-> 0]) /\ Keep

\* The admin revokes an unused invite (revokeInvite: WHERE used_by_user IS NULL).
Revoke(i) ==
  /\ CanRevoke /\ used[i] = 0 /\ ~revoked[i]
  /\ revoked' = [revoked EXCEPT ![i] = TRUE]
  /\ UNCHANGED <<users, devices, used, expired, req>>
Expire(i) ==
  /\ CanExpire /\ ~expired[i]
  /\ expired' = [expired EXCEPT ![i] = TRUE]
  /\ UNCHANGED <<users, devices, used, revoked, req>>

Server(r) ==
  \/ SRecoverHit(r) \/ SRecoverMiss(r)
  \/ SBatchToken(r) \/ SBatchInvalid(r) \/ SBatchTaken(r) \/ SBatchCreate(r)
  \/ SConsume(r) \/ SCreate(r)
  \/ SRecheckKnown(r) \/ SRecheckRefused(r)

\* Only invite 1, which tokens 1 and 2 race for, is revoked or expires.
Next == (\E r \in Reqs : Send(r) \/ Server(r)) \/ Revoke(1) \/ Expire(1)

Spec == Init /\ [][Next]_vars /\ \A r \in Reqs : WF_vars(Server(r))

-----------------------------------------------------------------------------
Ok(r) == req[r].pc = "done" /\ req[r].res \in {"ok", "created"}
Final(r) == req[r].pc = "done"

OneDevicePerToken == \A a, b \in devices : a.tok = b.tok => a = b
EveryUserHasItsDevice == \A u \in users : \E d \in devices : d.user = u.id
UsernameUnique == \A a, b \in users : a.name = b.name => a = b

\* One invite yields at most one user, and a user made with an invite is the one it records.
OneUserPerInvite ==
  /\ \A i \in Invites : Cardinality({u \in users : u.inv = i}) <= 1
  /\ \A u \in users : u.inv # 0 => used[u.inv] = u.id

\* Atomicity: an invite is never used without its user (and device).
ConsumedHasUser == \A i \in Invites : used[i] # 0 => \E u \in users : u.id = used[i]

\* A revoked invite is never used.
RevokedUnused == \A i \in Invites : revoked[i] => used[i] = 0

\* Retries of one token agree: once its account exists, every final answer
\* is that account; two successful copies name the same device; and no copy
\* is refused for good (401, 409) while another copy gets the account.
RetriesAgree ==
  /\ \A s \in Reqs : (Final(s) /\ req[s].known) => (Ok(s) /\ req[s].dev = TheDev(Tok(s)).id)
  /\ \A r, s \in Reqs : (Tok(r) = Tok(s) /\ Ok(r) /\ Ok(s)) => req[s].dev = req[r].dev
  /\ \A r, s \in Reqs : (Tok(r) = Tok(s) /\ Ok(r) /\ Final(s)) => Ok(s)

AllAnswered == \A r \in Reqs : (req[r].pc = "sent") ~> (req[r].pc = "done")
=============================================================================
