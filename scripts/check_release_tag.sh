#!/usr/bin/env bash
# Exits 0 only if the given tag is "v" followed by the VERSION_NAME in gradle.properties, so a
# release tag cannot publish artifacts under a different version.
#
#   scripts/check_release_tag.sh "$GITHUB_REF_NAME"
set -euo pipefail

tag="${1-}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(sed -n 's/^VERSION_NAME=//p' "$root/gradle.properties")"

if [[ -z "$version" ]]; then
    echo "check_release_tag.sh: gradle.properties has no VERSION_NAME" >&2
    exit 2
fi
if [[ "$tag" != "v$version" ]]; then
    echo "check_release_tag.sh: tag '$tag' does not match VERSION_NAME $version (expected v$version)" >&2
    exit 1
fi
echo "check_release_tag.sh: tag $tag publishes version $version"
