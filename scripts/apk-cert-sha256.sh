#!/usr/bin/env bash
# Prints the SHA-256 digest of the certificate an APK is signed with.
# Fails unless the APK verifies and every signer entry uses the same certificate.
#
# Usage: scripts/apk-cert-sha256.sh path/to/app.apk
set -euo pipefail

apk="$1"
apksigner=$(find "${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools" -name apksigner -type f | sort -V | tail -n 1)

output=$("$apksigner" verify --print-certs "$apk")

# The line's prefix varies between apksigner versions ("Signer #1 ...",
# "Signer (minSdkVersion=..., maxSdkVersion=...) ..."), so match on its end.
digests=$(printf '%s\n' "$output" | sed -n 's/^Signer .*certificate SHA-256 digest: //p' | sort -u)

if [ "$(printf '%s\n' "$digests" | grep -c .)" -ne 1 ]; then
  echo "Expected exactly one signing certificate, got:" >&2
  printf '%s\n' "$output" >&2
  exit 1
fi

printf '%s\n' "$digests"
