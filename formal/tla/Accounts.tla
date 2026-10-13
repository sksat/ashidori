------------------------------ MODULE Accounts ------------------------------
(***************************************************************************)
(* POST /v1/accounts (server/src/accounts.ts createAccount): a device      *)
(* creates its user, idempotent per client-generated token.               *)
(*                                                                         *)
(* Server steps of one request, each atomic in D1:                         *)
(*   Start    earlier(): the token's device is the first device of a       *)
(*            self-registered user and not revoked -> 200, any other       *)
(*            device -> 409 "token in use".                                *)
(*   Batch    one transaction: INSERT the user if user_count < MaxUsers,   *)
(*            INSERT the device for that user. A token that already exists *)
(*            makes the INSERT fail: the batch rolls back -> Recheck.      *)
(*            If the user was not inserted (server full), 0 rows, no       *)
(*            error -> Recheck (fixed) or 403 "full" (before the fix).     *)
(*   Recheck  earlier() again, else 403 "full" / 409.                      *)
(*                                                                         *)
(* Clients: two devices with their own tokens, each resending its request *)
(* any number of times (bounded), also concurrently.                       *)
(* Not modelled: the 7-day deletion of unused accounts (a retry after it   *)
(* creates a new account, by design), admin-created users.                 *)
(***************************************************************************)
EXTENDS Naturals, FiniteSets

CONSTANTS Tokens, ReqsPerToken, MaxUsers, RecheckOnZero

Reqs == Tokens \X (1..ReqsPerToken)

VARIABLES
  users,     \* set of user ids (all self-registered)
  devices,   \* set of [id, tok, user]
  req        \* req[r] = [pc, res, dev]

vars == <<users, devices, req>>

Init ==
  /\ users = {} /\ devices = {}
  /\ req = [r \in Reqs |-> [pc |-> "idle", res |-> "none", dev |-> 0]]

Tok(r) == r[1]
NewId(r) == 10 * r[1] + r[2]
TokDev(t) == {d \in devices : d.tok = t}
\* Every user here was created together with its first device, so a token's
\* device is always a first device.
Earlier(t) == [res |-> "ok", dev |-> (CHOOSE d \in TokDev(t) : TRUE).id]

Finish(r, out) == req' = [req EXCEPT ![r] = [pc |-> "done", res |-> out.res, dev |-> out.dev]]

Send(r) ==
  /\ req[r].pc = "idle"
  \* Copies of one token's request go out in order (the client resends).
  /\ \A k \in 1..(r[2] - 1) : req[<<r[1], k>>].pc # "idle"
  /\ req' = [req EXCEPT ![r].pc = "sent"]
  /\ UNCHANGED <<users, devices>>

SStart(r) ==
  /\ req[r].pc = "sent"
  /\ IF TokDev(Tok(r)) # {} THEN Finish(r, Earlier(Tok(r))) ELSE req' = [req EXCEPT ![r].pc = "batch"]
  /\ UNCHANGED <<users, devices>>

SBatch(r) ==
  /\ req[r].pc = "batch"
  /\ IF Cardinality(users) < MaxUsers
       THEN IF TokDev(Tok(r)) # {}
              THEN req' = [req EXCEPT ![r].pc = "recheck"] /\ UNCHANGED <<users, devices>>  \* rollback
              ELSE /\ users' = users \cup {NewId(r)}
                   /\ devices' = devices \cup {[id |-> NewId(r), tok |-> Tok(r), user |-> NewId(r)]}
                   /\ Finish(r, [res |-> "created", dev |-> NewId(r)])
       ELSE /\ IF RecheckOnZero THEN req' = [req EXCEPT ![r].pc = "recheck"]
                                ELSE Finish(r, [res |-> "full", dev |-> 0])
            /\ UNCHANGED <<users, devices>>

SRecheck(r) ==
  /\ req[r].pc = "recheck"
  /\ Finish(r, IF TokDev(Tok(r)) # {} THEN Earlier(Tok(r))
               ELSE [res |-> "full", dev |-> 0])
  /\ UNCHANGED <<users, devices>>

Next == \E r \in Reqs : Send(r) \/ SStart(r) \/ SBatch(r) \/ SRecheck(r)

Spec == Init /\ [][Next]_vars /\ \A r \in Reqs : WF_vars(SStart(r) \/ SBatch(r) \/ SRecheck(r))

-----------------------------------------------------------------------------
Ok(r) == req[r].pc = "done" /\ req[r].res \in {"ok", "created"}

OneDevicePerToken == \A a, b \in devices : a.tok = b.tok => a = b
UsersUnderMax == Cardinality(users) <= MaxUsers
EveryUserHasItsDevice == \A u \in users : \E d \in devices : d.user = u

\* Retries of one token agree: once one request got the device, every
\* completed request with that token got the same device (no "full").
RetriesAgree ==
  \A r, s \in Reqs :
    (Tok(r) = Tok(s) /\ Ok(r) /\ req[s].pc = "done") => (Ok(s) /\ req[s].dev = req[r].dev)

AllAnswered == \A r \in Reqs : (req[r].pc = "sent") ~> (req[r].pc = "done")
=============================================================================
