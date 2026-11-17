#!/usr/bin/env bash
# Selects the Xcode CameraK's iOS builds need on a CI runner. Kotlin 2.4.10 is tested with Xcode 26.4,
# and Compose Multiplatform 1.11 links iOS 26 SDK classes (UIViewLayoutRegion) that the macos-15
# image's default Xcode 16.4 lacks, so its iOS test binaries and apps fail to link. The newest 26.x
# the image has wins; a runner with none fails here rather than at the link.
set -euo pipefail
for app in /Applications/Xcode_26.4.1.app /Applications/Xcode_26.4.app /Applications/Xcode_26.3.app; do
  if [ -d "$app" ]; then
    sudo xcode-select -s "$app"
    xcodebuild -version
    exit 0
  fi
done
echo "select_xcode: no Xcode 26.3 or later on this runner" >&2
exit 1
