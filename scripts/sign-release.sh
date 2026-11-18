#!/usr/bin/env bash
#
# Trove release signing — the ONLY step that ever touches the private keystore.
#
# Flow: CI (GitHub Actions) builds a reproducible UNSIGNED APK on every
# vYYYY.MM.DD tag. You download it, run this script locally, and publish the
# signed bundle with `gh release`. The keystore and passphrase never leave
# this machine and never enter the repo, CI, or any artifact.
#
# Usage:
#   scripts/sign-release.sh <unsigned-or-input.apk> [tag]
#   scripts/sign-release.sh app-release-unsigned.apk v2026.11.18
#
# What it does:
#   1. Signs the APK with the release key (apksigner, v1+v2+v3 schemes)
#   2. Verifies the signature and checks the cert fingerprint matches the
#      one recorded in signing/RELEASE_SIGNING.md (guards against a wrong
#      or tampered keystore)
#   3. Emits SHA256SUMS, a GPG detached signature of the APK, and a GPG
#      signature of the checksums file
#   4. If [tag] is given and `gh` is authenticated, creates/publishes the
#      GitHub release with APK + SHA256SUMS + .gpgsig files attached
#
# Env overrides:
#   TROVE_KEYSTORE_DIR (default ~/.keystores/trove)
#   TROVE_GPG_KEY      (default: your default secret key)

set -euo pipefail

KS_DIR="${TROVE_KEYSTORE_DIR:-$HOME/.keystores/trove}"
KS="$KS_DIR/trove-release.p12"
PASS_FILE="$KS_DIR/keystore.pass"
ALIAS="trove-release"
EXPECTED_FP="CF:BB:CD:6B:7A:74:A7:B1:04:7C:2F:DA:B5:1C:5C:94:8B:36:6B:E6:65:04:B9:B7:16:C7:33:7C:2F:C8:75:87"
GPG_KEY="${TROVE_GPG_KEY:-}"
APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"

[ -x "$APKSIGNER" ] || { echo "ERROR: apksigner not found (set ANDROID_HOME)" >&2; exit 1; }
[ -f "$KS" ]         || { echo "ERROR: keystore missing at $KS" >&2; exit 1; }
[ "$#" -ge 1 ]       || { echo "Usage: $0 <input.apk> [tag]" >&2; exit 1; }

IN_APK="$(readlink -f "$1")"
TAG="${2:-}"
if [ -n "$TAG" ] && ! [[ "$TAG" =~ ^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}$ ]]; then
  echo "ERROR: tag '$TAG' does not match vYYYY.MM.DD (e.g. v2026.11.18)" >&2
  exit 1
fi
OUT_DIR="$(mktemp -d)"
BASE="$(basename "$IN_APK" .apk)"
SIGNED="$OUT_DIR/${BASE}.apk"

PASS="$(cat "$PASS_FILE")"
trap 'unset PASS' EXIT

echo "==> Signing $IN_APK"
"$APKSIGNER" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" \
  --out "$SIGNED" "$IN_APK"

echo "==> Verifying signature"
"$APKSIGNER" verify --print-certs "$SIGNED"

ACTUAL_FP="$("$APKSIGNER" verify --print-certs "$SIGNED" | \
  awk '/certificate SHA-256 digest/ {print toupper($NF); exit}')"
ACTUAL_FP="${ACTUAL_FP//:/}"
ACTUAL_FP="$(printf '%s' "$ACTUAL_FP" | sed 's/\(..\)/\1:/g' | sed 's/:$//')"
if [ "$ACTUAL_FP" != "$EXPECTED_FP" ]; then
  echo "ERROR: cert fingerprint mismatch!" >&2
  echo "  expected: $EXPECTED_FP" >&2
  echo "  actual:   $ACTUAL_FP" >&2
  echo "Refusing to release." >&2
  exit 1
fi
echo "    cert fingerprint OK: $ACTUAL_FP"

echo "==> Checksums and signatures"
cd "$OUT_DIR"
sha256sum "$SIGNED" | sed "s|$SIGNED|$(basename "$SIGNED")|" > SHA256SUMS
gpg --detach-sign --armor ${GPG_KEY:+--local-user "$GPG_KEY"} \
    --output "$(basename "$SIGNED").gpgsig" "$SIGNED"
gpg --detach-sign --armor ${GPG_KEY:+--local-user "$GPG_KEY"} \
    --output SHA256SUMS.gpgsig SHA256SUMS
gpg --verify "$(basename "$SIGNED").gpgsig" "$SIGNED" > /dev/null 2>&1 \
  && echo "    GPG signature: GOOD"

echo
echo "==> Signed artifacts in $OUT_DIR:"
ls -la "$OUT_DIR"

if [ -n "$TAG" ]; then
  if command -v gh > /dev/null 2>&1; then
    echo "==> Publishing GitHub release $TAG"
    gh release create "$TAG" \
      "$SIGNED#Trove-$TAG.apk" \
      "SHA256SUMS#SHA256SUMS" \
      "$(basename "$SIGNED").gpgsig#Trove-$TAG.apk.gpgsig" \
      "SHA256SUMS.gpgsig#SHA256SUMS.gpgsig" \
      --title "Trove $TAG" --generate-notes --verify-tags
    echo "    release published: verify users check SHA256SUMS + gpg sig"
  else
    echo "gh not found; upload manually from $OUT_DIR"
  fi
fi

echo
echo "==> Users verify with:"
echo "    sha256sum -c SHA256SUMS"
echo "    gpg --verify SHA256SUMS.gpgsig SHA256SUMS"
