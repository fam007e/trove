#!/usr/bin/env bash
# ==============================================================================
# Trove CI Reproducibility Verification Runner
# Builds two independent release APKs from clean states and asserts bit-for-bit
# hash parity across all embedded DEX bytecode, resources, and assets.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_ROOT/app/build/outputs/apk/release"

echo "=== Trove Automated Reproducibility Verification ==="
echo "Project Root: $PROJECT_ROOT"

TMP_WORK="$(mktemp -d /tmp/trove-repro-XXXXXX)"
trap 'rm -rf "$TMP_WORK"' EXIT

# Reproducible Builds Standard: derive timestamp from latest git commit
if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
    if git -C "$PROJECT_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        export SOURCE_DATE_EPOCH="$(git -C "$PROJECT_ROOT" log -1 --pretty=%ct)"
    else
        export SOURCE_DATE_EPOCH="1700000000"
    fi
fi
echo "Using SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH ($(date -u -d "@$SOURCE_DATE_EPOCH" 2>/dev/null || date -u -r "$SOURCE_DATE_EPOCH" 2>/dev/null || echo "UTC"))"

echo "--> [1/3] Building First Independent Release APK (Pass A)..."
cd "$PROJECT_ROOT"
./gradlew clean assembleRelease --no-build-cache --no-daemon

APK_A=$(find "$BUILD_DIR" -name "*.apk" | head -n 1)
if [ -z "$APK_A" ] || [ ! -f "$APK_A" ]; then
    echo "ERROR: First build did not produce an APK!" >&2
    exit 1
fi
cp "$APK_A" "$TMP_WORK/trove-pass-a.apk"
SHA_A=$(sha256sum "$TMP_WORK/trove-pass-a.apk" | awk '{print $1}')
echo "Pass A SHA256: $SHA_A"

echo "--> [2/3] Building Second Independent Release APK (Pass B)..."
# Purge build outputs and re-run with different file system modification times
./gradlew clean assembleRelease --no-build-cache --no-daemon

APK_B=$(find "$BUILD_DIR" -name "*.apk" | head -n 1)
if [ -z "$APK_B" ] || [ ! -f "$APK_B" ]; then
    echo "ERROR: Second build did not produce an APK!" >&2
    exit 1
fi
cp "$APK_B" "$TMP_WORK/trove-pass-b.apk"
SHA_B=$(sha256sum "$TMP_WORK/trove-pass-b.apk" | awk '{print $1}')
echo "Pass B SHA256: $SHA_B"

echo "--> [3/3] Comparing Pass A and Pass B via verify_repro.sh..."
bash "$SCRIPT_DIR/verify_repro.sh" "$TMP_WORK/trove-pass-a.apk" "$TMP_WORK/trove-pass-b.apk"

echo "========================================================="
echo "SUCCESS: Trove release build is 100% VERIFIED REPRODUCIBLE"
echo "Pass A Hash: $SHA_A"
echo "Pass B Hash: $SHA_B"
echo "========================================================="
exit 0
