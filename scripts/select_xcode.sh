#!/usr/bin/env bash
# Selects the Xcode CameraK's iOS builds need on a CI runner. Kotlin 2.4.10 is tested with Xcode 26.4,
# and Compose Multiplatform 1.11 links iOS 26 SDK classes (UIViewLayoutRegion) that the macos-15
# image's default Xcode 16.4 lacks, so its iOS test binaries and apps fail to link. The newest
# Xcode 26.3 or later the image has wins; a runner with none fails here rather than at the link.
set -euo pipefail
newest=""
for app in /Applications/Xcode_2[6-9]*.app; do
  [ -d "$app" ] || continue
  version=${app#/Applications/Xcode_}
  version=${version%.app}
  # 26.0 to 26.2 predate what CameraK's CI has built with.
  case "$version" in 26.0* | 26.1* | 26.2*) continue ;; esac
  if [ -z "$newest" ] || [ "$(printf '%s\n%s\n' "$newest" "$version" | sort -V | tail -1)" = "$version" ]; then
    newest=$version
  fi
done
if [ -z "$newest" ]; then
  echo "select_xcode: no Xcode 26.3 or later on this runner" >&2
  exit 1
fi
sudo xcode-select -s "/Applications/Xcode_$newest.app"
xcodebuild -version
