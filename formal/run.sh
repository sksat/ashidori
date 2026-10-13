#!/usr/bin/env bash
# Fetches the pinned verification tools (checksums verified) and runs every
# model check. Usage:
#   formal/run.sh                 # everything
#   formal/run.sh tamarin         # crypto models only
#   formal/run.sh tla [Name...]   # TLA+ models only (optionally some configs, e.g. Outbox Outbox_nonatomic)
#   formal/run.sh fetch           # only download the tools
# Tools are cached in $ASHIDORI_FORMAL_CACHE (default ~/.cache/ashidori-formal).
# Linux x86_64 only (Tamarin and Maude are prebuilt binaries).
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
report() { # report <name> <ok|FAIL> <seconds> <detail>
  printf '%-34s %-4s %6ss  %s\n' "$1" "$2" "$3" "$4"
  [ "$2" = ok ] || failures=$((failures + 1))
}

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

# <config> <module> <expected>: "pass", or "fail:<text that TLC must print>".
TLA_CHECKS="
Outbox Outbox pass
Outbox_nonatomic Outbox fail:Invariant_SameSlotSameCiphertext_is_violated
BatchCommit_fixed BatchCommit pass
BatchCommit_current BatchCommit fail:Invariant_CommittedHasObject_is_violated
Registration Registration pass
Registration_norecheck Registration fail:Invariant_RetriesAgree_is_violated
Registration_applypersist Registration fail:Invariant_TokenRecoverable_is_violated
Accounts Accounts pass
Accounts_norecheck Accounts fail:Invariant_RetriesAgree_is_violated
SharedCheckin SharedCheckin pass
SharedCheckin_old SharedCheckin fail:Invariant_LatestWins_is_violated
FeedResync FeedResync pass
FeedResync_firstpage FeedResync fail:Invariant_NoMixedVersions_is_violated
StatusDownload StatusDownload pass
StatusDownload_hostile StatusDownload pass
StatusDownload_fixed StatusDownload pass
StatusDownload_fakeseq StatusDownload fail:Temporal_properties_were_violated
StatusDownload_fakecursor StatusDownload fail:Temporal_properties_were_violated
"

run_tla() {
  fetch_tla
  local only=("$@") cfg module expect
  while read -r cfg module expect; do
    [ -n "$cfg" ] || continue
    if [ ${#only[@]} -gt 0 ] && [[ ! " ${only[*]} " == *" $cfg "* ]]; then continue; fi
    local log="$LOGS/$cfg.log" t0 t1 states
    t0=$(date +%s)
    mkdir -p "$LOGS/tmp"
    (cd "$HERE/tla" && java -XX:+UseParallelGC -Djava.io.tmpdir="$LOGS/tmp" -cp "$TLA_JAR" tlc2.TLC \
      -workers auto -deadlock -metadir "$LOGS/states-$cfg" -config "$cfg.cfg" "$module.tla") >"$log" 2>&1 || true
    rm -rf "$LOGS/states-$cfg"
    t1=$(date +%s)
    states=$(grep -Eo '[0-9,]+ distinct states found' "$log" | tail -1 || true)
    if [ "$expect" = pass ]; then
      if grep -q "Model checking completed. No error has been found." "$log"; then
        report "tlc $cfg" ok $((t1 - t0)) "no error; $states"
      else
        report "tlc $cfg" FAIL $((t1 - t0)) "$(grep -E 'is violated|violated|Error:' "$log" | head -1); see $log"
      fi
    else
      local text="${expect#fail:}"
      text="${text//_/ }"
      if grep -q "$text" "$log"; then
        report "tlc $cfg" ok $((t1 - t0)) "counterexample found (expected): $text"
      else
        report "tlc $cfg" FAIL $((t1 - t0)) "expected '$text'; see $log"
      fi
    fi
  done <<<"$TLA_CHECKS"
}

case "${1:-all}" in
  fetch) fetch_tla; fetch_tamarin; exit 0 ;;
  tamarin) run_tamarin ;;
  tla) shift; run_tla "$@" ;;
  all) run_tamarin; run_tla ;;
  *) echo "usage: $0 [all|fetch|tamarin|tla [config...]]" >&2; exit 2 ;;
esac

echo "logs: $LOGS"
if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) did not give the expected result" >&2
  exit 1
fi
echo "all checks gave the expected result"
