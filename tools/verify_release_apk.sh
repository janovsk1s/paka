#!/usr/bin/env bash
set -euo pipefail

EXPECTED_CERT_SHA256="${EXPECTED_CERT_SHA256:-098fbb0a5455ec00dbafae93f16bff74b048e5bde7a824fac9ecf42effad0019}"
APK="${1:-app/build/outputs/apk/release/app-release.apk}"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

# Accepts one or more candidate tool names (e.g. aapt aapt2) and returns the
# first found, preferring the highest build-tools version (version-ordered).
find_android_tool() {
  for tool in "$@"; do
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
      [ -n "$sdk" ] || continue
      [ -d "$sdk/build-tools" ] || continue
      found="$(find "$sdk/build-tools" -path "*/$tool" -type f 2>/dev/null | sort -V | tail -n 1)"
      if [ -n "$found" ]; then
        printf '%s\n' "$found"
        return 0
      fi
    done
    command -v "$tool" 2>/dev/null && return 0
  done
  return 1
}

[ -f "$APK" ] || die "APK not found: $APK"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi

APKSIGNER="$(find_android_tool apksigner)" || die "apksigner not found; set ANDROID_HOME or ANDROID_SDK_ROOT"

cert_output="$("$APKSIGNER" verify --print-certs "$APK")"

actual_cert="$(printf '%s\n' "$cert_output" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
[ -n "$actual_cert" ] || die "could not read APK signing certificate"

if [ "$actual_cert" != "$EXPECTED_CERT_SHA256" ]; then
  printf 'expected cert: %s\n' "$EXPECTED_CERT_SHA256" >&2
  printf 'actual cert:   %s\n' "$actual_cert" >&2
  die "release certificate mismatch"
fi

# The permission rules live in one place, shared with CI.
"$(dirname "$0")/check_apk_permissions.sh" "$APK"

printf 'APK verified: %s\n' "$APK"
printf 'certificate: %s\n' "$actual_cert"
