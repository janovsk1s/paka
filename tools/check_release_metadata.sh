#!/bin/sh
set -eu

tag=${1:-}
build_file=${2:-app/build.gradle.kts}

case "$tag" in
  v*) ;;
  *) echo "Expected a v-prefixed release tag" >&2; exit 1 ;;
esac

version_name=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"/\1/p' "$build_file" | head -n 1)
channel_label=$(sed -nE 's/^[[:space:]]*val releaseChannelLabel = "([^"]*)"/\1/p' "$build_file" | head -n 1)
isolated_suffix=$(sed -nE 's/^[[:space:]]*val isolatedVersionSuffix = "([^"]*)"/\1/p' "$build_file" | head -n 1)

release=${tag#v}
case "$release" in
  *-beta.*)
    expected_version=${release%%-beta.*}
    preview=${release#*-beta.}
    expected_label="capture preview $preview"
    expected_suffix="-photo-beta.$preview"
    [ "$channel_label" = "$expected_label" ] || {
      echo "releaseChannelLabel must be '$expected_label' for $tag (found '$channel_label')" >&2
      exit 1
    }
    [ "$isolated_suffix" = "$expected_suffix" ] || {
      echo "isolatedVersionSuffix must be '$expected_suffix' for $tag (found '$isolated_suffix')" >&2
      exit 1
    }
    ;;
  *)
    expected_version=$release
    [ -z "$channel_label" ] || {
      echo "releaseChannelLabel must be empty for stable $tag (found '$channel_label')" >&2
      exit 1
    }
    # Debug/preview builds must never claim a released preview identity, so a
    # stable tag requires the suffix reset to its development value.
    case "$isolated_suffix" in
      *-development) ;;
      *)
        echo "isolatedVersionSuffix must end in '-development' for stable $tag (found '$isolated_suffix')" >&2
        exit 1
        ;;
    esac
    ;;
esac

[ "$version_name" = "$expected_version" ] || {
  echo "versionName must be '$expected_version' for $tag (found '$version_name')" >&2
  exit 1
}

echo "Release metadata matches $tag"
