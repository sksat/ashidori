#!/usr/bin/env bash
# Fetches the pinned verification tools (checksums verified) and runs every
# model check. Usage:
#   formal/run.sh                 # everything
#   formal/run.sh fast            # everything except the slow TLA+ configs ($SLOW_TLA)
#   formal/run.sh tamarin         # crypto models only
#   formal/run.sh tla [Name...]   # TLA+ models only (optionally some configs, e.g. Outbox Outbox_nonatomic)
#   formal/run.sh fetch           # only download the tools
# Tools are cached in $ASHIDORI_FORMAL_CACHE (default ~/.cache/ashidori-formal).
# Linux x86_64 only (Tamarin and Maude are prebuilt binaries).
# TLC runs one config at a time with $TLC_JAVA_OPTS (default -Xmx3g) and
# $TLC_WORKERS workers (default 4).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="${ASHIDORI_FORMAL_CACHE:-$HOME/.cache/ashidori-formal}"

TLA_VERSION=1.7.4
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/v${TLA_VERSION}/tla2tools.jar"
TLA_SHA256=936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88

TAMARIN_VERSION=1.12.0
TAMARIN_URL="https://github.com/tamarin-prover/tamarin-prover/releases/download/${TAMARIN_VERSION}/tamarin-prover-${TAMARIN_VERSION}-linux64-ubuntu.tar.gz"
TAMARIN_SHA256=201be06f469e47cff554df6ca93db8366fc2c69d70c61fcbd1370a1074b469c6

MAUDE_VERSION=3.5.1
MAUDE_URL="https://github.com/maude-lang/Maude/releases/download/Maude${MAUDE_VERSION}/Maude-${MAUDE_VERSION}-linux-x86_64.zip"
MAUDE_SHA256=72ed1ca87e3b3d0dfc6ee1436baf154bf04c45ff97d521bec040c5e8dfc8f92c

TLA_JAR="$CACHE/dl/tla2tools-${TLA_VERSION}.jar"
TAMARIN="$CACHE/tamarin-${TAMARIN_VERSION}/tamarin-prover"
MAUDE="$CACHE/maude-${MAUDE_VERSION}/maude"

# fetch <url> <file> <sha256>
fetch() {
  local url="$1" file="$2" sum="$3"
  mkdir -p "$(dirname "$file")"
  if [ ! -f "$file" ] || ! echo "$sum  $file" | sha256sum -c --status; then
    echo "fetching $url"
    curl -fsSL --retry 3 -o "$file.part" "$url"
    if ! echo "$sum  $file.part" | sha256sum -c --status; then
      echo "checksum mismatch for $url" >&2
      rm -f "$file.part"
      exit 1
    fi
    mv "$file.part" "$file"
  fi
}

fetch_tla() { fetch "$TLA_URL" "$TLA_JAR" "$TLA_SHA256"; }

fetch_tamarin() {
  local tgz="$CACHE/dl/tamarin-${TAMARIN_VERSION}.tar.gz" zip="$CACHE/dl/maude-${MAUDE_VERSION}.zip"
  fetch "$TAMARIN_URL" "$tgz" "$TAMARIN_SHA256"
  fetch "$MAUDE_URL" "$zip" "$MAUDE_SHA256"
  if [ ! -x "$TAMARIN" ]; then
    mkdir -p "$(dirname "$TAMARIN")"
    tar xzf "$tgz" -C "$(dirname "$TAMARIN")"
    chmod +x "$TAMARIN"
  fi
  if [ ! -x "$MAUDE" ]; then
    mkdir -p "$(dirname "$MAUDE")"
    unzip -q -o "$zip" -d "$(dirname "$MAUDE")"
    chmod +x "$MAUDE"
  fi
}

failures=0
checks=0
report() { # report <name> <ok|FAIL> <seconds> <detail>
  checks=$((checks + 1))
  printf '%-38s %-4s %6ss  %s\n' "$1" "$2" "$3" "$4"
  [ "$2" = ok ] || failures=$((failures + 1))
}

# TLC resources: a heap cap and a worker count (one TLC runs at a time).
TLC_JAVA_OPTS="${TLC_JAVA_OPTS:--Xmx3g}"
TLC_WORKERS="${TLC_WORKERS:-4}"

LOGS="${ASHIDORI_FORMAL_LOGS:-$CACHE/logs}"
mkdir -p "$LOGS"

# ---- Tamarin ----

# Expected result per lemma of crypto/batch.spthy.
BATCH_EXPECT="
executable verified
key_secrecy verified
batch_key_secrecy verified
payload_secrecy verified
slot_binding verified
no_slot_move verified
payload_secrecy_if_key_revealed falsified
slot_binding_if_key_revealed falsified
no_slot_move_if_key_revealed falsified
same_device_id_for_two_devices verified
no_forward_secrecy verified
"

run_tamarin() {
  fetch_tamarin
  local log t0 t1 summary
  log="$LOGS/batch.log"
  t0=$(date +%s)
  "$TAMARIN" --with-maude="$MAUDE" --prove "$HERE/crypto/batch.spthy" >"$log" 2>&1 || true
  t1=$(date +%s)
  summary=$(sed -n '/summary of summaries/,$p' "$log")
  if echo "$summary" | grep -q "wellformedness check failed"; then
    report "tamarin batch (wellformedness)" FAIL $((t1 - t0)) "see $log"
  fi
  while read -r lemma want; do
    [ -n "$lemma" ] || continue
    local line got
    line=$(echo "$summary" | grep -E "^ *$lemma \(" || true)
    got=$(echo "$line" | sed -E 's/.*\): ([a-z]+).*/\1/')
    if [ "$got" = "$want" ]; then
      report "tamarin batch/$lemma" ok $((t1 - t0)) "$got (expected)"
    else
      report "tamarin batch/$lemma" FAIL $((t1 - t0)) "got '${got:-none}', expected $want; see $log"
    fi
  done <<<"$BATCH_EXPECT"

  # Mutation: the slot is not bound as AAD; slot binding must break.
  log="$LOGS/batch-NOAAD.log"
  t0=$(date +%s)
  "$TAMARIN" --with-maude="$MAUDE" -D=NOAAD --prove=slot_binding --prove=no_slot_move "$HERE/crypto/batch.spthy" >"$log" 2>&1 || true
  t1=$(date +%s)
  summary=$(sed -n '/summary of summaries/,$p' "$log")
  for lemma in slot_binding no_slot_move; do
    got=$(echo "$summary" | grep -E "^ *$lemma \(" | sed -E 's/.*\): ([a-z]+).*/\1/')
    if [ "$got" = falsified ]; then
      report "tamarin batch -D=NOAAD/$lemma" ok $((t1 - t0)) "falsified (expected)"
    else
      report "tamarin batch -D=NOAAD/$lemma" FAIL $((t1 - t0)) "got '${got:-none}', expected falsified; see $log"
    fi
  done

  local flag want name
  for flag in "" REVEAL; do
    if [ -z "$flag" ]; then want=verified; name="tamarin privacy (diff-equiv)"; else want=falsified; name="tamarin privacy -D=REVEAL"; fi
    log="$LOGS/privacy${flag:+-$flag}.log"
    t0=$(date +%s)
    "$TAMARIN" --with-maude="$MAUDE" --diff ${flag:+-D=$flag} --prove "$HERE/crypto/batch_privacy.spthy" >"$log" 2>&1 || true
    t1=$(date +%s)
    got=$(sed -n '/summary of summaries/,$p' "$log" | grep "Observational_equivalence" | sed -E 's/.*: ([a-z]+).*/\1/')
    if [ "$got" = "$want" ]; then
      report "$name" ok $((t1 - t0)) "$got (expected)"
    else
      report "$name" FAIL $((t1 - t0)) "got '${got:-none}', expected $want; see $log"
    fi
  done
}

# ---- TLA+ ----

# One check per line: <config> <module> <expected> [cover:A,B...] [reach:X,Y...]
#   pass          TLC must finish with no error.
#   inv:<Name>    TLC must report "Invariant <Name> is violated".
#   act:<Name>    TLC must report "Action property <Name> is violated".
#   live:<Name>   TLC must report a temporal property violation, and <Name>
#                 must be the only PROPERTY of the config (TLC does not name
#                 the violated temporal property, so it must be the only one).
#   cover:...     each named action must fire at least once (TLC -coverage),
#                 so that the config does not pass vacuously.
#   reach:...     each named invariant (Unreached_*, "this case never
#                 happens") must be violated in a run of the same config with
#                 only that invariant: the case is reachable.
# Configs named "finding" in their comment model the code as it is where
# this model found a problem (see formal/README.md); the others model the
# code, its fixes, or mutations that must break the property they name.
TLA_CHECKS="
Registration Registration pass cover:SRecoverHit,SGateRefuse,SStartKnown,SBatchCreate,SBatchUnique,SBatchZero,SRecheckKnown,SRecheckNew,SFullCheckInvalid
Registration_full Registration pass cover:SBatchCreate,SBatchZero,SFullCheckFull
Registration_live Registration pass
Registration_norecheck Registration inv:RetriesAgree
Registration_gatefirst Registration inv:RecoverUngated
Registration_expiry Registration inv:CodeIffDevice
Registration_nounique Registration inv:CodeIffDevice
Accounts Accounts pass cover:SRecoverHit,SClosed,SGateRefuse,SBatchCreate,SBatchUnique,SBatchFull,SRecheckKnown,SRecheckFull
Accounts_other Accounts pass cover:SStartKnown,SBatchFull,SRecheckFull
Accounts_live Accounts pass
Accounts_norecheck Accounts inv:RetriesAgree
Accounts_gatefirst Accounts inv:RecoverUngated
Accounts_closedrace Accounts inv:RetriesAgree
Outbox Outbox pass cover:Seal,Commit,Crash,Timeout,Unpair reach:Unreached_Resealed
Outbox_2faults Outbox pass
Outbox_nonatomic Outbox inv:SameSlotSameCiphertext
Outbox_archive Outbox pass cover:ArchOpen,ArchPart,Replace reach:Unreached_HandedOver
Outbox_r1 Outbox inv:ArchiveComplete
Outbox_late Outbox live:ArchiveLatest
BatchCommit_fixed BatchCommit pass
BatchCommit_current BatchCommit inv:CommittedHasObject
BatchCommit_dg BatchCommit inv:CommittedHasObject
BatchCommit_nocount BatchCommit inv:CountersMatch
BatchCommit_nocaps BatchCommit inv:UnderCaps
SharedCheckin SharedCheckin pass
SharedCheckin_old SharedCheckin inv:LatestWins
SharedCheckin_skew SharedCheckin inv:StaleMeansLaterOp
FeedResync FeedResync pass
FeedResync_firstpage FeedResync inv:NoMixedVersions
StatusDownload StatusDownload pass
StatusDownload_hostile StatusDownload pass
StatusDownload_fixed StatusDownload pass
StatusDownload_fixed1 StatusDownload pass
StatusDownload_fakes StatusDownload pass
StatusDownload_manyfakes StatusDownload pass
StatusDownload_manyfakes_pre StatusDownload live:StatusLive
StatusDownload_fakeseq StatusDownload live:StatusLive
StatusDownload_fakecursor StatusDownload live:StatusLive
StatusDownload_r4 StatusDownload live:StatusLive
StatusDownload_r5 StatusDownload live:StatusLive
StatusDownload_blob StatusDownload inv:AuthOnlyOnSuccess
StatusRotation_f6 StatusRotation pass
StatusRotation_f6real StatusRotation pass
StatusRotation_f6pre StatusRotation live:StatusLive
StatusRotation_pages4 StatusRotation pass cover:RescanPage,RetryStep
StatusRotation_pages5 StatusRotation pass
StatusRotation_2dev StatusRotation pass
StatusRotation_r5 StatusRotation live:StatusLive
RegistrationClient RegistrationClient pass cover:Cancel,SaveRegDone,Rejected,ResetDone,UUnmarked,KeyDone,RunReg,DiskUnreadable,DiskOld,Crash,Lose,LateServe
RegistrationClient_2faults RegistrationClient pass
RegistrationClient_legacy RegistrationClient pass cover:Adopted,RunReg
RegistrationClient_live RegistrationClient pass
RegistrationClient_noresume RegistrationClient live:Converges
RegistrationClient_runread RegistrationClient inv:RunRegMatches
RegistrationClient_unknownmark RegistrationClient inv:TokenRecoverable
RegistrationClient_r2 RegistrationClient inv:CancelledNoEffect
RegistrationClient_f2 RegistrationClient inv:TokenRecoverable
RegistrationClient_f2report RegistrationClient inv:FailureReported
RegistrationClient_r3 RegistrationClient inv:ReadyPersisted
RegistrationClient_applypersist RegistrationClient inv:TokenRecoverable
RegistrationClient_unpair RegistrationClient inv:NoForeignKey
"

# Configs that take 30 s or more here (several times that on a CI runner).
# `fast` skips them; CI runs them daily and on demand (.github/workflows/formal.yml).
SLOW_TLA="RegistrationClient_2faults RegistrationClient_live Outbox_2faults StatusDownload StatusDownload_hostile
  StatusDownload_fixed StatusDownload_fixed1 StatusDownload_fakes StatusDownload_manyfakes StatusDownload_manyfakes_pre
  StatusRotation_2dev Accounts_other"
SLOW_TLA="${SLOW_TLA//$'\n'/ }"

# The PROPERTY / PROPERTIES names of a config file.
cfg_properties() {
  awk '/^(PROPERTY|PROPERTIES)([ \t]|$)/ {p=1; for (i=2;i<=NF;i++) print $i; next}
       /^[A-Z_]+([ \t]|$)/ {p=0}
       p {for (i=1;i<=NF;i++) print $i}' "$1"
}

# A copy of config file <cfg> that checks only the invariant <name>.
cfg_only_invariant() { # <cfg file> <name> <out>
  awk '/^(INVARIANT|INVARIANTS|PROPERTY|PROPERTIES)([ \t]|$)/ {skip=1; next}
       /^[A-Z_]+([ \t]|$)/ {skip=0}
       !skip' "$1" >"$3"
  echo "INVARIANT $2" >>"$3"
}

# Runs TLC on <module> with the config file <cfg> (relative to tla/, or absolute).
tlc() { # <cfg> <module> <log> [TLC options...]
  local cfg="$1" module="$2" log="$3" meta
  shift 3
  meta="$LOGS/states-$(basename "$log" .log)"
  mkdir -p "$LOGS/tmp"
  # shellcheck disable=SC2086 # TLC_JAVA_OPTS holds several options
  (cd "$HERE/tla" && java $TLC_JAVA_OPTS -XX:+UseParallelGC -Djava.io.tmpdir="$LOGS/tmp" -cp "$TLA_JAR" tlc2.TLC \
    -workers "$TLC_WORKERS" -deadlock "$@" -metadir "$meta" -config "$cfg" "$module.tla") >"$log" 2>&1 || true
  rm -rf "$meta"
}

run_tla_one() { # <config> <module> <expected> [cover:...] [reach:...]
  local cfg="$1" module="$2" expect="$3" cover="" reach="" opt log t0 t1 states cov_opt=()
  shift 3
  for opt in "$@"; do
    case "$opt" in
      cover:*) cover="${opt#cover:}" ;;
      reach:*) reach="${opt#reach:}" ;;
      *) report "tlc $cfg" FAIL 0 "bad option '$opt' in run.sh"; return ;;
    esac
  done
  log="$LOGS/$cfg.log"
  [ -n "$cover" ] && cov_opt=(-coverage 100000)
  t0=$(date +%s)
  tlc "$cfg.cfg" "$module" "$log" "${cov_opt[@]}"
  t1=$(date +%s)
  states=$(grep -Eo '[0-9,]+ distinct states found' "$log" | tail -1 || true)
  local name="tlc $cfg" secs=$((t1 - t0))
  case "$expect" in
    pass)
      if ! grep -q "Model checking completed. No error has been found." "$log"; then
        report "$name" FAIL $secs "$(grep -E 'is violated|violated|Error:' "$log" | head -1); see $log"
      else
        local a missing=()
        for a in ${cover//,/ }; do
          # "<Action line ..., col ... of module M>: distinct:total"
          if ! grep -Eq "^<${a} line [^>]*>: [0-9]+:[1-9][0-9]*\$" "$log"; then missing+=("$a"); fi
        done
        if [ ${#missing[@]} -gt 0 ]; then
          report "$name" FAIL $secs "no error, but never fired: ${missing[*]}; see $log"
        else
          report "$name" ok $secs "no error; $states${cover:+; all covered}"
        fi
      fi
      ;;
    inv:*|act:*)
      local want
      if [ "${expect%%:*}" = inv ]; then want="Invariant ${expect#inv:} is violated"; else want="Action property ${expect#act:} is violated"; fi
      if grep -q "$want" "$log"; then
        report "$name" ok $secs "counterexample found (expected): $want"
      else
        report "$name" FAIL $secs "expected '$want'; got '$(grep -E 'violated|Error:' "$log" | head -1)'; see $log"
      fi
      ;;
    live:*)
      local props
      props=$(cfg_properties "$HERE/tla/$cfg.cfg" | tr '\n' ' ' | sed 's/ $//')
      if [ "$props" != "${expect#live:}" ]; then
        report "$name" FAIL $secs "config must have only PROPERTY ${expect#live:} (has: $props)"
      elif grep -q "Temporal properties were violated" "$log"; then
        report "$name" ok $secs "counterexample found (expected): ${expect#live:} is violated"
      else
        report "$name" FAIL $secs "expected ${expect#live:} to be violated; got '$(grep -E 'violated|Error:|No error' "$log" | head -1)'; see $log"
      fi
      ;;
    *) report "$name" FAIL 0 "bad expectation '$expect' in run.sh" ;;
  esac
  # Reachability: each named Unreached_* invariant must be violated, i.e.
  # some behavior of the config reaches that case.
  local r rcfg rlog
  for r in ${reach//,/ }; do
    rcfg="$LOGS/tmp/$cfg-$r.cfg"
    rlog="$LOGS/$cfg-$r.log"
    cfg_only_invariant "$HERE/tla/$cfg.cfg" "$r" "$rcfg"
    t0=$(date +%s)
    tlc "$rcfg" "$module" "$rlog"
    t1=$(date +%s)
    if grep -q "Invariant $r is violated" "$rlog"; then
      report "tlc $cfg reach $r" ok $((t1 - t0)) "reached"
    else
      report "tlc $cfg reach $r" FAIL $((t1 - t0)) "not reached; see $rlog"
    fi
  done
}

run_tla() {
  fetch_tla
  local only=("$@") cfg module expect opts known=" "
  while read -r cfg module expect opts; do
    [ -n "$cfg" ] && known+="$cfg "
  done <<<"$TLA_CHECKS"
  for cfg in "${only[@]}"; do
    if [[ "$known" != *" $cfg "* ]]; then
      echo "unknown TLA+ config '$cfg' (known:$known)" >&2
      exit 2
    fi
  done
  while read -r cfg module expect opts; do
    [ -n "$cfg" ] || continue
    if [ ${#only[@]} -gt 0 ] && [[ ! " ${only[*]} " == *" $cfg "* ]]; then continue; fi
    if [ "${SKIP_SLOW:-}" = 1 ] && [[ " $SLOW_TLA " == *" $cfg "* ]]; then continue; fi
    # shellcheck disable=SC2086 # opts holds several options
    run_tla_one "$cfg" "$module" "$expect" $opts
  done <<<"$TLA_CHECKS"
}

case "${1:-all}" in
  fetch) fetch_tla; fetch_tamarin; exit 0 ;;
  tamarin) run_tamarin ;;
  tla) shift; run_tla "$@" ;;
  all) run_tamarin; run_tla ;;
  fast) SKIP_SLOW=1; run_tamarin; run_tla ;;
  *) echo "usage: $0 [all|fast|fetch|tamarin|tla [config...]]" >&2; exit 2 ;;
esac

echo "logs: $LOGS"
if [ "$checks" -eq 0 ]; then
  echo "no check ran" >&2
  exit 1
fi
if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) did not give the expected result" >&2
  exit 1
fi
echo "all $checks checks gave the expected result"
