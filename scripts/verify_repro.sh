#!/usr/bin/env bash
set -euo pipefail

APK1="$1"
APK2="$2"

if [ ! -f "$APK1" ] || [ ! -f "$APK2" ]; then
    echo "Usage: $0 <apk1> <apk2>"
    exit 1
fi

tmp_dir=$(mktemp -d)
# Ensure cleanup on exit
trap 'rm -rf "$tmp_dir"' EXIT

generate_hash_list() {
    local apk="$1"
    local target_dir="$2"
    local out_file="$3"
    
    mkdir -p "$target_dir"
    unzip -q "$apk" -d "$target_dir"
    rm -rf "$target_dir/META-INF"
    
    # Count files to prevent trivial passes
    local count
    count=$(find "$target_dir" -type f | wc -l)
    if [ "$count" -eq 0 ]; then
        echo "ERROR: No files found in $apk" >&2
        exit 1
    fi
    
    # Generate hashes and write to the output file
    find "$target_dir" -type f | sort | xargs sha256sum | \
        sed "s|$target_dir/||" > "$out_file"
}

echo "Generating hash list for $APK1..."
generate_hash_list "$APK1" "$tmp_dir/apk1" "$tmp_dir/hash1.txt"

echo "Generating hash list for $APK2..."
generate_hash_list "$APK2" "$tmp_dir/apk2" "$tmp_dir/hash2.txt"

if diff -q "$tmp_dir/hash1.txt" "$tmp_dir/hash2.txt" > /dev/null 2>&1; then
    echo "SUCCESS: APKs are reproducible (excluding signatures)."
    exit 0
else
    echo "FAILURE: APKs differ!"
    # Show diff
    diff "$tmp_dir/hash1.txt" "$tmp_dir/hash2.txt"
    exit 1
fi
