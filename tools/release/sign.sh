#!/bin/bash
# Signs a release APK with key rotation (APK Signature Scheme v3), so it
# installs over builds signed with the previous key.
#
#   tools/release/sign.sh <in.apk> <out.apk>
#
# Reads from ~/.gradle/gradle.properties (outside the repository):
#   ashidori.release.storeFile, ashidori.release.storePassword,
#   ashidori.release.keyAlias        the current key
#   ashidori.release.lineage         the proof of rotation from the previous key
#   ashidori.release.oldStoreFile, ashidori.release.oldStorePassword,
#   ashidori.release.oldKeyAlias     the previous key
set -euo pipefail
in=$1 out=$2
props=$HOME/.gradle/gradle.properties
prop() { grep -m1 "^$1=" "$props" | cut -d= -f2-; }
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
apksigner=${APKSIGNER:-$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)apksigner}
cp "$in" "$out"
"$apksigner" sign \
  --ks "$(prop ashidori.release.oldStoreFile)" --ks-pass "pass:$(prop ashidori.release.oldStorePassword)" \
  --ks-key-alias "$(prop ashidori.release.oldKeyAlias)" \
  --next-signer \
  --ks "$(prop ashidori.release.storeFile)" --ks-pass "pass:$(prop ashidori.release.storePassword)" \
  --ks-key-alias "$(prop ashidori.release.keyAlias)" \
  --lineage "$(prop ashidori.release.lineage)" --rotation-min-sdk-version 33 \
  "$out"
"$apksigner" verify --print-certs "$out" | grep 'Signer #1 certificate SHA-256'
