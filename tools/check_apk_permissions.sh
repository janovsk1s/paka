#!/usr/bin/env bash
set -euo pipefail

EXPECTED_PERMISSIONS="${EXPECTED_PERMISSIONS:-android.permission.CAMERA}"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

find_android_tool() {
  tool="$1"
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] || continue
    [ -d "$sdk/build-tools" ] || continue
    found="$(find "$sdk/build-tools" -path "*/$tool" -type f 2>/dev/null | sort | tail -n 1)"
    if [ -n "$found" ]; then
      printf '%s\n' "$found"
      return 0
    fi
  done
  command -v "$tool" 2>/dev/null || return 1
}

[ "$#" -gt 0 ] || die "usage: $0 path/to/app.apk [path/to/another.apk...]"

AAPT="$(find_android_tool aapt)" || die "aapt not found; set ANDROID_HOME or ANDROID_SDK_ROOT"
expected_permissions="$(printf '%s\n' "$EXPECTED_PERMISSIONS" | tr ',' '\n' | sed '/^$/d' | sort -u)"

for apk in "$@"; do
  [ -f "$apk" ] || die "APK not found: $apk"

  package_name="$("$AAPT" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
  [ -n "$package_name" ] || die "could not read APK package name: $apk"

  actual_permissions="$("$AAPT" dump permissions "$apk" |
    sed -n "s/.*uses-permission: name='\([^']*\)'.*/\1/p" |
    grep -v "^${package_name}\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$" |
    sort -u)"

  if [ "$actual_permissions" != "$expected_permissions" ]; then
    printf 'APK: %s\n' "$apk" >&2
    printf 'expected permissions:\n%s\n' "$expected_permissions" >&2
    printf 'actual permissions:\n%s\n' "$actual_permissions" >&2
    die "permission set mismatch"
  fi

  if printf '%s\n' "$actual_permissions" | grep -Eq '^android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)$'; then
    die "network permission present in $apk"
  fi

  printf 'APK permissions verified: %s\n' "$apk"
  printf 'permissions:\n%s\n' "$actual_permissions"
done
