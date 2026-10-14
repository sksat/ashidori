------------------------- MODULE RegistrationClient -------------------------
(***************************************************************************)
(* The client side of registration and pairing: core RegistrationMachine,  *)
(* PairingStore and DurableMap as the app uses them (Sync.scala            *)
(* Registration, SyncConfig, SyncRun). The server is abstract: it is       *)
(* idempotent per token (a token that has a device gets that device), as   *)
(* Registration.tla checks for the real server.                            *)
(*                                                                         *)
(* Storage. Two DurableMaps: C ("sync": the registration, i.e. server URL  *)
(* and device id, and the unpairing mark) and S ("secure": the token, the  *)
(* master key, the pending request). A write is WriteBegin(next) ->        *)
(* DiskResult, and anything may read in between:                           *)
(*   new          disk = snap = next, nothing unknown                      *)
(*   old          the write failed; the file holds the old records         *)
(*   unreadable   the write failed and the file could not be read back:    *)
(*                it holds the old or the new records; the changed keys    *)
(*                are unknown (read as absent) until a later write         *)
(* A read returns snap[k] unless k is unknown. With WriteReadsBack (the    *)
(* code), a write after an unreadable failure first reads the file again   *)
(* and starts from what it holds, which settles the unknown keys (the read *)
(* back is assumed to succeed then; the code refuses a write that would    *)
(* otherwise guess an unknown key it does not set). Without it, the write  *)
(* started from the snapshot and wrote back the values from before the     *)
(* failed write. A crash keeps the disk (old *)
(* or new records for a write in flight: atomic rename); the restart reads *)
(* it. Durable = FALSE is SharedPreferences.commit() instead (review R3,   *)
(* F1): the memory takes the new value at WriteBegin and keeps it.         *)
(*                                                                         *)
(* Attempts. `cur` is id (live), -id (answer being taken in) or 0. start   *)
(* and retry claim an id (0 -> id); taking an answer in moves id -> -id, a *)
(* cancel id -> 0, each one compare-and-set, so exactly one wins. A cancel *)
(* that finds -id is too late and changes nothing (F2): the request stays, *)
(* and a failed save is reported and can be retried. All storage steps of  *)
(* registration run in order on the single `local` executor (lq, lt).      *)
(*                                                                         *)
(* Pairing. The key is stored with the registration it was set for and    *)
(* counts only for that one: ready <=> no mark, a registration, a token,   *)
(* and the key bound to that registration. Unpairing marks on disk, then   *)
(* invalidates attempts (reset, on `local`), then under the sync lock     *)
(* clears the registration, the token, the key and the rest (the pending   *)
(* request, sync state), then unmarks. A crash between any two steps is    *)
(* finished at the next start (resume, from the mark again). savePairing   *)
(* is refused while marked. A key stored by an earlier version (unbound)  *)
(* is bound to the current registration at start.                          *)
(*                                                                         *)
(* Sync runs (under the sync lock) read the registration, device id, token *)
(* and key in one read (SyncRun.current, PairingStore.session).            *)
(*                                                                         *)
(* Mutations: CancelSafe = FALSE (R2), TooLateNoop = FALSE (F2), Durable = *)
(* FALSE (R3, F1), StoreBeforeSend = FALSE (N2: the request goes out      *)
(* before the token is on disk), KeyBound = FALSE with UnpairMark = FALSE  *)
(* (review 6 item 3).                                                      *)
(*                                                                         *)
(* Three settings are fixes of findings of this model. TRUE is the code;   *)
(* FALSE is the code before the fix (RegistrationClient_runread,           *)
(* _unknownmark):                                                          *)
(*   RunReadsOnce     SyncRun.current reads the registration once, with   *)
(*                    the key (before: an unpairing mark between the reads *)
(*                    gave a run the key of A and the registration "").    *)
(*   UnknownMarkSet   a mark whose write failed unreadably reads as set,  *)
(*                    and the unmark is written again (bounded in the      *)
(*                    code: it may give up and stay marked) (before: it    *)
(*                    read as absent while the disk might hold it: a new   *)
(*                    registration began, and the next start resumed the   *)
(*                    unpairing and removed its request).                  *)
(*   WriteReadsBack   see Storage (before: a later write of other keys     *)
(*                    wrote the old mark back).                            *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS Tokens,        \* tokens the client may generate, in order (1, 2, ...)
          MaxAttempts,   \* attempt ids (start, retry, cancel and reset use one each)
          MaxCancels,    \* cancels and unpairings by the user
          MaxWriteFail,  \* failed writes ("old" or "unreadable")
          MaxFaults,     \* crashes and lost answers
          MaxRefusals,   \* server refusals that the client retries (429/503)
          CanExpire,     \* the code may expire (then a token without a device gets 401)
          WithKey, WithRuns, LegacyKey,
          CancelSafe, TooLateNoop, Durable, StoreBeforeSend, KeyBound, UnpairMark,
          RunReadsOnce, UnknownMarkSet, WriteReadsBack,
          ResumeAtStart  \* FALSE (mutation): a marked unpairing is not finished at start

None == [none |-> TRUE]
LegacyDev == 90      \* the registration of an earlier app version (LegacyKey)
LegacyTok == 9
DevOf(t) == 100 + t
NoKey == [tag |-> 0, bnd |-> 0]   \* tag: registration it was set for (ghost); bnd: bound to (0: unbound)
Stores == {"C", "S"}
Default(k) == CASE k = "mark" -> FALSE [] k = "key" -> NoKey [] OTHER -> 0

VARIABLES
  disk, snap, unk, w,        \* DurableMaps, per store; w: None or [next, by, res]
  srv, codeOk, nws, late, refusals,  \* server devices per token; network threads; requests in flight
  cur, ids, latest, lq, lt,  \* RegistrationMachine; `local`: task queue and current task
  upc, kpc, kreg, run, boot, \* unpairing, key step, sync run, start-up
  nextTok, rejected, cancels, wfails, faults,
  cancelled, dropped, stale, lostFail   \* ghosts

StoreV == <<disk, snap, unk, w>>
NetV == <<srv, codeOk, nws, late, refusals>>
MachV == <<cur, ids, latest, lq, lt>>
ThreadV == <<upc, kpc, kreg, run, boot>>
MiscV == <<nextTok, rejected, cancels, wfails, faults>>
GhostV == <<cancelled, dropped, stale, lostFail>>
vars == <<StoreV, NetV, MachV, ThreadV, MiscV, GhostV>>

InitC == [reg |-> IF LegacyKey THEN LegacyDev ELSE 0, mark |-> FALSE]
InitS == [tok |-> IF LegacyKey THEN LegacyTok ELSE 0,
          key |-> IF LegacyKey THEN [tag |-> LegacyDev, bnd |-> 0] ELSE NoKey, pend |-> 0]

Init ==
  /\ disk = [s \in Stores |-> IF s = "C" THEN InitC ELSE InitS]
  /\ snap = disk
  /\ unk = [s \in Stores |-> {}] /\ w = [s \in Stores |-> None]
  /\ srv = [t \in Tokens \cup {LegacyTok} |-> IF t = LegacyTok /\ LegacyKey THEN LegacyDev ELSE 0]
  /\ codeOk = TRUE /\ nws = {} /\ late = {} /\ refusals = 0
  /\ cur = 0 /\ ids = 0 /\ latest = 0 /\ lq = <<>> /\ lt = None
  /\ upc = "idle" /\ kpc = "idle" /\ kreg = 0 /\ run = None
  /\ boot = IF LegacyKey THEN "adopt" ELSE "none"
  /\ nextTok = 1 /\ rejected = FALSE /\ cancels = 0 /\ wfails = 0 /\ faults = 0
  /\ cancelled = {} /\ dropped = {} /\ stale = FALSE /\ lostFail = FALSE

----------------------------------------------------------------------------
\* Reads: they never block, and return only what the disk holds (unless
\* Durable = FALSE).
Rd(s, k) == IF k \in unk[s] THEN (IF k = "mark" THEN UnknownMarkSet ELSE Default(k)) ELSE snap[s][k]
Registration == IF Rd("C", "mark") \/ Rd("C", "reg") = 0 THEN 0 ELSE Rd("C", "reg")
Paired == Registration # 0 /\ Rd("S", "tok") # 0
\* PairingStore.masterKey (NoKey: none).
MasterKey ==
  LET r == Registration
      k == Rd("S", "key")
  IN IF r = 0 \/ k = NoKey THEN NoKey
     ELSE IF ~KeyBound \/ k.bnd = r THEN k ELSE NoKey
Ready == Paired /\ MasterKey # NoKey
Pending == Rd("S", "pend")
Busy == Paired \/ Rd("C", "mark")

\* A write of thread `by`: it waits for the result (w[s].res), then takes
\* it (Release) and goes on. With unknown keys (only when Durable) and
\* WriteReadsBack, it reads the file first: the snapshot becomes the disk.
WriteBegin(s, k, v, by) ==
  LET reread == WriteReadsBack /\ unk[s] # {}
      base == IF reread THEN disk[s] ELSE snap[s]
      next == [base EXCEPT ![k] = v]
  IN
  /\ w[s] = None
  /\ w' = [w EXCEPT ![s] = [next |-> next, by |-> by, res |-> "pending"]]
  /\ snap' = IF ~Durable THEN [snap EXCEPT ![s] = next]
            ELSE IF reread THEN [snap EXCEPT ![s] = disk[s]] ELSE snap
  /\ unk' = IF reread THEN [unk EXCEPT ![s] = {}] ELSE unk
WriteDone(s, by) == w[s] # None /\ w[s].by = by /\ w[s].res # "pending"
WriteOk(s) == w[s].res = "ok"
Release(s) == w' = [w EXCEPT ![s] = None]

Changed(a, b) == {k \in DOMAIN a : a[k] # b[k]}

\* The disk write ends (DurableMap.write).
DiskNew(s) ==
  /\ w[s] # None /\ w[s].res = "pending"
  /\ disk' = [disk EXCEPT ![s] = w[s].next] /\ snap' = [snap EXCEPT ![s] = w[s].next]
  /\ unk' = [unk EXCEPT ![s] = {}]
  /\ w' = [w EXCEPT ![s].res = "ok"]
  /\ UNCHANGED <<NetV, MachV, ThreadV, MiscV, GhostV>>
DiskOld(s) ==
  /\ w[s] # None /\ w[s].res = "pending" /\ wfails < MaxWriteFail
  /\ wfails' = wfails + 1
  /\ w' = [w EXCEPT ![s].res = "fail"]
  /\ UNCHANGED <<disk, snap, unk>>   \* Durable = FALSE: the memory keeps the new value
  /\ UNCHANGED <<NetV, MachV, ThreadV, nextTok, rejected, cancels, faults, GhostV>>
DiskUnreadable(s) ==
  /\ Durable /\ w[s] # None /\ w[s].res = "pending" /\ wfails < MaxWriteFail
  /\ wfails' = wfails + 1
  /\ \E d \in {disk[s], w[s].next} : disk' = [disk EXCEPT ![s] = d]
  /\ unk' = [unk EXCEPT ![s] = @ \cup Changed(snap[s], w[s].next)]
  /\ w' = [w EXCEPT ![s].res = "fail"]
  /\ UNCHANGED snap
  /\ UNCHANGED <<NetV, MachV, ThreadV, nextTok, rejected, cancels, faults, GhostV>>
Disk(s) == DiskNew(s) \/ DiskOld(s) \/ DiskUnreadable(s)

----------------------------------------------------------------------------
\* RegistrationMachine.

Task(k, id, tok, ans) == [k |-> k, id |-> id, tok |-> tok, ans |-> ans, step |-> "check"]
Claim == ids < MaxAttempts /\ cur = 0 /\ ids' = ids + 1 /\ cur' = ids + 1 /\ latest' = ids + 1

\* A Failed(id) result; the screen shows it iff id is the latest attempt.
\* Losing the failure of an attempt the user did not cancel is the F2 bug.
FailedEvt(id) == lostFail' = (lostFail \/ (id # 0 /\ id # latest /\ id \notin cancelled))
Spawn(id, t) == nws' = nws \cup {[id |-> id, tok |-> t, ans |-> "wait"]}

\* start() and retry(), from the screen (which offers start with nothing
\* pending, retry with a pending request).
Start ==
  /\ ~rejected /\ nextTok \in Tokens /\ ~Busy /\ Pending = 0 /\ upc = "idle"
  /\ Claim /\ lq' = Append(lq, Task("begin", ids + 1, 0, "-"))
  /\ UNCHANGED <<StoreV, NetV, lt, ThreadV, MiscV, GhostV>>
Retry ==
  /\ ~rejected /\ Pending # 0 /\ ~Busy /\ upc = "idle"
  /\ Claim /\ lq' = Append(lq, Task("retry", ids + 1, 0, "-"))
  /\ UNCHANGED <<StoreV, NetV, lt, ThreadV, MiscV, GhostV>>

LTake ==
  /\ lt = None /\ lq # <<>>
  /\ lt' = Head(lq) /\ lq' = Tail(lq)
  /\ UNCHANGED <<StoreV, NetV, cur, ids, latest, ThreadV, MiscV, GhostV>>

LStep(s) == lt' = [lt EXCEPT !.step = s]
At(k, s) == lt # None /\ lt.k = k /\ lt.step = s
\* release(id): ends attempt id if it is still current.
ReleaseCur(id) == cur' = IF cur = id THEN 0 ELSE cur

\* begin(): a fresh token, stored (and on disk) before it is sent.
BeginCheck ==
  /\ At("begin", "check")
  /\ IF cur # lt.id \/ Busy \/ Pending # 0
       THEN /\ ReleaseCur(lt.id) /\ FailedEvt(lt.id) /\ lt' = None
            /\ UNCHANGED <<StoreV, nws, nextTok>>
       ELSE /\ WriteBegin("S", "pend", nextTok, "L") /\ UNCHANGED disk
            /\ nextTok' = nextTok + 1
            /\ IF StoreBeforeSend THEN UNCHANGED nws ELSE Spawn(lt.id, nextTok)
            /\ lt' = [lt EXCEPT !.step = "w", !.tok = nextTok]
            /\ UNCHANGED <<cur, lostFail>>
  /\ UNCHANGED <<srv, codeOk, late, refusals, ids, latest, lq, ThreadV, rejected, cancels, wfails, faults,
                 cancelled, dropped, stale>>
BeginStored ==
  /\ At("begin", "w") /\ WriteDone("S", "L")
  /\ Release("S") /\ lt' = None
  /\ IF WriteOk("S")
       THEN /\ IF StoreBeforeSend THEN Spawn(lt.id, lt.tok) ELSE UNCHANGED nws
            /\ UNCHANGED <<cur, lostFail>>
       ELSE ReleaseCur(lt.id) /\ FailedEvt(lt.id) /\ UNCHANGED nws
  /\ UNCHANGED <<disk, snap, unk, srv, codeOk, late, refusals, ids, latest, lq, ThreadV, MiscV,
                 cancelled, dropped, stale>>

RetryRun ==
  /\ At("retry", "check")
  /\ lt' = None
  /\ IF Pending # 0 /\ cur = lt.id
       THEN Spawn(lt.id, Pending) /\ UNCHANGED <<cur, lostFail>>
       ELSE ReleaseCur(lt.id) /\ FailedEvt(lt.id) /\ UNCHANGED nws
  /\ UNCHANGED <<StoreV, srv, codeOk, late, refusals, ids, latest, lq, ThreadV, MiscV, cancelled, dropped, stale>>

\* finish(): take the answer in unless the attempt was cancelled or reset.
\* CancelSafe: one compare-and-set id -> -id. Before the fix (R2) it only
\* read cur = id, and a cancel could come in between it and the save.
FinishCheck ==
  /\ At("finish", "check")
  /\ IF cur # lt.id
       THEN lt' = None /\ UNCHANGED <<cur, lostFail>>          \* cancelled meanwhile: dropped
       ELSE /\ cur' = IF CancelSafe THEN -lt.id ELSE cur
            /\ CASE lt.ans \in {"ok", "created"} -> LStep("save") /\ UNCHANGED lostFail
                 [] lt.ans = "invalid"           -> LStep("reject") /\ UNCHANGED lostFail
                 [] OTHER                        -> LStep("fin") /\ FailedEvt(lt.id)
  /\ UNCHANGED <<StoreV, NetV, ids, latest, lq, ThreadV, MiscV, cancelled, dropped, stale>>

\* Steps of `local` that only write and wait.
LUnchanged == UNCHANGED <<NetV, cur, ids, latest, lq, ThreadV, MiscV, cancelled, dropped>>

\* savePairing: refused while unpairing; the token first, then the registration.
SaveTok ==
  /\ At("finish", "save")
  /\ IF Rd("C", "mark")
       THEN LStep("fin") /\ FailedEvt(lt.id) /\ UNCHANGED StoreV
       ELSE WriteBegin("S", "tok", lt.tok, "L") /\ LStep("tokw") /\ UNCHANGED <<disk, lostFail>>
  /\ UNCHANGED stale /\ LUnchanged
SaveTokDone ==
  /\ At("finish", "tokw") /\ WriteDone("S", "L")
  /\ Release("S")
  /\ IF WriteOk("S") THEN LStep("reg") /\ UNCHANGED lostFail ELSE LStep("fin") /\ FailedEvt(lt.id)
  /\ UNCHANGED <<disk, snap, unk, stale>> /\ LUnchanged
SaveReg ==
  /\ At("finish", "reg")
  /\ WriteBegin("C", "reg", DevOf(lt.tok), "L") /\ LStep("regw")
  /\ UNCHANGED <<disk, stale, lostFail>> /\ LUnchanged
\* Paired now. The pending request is removed (best effort).
SaveRegDone ==
  /\ At("finish", "regw") /\ WriteDone("C", "L")
  /\ Release("C")
  /\ IF WriteOk("C")
       THEN LStep("pend") /\ stale' = (stale \/ lt.id \in cancelled) /\ UNCHANGED lostFail
       ELSE LStep("fin") /\ FailedEvt(lt.id) /\ UNCHANGED stale
  /\ UNCHANGED <<disk, snap, unk>> /\ LUnchanged
SavePend ==
  /\ At("finish", "pend")
  /\ WriteBegin("S", "pend", 0, "L") /\ LStep("pendw")
  /\ UNCHANGED <<disk, stale, lostFail>> /\ LUnchanged
SavePendDone ==
  /\ At("finish", "pendw") /\ WriteDone("S", "L")
  /\ Release("S") /\ LStep("fin")
  /\ UNCHANGED <<disk, snap, unk, stale, lostFail>> /\ LUnchanged

\* A definite refusal (401/409): the request is removed, then the refusal
\* reported (a failed removal reports the write error instead).
Reject ==
  /\ At("finish", "reject")
  /\ WriteBegin("S", "pend", 0, "L") /\ LStep("rejectw")
  /\ UNCHANGED <<disk, stale, lostFail>> /\ LUnchanged
Rejected ==
  /\ At("finish", "rejectw") /\ WriteDone("S", "L")
  /\ Release("S") /\ LStep("fin") /\ FailedEvt(lt.id)
  /\ rejected' = (rejected \/ WriteOk("S"))
  /\ UNCHANGED <<disk, snap, unk, NetV, cur, ids, latest, lq, ThreadV, nextTok, cancels, wfails, faults,
                 cancelled, dropped, stale>>
\* finally: current.compareAndSet(-id, 0) (before the fix: the attempt ended).
FinishEnd ==
  /\ At("finish", "fin")
  /\ cur' = IF cur = -lt.id \/ (~CancelSafe /\ cur = lt.id) THEN 0 ELSE cur
  /\ lt' = None
  /\ UNCHANGED <<StoreV, NetV, ids, latest, lq, ThreadV, MiscV, GhostV>>

\* The store(None) of a cancel.
Drop ==
  /\ At("drop", "check")
  /\ WriteBegin("S", "pend", 0, "L") /\ LStep("w")
  /\ UNCHANGED <<disk, stale, lostFail>> /\ LUnchanged
Dropped ==
  /\ At("drop", "w") /\ WriteDone("S", "L")
  /\ Release("S") /\ lt' = None
  /\ UNCHANGED <<disk, snap, unk, stale, lostFail>> /\ LUnchanged

\* reset() of an unpairing, on `local` (so no answer is being taken in).
Reset ==
  /\ At("reset", "check")
  /\ cur' = 0
  /\ cancelled' = cancelled \cup (IF cur > 0 THEN {cur} ELSE {})
  /\ WriteBegin("S", "pend", 0, "L") /\ LStep("w")
  /\ UNCHANGED <<disk, NetV, ids, latest, lq, ThreadV, MiscV, dropped, stale, lostFail>>
\* The unpairing goes on (or, if the write failed, forget() throws).
ResetDone ==
  /\ At("reset", "w") /\ WriteDone("S", "L")
  /\ Release("S") /\ lt' = None
  /\ upc' = IF WriteOk("S") THEN "lock" ELSE "idle"
  /\ boot' = IF boot = "resume" /\ ~WriteOk("S") THEN "none" ELSE boot
  /\ UNCHANGED <<disk, snap, unk, NetV, cur, ids, latest, lq, kpc, kreg, run, MiscV, GhostV>>

LocalStep ==
  \/ LTake \/ BeginCheck \/ BeginStored \/ RetryRun
  \/ FinishCheck \/ SaveTok \/ SaveTokDone \/ SaveReg \/ SaveRegDone \/ SavePend \/ SavePendDone
  \/ Reject \/ Rejected \/ FinishEnd
  \/ Drop \/ Dropped \/ Reset \/ ResetDone

\* cancel() (the screen). Live attempt: one compare-and-set id -> 0, and
\* the request is removed. No attempt: the request is removed. Being taken
\* in: too late, nothing changes (TooLateNoop); before F2 it still
\* invalidated the attempt's results and removed the request.
Cancel ==
  /\ cancels < MaxCancels /\ ~Paired /\ upc = "idle" /\ (cur # 0 \/ Pending # 0)
  /\ ~(cur < 0 /\ TooLateNoop)
  /\ ids < MaxAttempts
  /\ cancels' = cancels + 1
  /\ ids' = ids + 1 /\ latest' = ids + 1
  /\ lq' = Append(lq, Task("drop", 0, 0, "-"))
  /\ IF cur < 0
       THEN UNCHANGED <<cur, cancelled, dropped>>
       ELSE /\ cur' = 0
            /\ cancelled' = cancelled \cup (IF cur > 0 THEN {cur} ELSE {})
            \* The user gives up the stored request and the cancelled
            \* attempt's token (begin() may still be storing it: a request
            \* already on its way cannot be called back).
            /\ dropped' = dropped \cup {Pending} \cup {n.tok : n \in {x \in nws : x.id = cur}}
                            \cup (IF lt # None /\ lt.id = cur /\ cur > 0 THEN {lt.tok} ELSE {})
  /\ UNCHANGED <<StoreV, NetV, lt, ThreadV, nextTok, rejected, wfails, faults, stale, lostFail>>

----------------------------------------------------------------------------
\* The network and the abstract server.

Answer(n, a) == nws' = (nws \ {n}) \cup {[n EXCEPT !.ans = a]}
NUnchanged == UNCHANGED <<StoreV, MachV, ThreadV, MiscV, GhostV>>

\* The token's device; else a new device, 401 once the code expired, or a
\* refusal by a gate (429/503), which the client retries.
ServeN(n) ==
  /\ n \in nws /\ n.ans = "wait"
  /\ \/ srv[n.tok] # 0 /\ Answer(n, "ok") /\ UNCHANGED <<srv, refusals>>
     \/ /\ srv[n.tok] = 0 /\ codeOk
        /\ srv' = [srv EXCEPT ![n.tok] = DevOf(n.tok)]
        /\ Answer(n, "created") /\ UNCHANGED refusals
     \/ srv[n.tok] = 0 /\ ~codeOk /\ Answer(n, "invalid") /\ UNCHANGED <<srv, refusals>>
     \/ /\ srv[n.tok] = 0 /\ refusals < MaxRefusals
        /\ refusals' = refusals + 1
        /\ Answer(n, "retry") /\ UNCHANGED srv
  /\ UNCHANGED <<codeOk, late>> /\ NUnchanged

\* The answer is lost (a network error ends the attempt); the request may
\* still reach the server later.
LoseN(n) ==
  /\ n \in nws /\ n.ans = "wait" /\ faults < MaxFaults
  /\ faults' = faults + 1
  /\ Answer(n, "neterr")
  /\ late' = late \cup {n.tok}
  /\ UNCHANGED <<StoreV, srv, codeOk, refusals, MachV, ThreadV, nextTok, rejected, cancels, wfails, GhostV>>

LateServeN(t) ==
  /\ t \in late
  /\ late' = late \ {t}
  /\ srv' = IF srv[t] = 0 /\ codeOk THEN [srv EXCEPT ![t] = DevOf(t)] ELSE srv
  /\ UNCHANGED <<codeOk, nws, refusals>> /\ NUnchanged

\* The network thread hands the answer to `local`.
NetDoneN(n) ==
  /\ n \in nws /\ n.ans # "wait"
  /\ nws' = nws \ {n}
  /\ lq' = Append(lq, Task("finish", n.id, n.tok, n.ans))
  /\ UNCHANGED <<StoreV, srv, codeOk, late, refusals, cur, ids, latest, lt, ThreadV, MiscV, GhostV>>

Serve == \E n \in nws : ServeN(n)
Lose == \E n \in nws : LoseN(n)
NetDone == \E n \in nws : NetDoneN(n)
LateServe == \E t \in late : LateServeN(t)

Expire ==
  /\ CanExpire /\ codeOk /\ codeOk' = FALSE
  /\ UNCHANGED <<srv, nws, late, refusals>> /\ NUnchanged

----------------------------------------------------------------------------
\* Unpairing (PairingStore.forget), on its own thread.

UUnchanged == UNCHANGED <<NetV, MachV, kpc, kreg, run, MiscV, GhostV>>

Unpair ==
  /\ cancels < MaxCancels /\ Paired /\ upc = "idle"
  /\ cancels' = cancels + 1
  /\ dropped' = dropped \cup {Rd("S", "tok"), Pending}
  /\ upc' = "mark"
  /\ UNCHANGED <<StoreV, NetV, MachV, kpc, kreg, run, boot, nextTok, rejected, wfails, faults,
                 cancelled, stale, lostFail>>

\* A failed write ends forget() (it throws); a resume at start then gives up.
UFail == upc' = "idle" /\ boot' = IF boot = "resume" THEN "none" ELSE boot
UDone == upc' = "idle" /\ boot' = IF boot = "resume" THEN "adopt" ELSE boot

UMark ==
  /\ upc = "mark"
  /\ IF UnpairMark
       THEN WriteBegin("C", "mark", TRUE, "U") /\ upc' = "markw"
       ELSE upc' = "inval" /\ UNCHANGED <<w, snap, unk>>
  /\ UNCHANGED <<disk, boot>> /\ UUnchanged
UMarked ==
  /\ upc = "markw" /\ WriteDone("C", "U")
  /\ Release("C")
  /\ IF WriteOk("C") THEN upc' = "inval" /\ UNCHANGED boot ELSE UFail
  /\ UNCHANGED <<disk, snap, unk>> /\ UUnchanged
\* invalidate(): Registration.reset() (latest bumped here, the rest on
\* `local`; ResetDone moves on to "lock") and the sync jobs cancelled.
UInval ==
  /\ upc = "inval" /\ ids < MaxAttempts
  /\ ids' = ids + 1 /\ latest' = ids + 1
  /\ lq' = Append(lq, Task("reset", 0, 0, "-"))
  /\ upc' = "invalw"
  /\ UNCHANGED <<StoreV, NetV, cur, lt, kpc, kreg, run, boot, MiscV, GhostV>>
\* The sync lock: a running job ends first.
ULock ==
  /\ upc = "lock" /\ run = None
  /\ upc' = "creg"
  /\ UNCHANGED <<StoreV, boot>> /\ UUnchanged
Locked == upc \in {"lock", "creg", "cregw", "ctok", "ctokw", "ckey", "ckeyw", "crest", "crestw",
                   "unmark", "unmarkw"}

\* One clearing step: write k := v in store s, then go on to `then`.
UClear(at, s, k, v) ==
  /\ upc = at
  /\ WriteBegin(s, k, v, "U") /\ upc' = at \o "w"
  /\ UNCHANGED <<disk, boot>> /\ UUnchanged
UCleared(at, s, then) ==
  /\ upc = at \o "w" /\ WriteDone(s, "U")
  /\ Release(s)
  /\ IF WriteOk(s) THEN upc' = then /\ UNCHANGED boot ELSE UFail
  /\ UNCHANGED <<disk, snap, unk>> /\ UUnchanged
UUnmark ==
  /\ upc = "unmark"
  /\ IF UnpairMark
       THEN WriteBegin("C", "mark", FALSE, "U") /\ upc' = "unmarkw" /\ UNCHANGED boot
       ELSE UDone /\ UNCHANGED <<w, snap, unk>>
  /\ UNCHANGED disk /\ UUnchanged
\* UnknownMarkSet: an unmark that failed unreadably is written again until
\* the mark is known; the code gives up after a few attempts (the mark then
\* stays, read as set, for the next start).
UUnmarked ==
  /\ upc = "unmarkw" /\ WriteDone("C", "U")
  /\ Release("C")
  /\ IF WriteOk("C") THEN UDone
     ELSE IF UnknownMarkSet /\ "mark" \in unk["C"]
       THEN (upc' = "unmark" /\ UNCHANGED boot) \/ UFail
       ELSE UFail
  /\ UNCHANGED <<disk, snap, unk>> /\ UUnchanged

\* An unpairing that stopped on a failed write stays marked; the next start
\* of the app finishes it (resume). Liveness assumes the app starts again.
Resume ==
  /\ ResumeAtStart /\ upc = "idle" /\ Rd("C", "mark") /\ boot = "none"
  /\ upc' = "mark" /\ boot' = "resume"
  /\ UNCHANGED StoreV /\ UUnchanged

UnpairStep ==
  \/ UMark \/ UMarked \/ UInval \/ ULock
  \/ UClear("creg", "C", "reg", 0) \/ UCleared("creg", "C", "ctok")
  \/ UClear("ctok", "S", "tok", 0) \/ UCleared("ctok", "S", "ckey")
  \/ UClear("ckey", "S", "key", NoKey) \/ UCleared("ckey", "S", "crest")
  \/ UClear("crest", "S", "pend", 0) \/ UCleared("crest", "S", "unmark")
  \/ UUnmark \/ UUnmarked

----------------------------------------------------------------------------
\* The key step (confirmKey) on its own thread; the screen captured the
\* registration when the step began.

KUnchanged == UNCHANGED <<NetV, MachV, upc, run, boot, MiscV, GhostV>>

KeyStart ==
  /\ WithKey /\ kpc = "idle" /\ Paired /\ MasterKey = NoKey
  /\ kpc' = "check" /\ kreg' = Registration
  /\ UNCHANGED StoreV /\ KUnchanged
KeyWrite ==
  /\ kpc = "check"
  /\ IF kreg # 0 /\ kreg = Registration /\ MasterKey = NoKey
       THEN /\ WriteBegin("S", "key", [tag |-> kreg, bnd |-> IF KeyBound THEN kreg ELSE 0], "K")
            /\ kpc' = "w"
       ELSE kpc' = "idle" /\ UNCHANGED <<w, snap, unk>>
  /\ UNCHANGED <<disk, kreg>> /\ KUnchanged
KeyDone ==
  /\ kpc = "w" /\ WriteDone("S", "K")
  /\ Release("S") /\ kpc' = "idle"
  /\ UNCHANGED <<disk, snap, unk, kreg>> /\ KUnchanged

\* At start, after a resume: a key that an earlier version stored without
\* its registration is bound to the current one.
BUnchanged == UNCHANGED <<NetV, MachV, upc, kpc, kreg, run, MiscV, GhostV>>
Adopt ==
  /\ boot = "adopt"
  /\ LET k == Rd("S", "key") IN
       IF KeyBound /\ k # NoKey /\ k.bnd = 0 /\ Registration # 0
         THEN WriteBegin("S", "key", [k EXCEPT !.bnd = Registration], "B") /\ boot' = "adoptw"
         ELSE boot' = "none" /\ UNCHANGED <<w, snap, unk>>
  /\ UNCHANGED disk /\ BUnchanged
Adopted ==
  /\ boot = "adoptw" /\ WriteDone("S", "B")
  /\ Release("S") /\ boot' = "none"
  /\ UNCHANGED <<disk, snap, unk>> /\ BUnchanged

----------------------------------------------------------------------------
\* A sync run (SyncRun.current, under the sync lock): the registration,
\* device id and key in one read (RunReadsOnce; before the fix: the device id
\* and the key, then the registration in a second read).

RUnchanged == UNCHANGED <<StoreV, NetV, MachV, upc, kpc, kreg, boot, MiscV, GhostV>>

RunStart ==
  /\ WithRuns /\ run = None /\ ~Locked
  /\ Rd("C", "reg") # 0 /\ Rd("S", "tok") # 0 /\ MasterKey # NoKey
  /\ run' = [ph |-> "got", dev |-> Rd("C", "reg"), key |-> MasterKey.tag,
             reg |-> IF RunReadsOnce THEN Registration ELSE -1]
  /\ RUnchanged
RunReg ==
  /\ run # None /\ run.ph = "got"
  /\ run' = [run EXCEPT !.ph = "use", !.reg = IF RunReadsOnce THEN run.reg ELSE Registration]
  /\ RUnchanged
RunEnd ==
  /\ run # None /\ run.ph = "use"
  /\ run' = None
  /\ RUnchanged

----------------------------------------------------------------------------
\* Process death: memory is lost; a write in flight leaves the old or the
\* new records. The restart reads the stores from disk, finishes a marked
\* unpairing (resume), then adopts a legacy key.
Crash ==
  /\ faults < MaxFaults
  /\ faults' = faults + 1
  /\ \E d \in [Stores -> BOOLEAN] :
       LET nd == [s \in Stores |-> IF w[s] # None /\ w[s].res = "pending" /\ d[s] THEN w[s].next ELSE disk[s]]
       IN /\ disk' = nd /\ snap' = nd
          /\ upc' = IF nd["C"].mark /\ ResumeAtStart THEN "mark" ELSE "idle"
          /\ boot' = IF nd["C"].mark /\ ResumeAtStart THEN "resume" ELSE "adopt"
  /\ unk' = [s \in Stores |-> {}] /\ w' = [s \in Stores |-> None]
  /\ nws' = {} /\ late' = late \cup {n.tok : n \in {x \in nws : x.ans = "wait"}}
  /\ cur' = 0 /\ latest' = 0 /\ lq' = <<>> /\ lt' = None
  /\ kpc' = "idle" /\ kreg' = 0 /\ run' = None
  /\ UNCHANGED <<srv, codeOk, refusals, ids, nextTok, rejected, cancels, wfails, GhostV>>

----------------------------------------------------------------------------
Next ==
  \/ Start \/ Retry \/ Cancel \/ LocalStep
  \/ Serve \/ Lose \/ NetDone
  \/ LateServe
  \/ Expire
  \/ Unpair \/ UnpairStep \/ Resume
  \/ KeyStart \/ KeyWrite \/ KeyDone \/ Adopt \/ Adopted
  \/ RunStart \/ RunReg \/ RunEnd
  \/ \E s \in Stores : Disk(s)
  \/ Crash

\* Every component goes on; the user eventually starts, retries and enters
\* the key. Cancels and unpairings are not forced (they are bounded).
Spec == Init /\ [][Next]_vars
          /\ WF_vars(LocalStep) /\ WF_vars(UnpairStep) /\ WF_vars(KeyWrite) /\ WF_vars(KeyDone)
          /\ WF_vars(Adopt) /\ WF_vars(Adopted) /\ WF_vars(RunReg) /\ WF_vars(RunEnd)
          /\ \A s \in Stores : WF_vars(DiskNew(s))
          /\ WF_vars(Serve \/ NetDone)
          /\ WF_vars(Start) /\ WF_vars(Retry) /\ WF_vars(KeyStart) /\ WF_vars(Resume)

----------------------------------------------------------------------------
\* A read returns what the disk holds: a value being written, or whose
\* write failed, is never returned (F1, R3).
ReadReturnsPersisted == \A s \in Stores : \A k \in DOMAIN snap[s] : k \notin unk[s] => snap[s][k] = disk[s][k]

\* Ready, as the app reads it, has its registration, token and key on disk.
\* (The mark is not included: before the UnknownMarkSet fix a mark whose
\* write failed unreadably read as absent.)
ReadyPersisted ==
  Ready => /\ disk["C"].reg = Registration
           /\ disk["S"].tok = Rd("S", "tok") /\ disk["S"].key = MasterKey

\* A sync run uses only a key set for the registration (server, device id)
\* that it uploads as (review 6 item 3).
NoForeignKey == run # None => run.key = run.dev
\* ... and keeps its sync state under that registration (SyncRun.reg).
RunRegMatches == (run # None /\ run.ph = "use") => run.reg = run.key

\* A cancelled (or reset) attempt never saves a registration (R2).
CancelledNoEffect == ~stale

\* The failure of an attempt the user did not cancel is reported (F2).
FailureReported == ~lostFail

\* A device created for one of the client's tokens stays reachable: the
\* client is paired with it on disk, or still has the token stored as its
\* pending request, unless the user gave the token up (a cancel that
\* returned as such, an unpairing).
TokenRecoverable ==
  \A t \in Tokens :
    srv[t] # 0 =>
      \/ disk["C"].reg = srv[t] /\ disk["S"].tok = t
      \/ disk["S"].pend = t
      \/ t \in dropped

\* Liveness, per request: every attempt ends (its answer taken in, or it
\* was cancelled or reset).
AttemptEnds == (cur # 0) ~> (cur = 0)
\* Liveness, final: the client ends ready with a key bound to its
\* registration, definitely refused, or with every token given up. Pairing
\* and then unpairing does not satisfy it: <>[] asks for the final state.
GaveUp == cur = 0 /\ Pending = 0 /\ nextTok \notin Tokens /\ upc = "idle"
Converges == <>[](Ready \/ rejected \/ GaveUp)

\* "This case never happens" (run.sh reach: each must be violated, so that
\* the fixes of the unknown mark are exercised, not vacuous).
\* An unmark failed unreadably and is about to be written again, from the
\* file read back (WriteReadsBack).
Unreached_UnmarkRewrite == ~(upc = "unmark" /\ "mark" \in unk["C"])
\* The unpairing gave up with the mark unknown: the app goes on, reading
\* the mark as set (UnknownMarkSet), until the next start finishes it.
Unreached_UnknownMarkIdle == ~(upc = "idle" /\ "mark" \in unk["C"])
=============================================================================
