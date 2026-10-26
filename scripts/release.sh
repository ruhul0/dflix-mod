#!/usr/bin/env bash
# Builds the signed APK and Play bundle locally and publishes them as the next GitHub release.
# Usage: scripts/release.sh            (release notes = last commit message)
#        scripts/release.sh "notes"    (custom release notes)
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f keystore/keystore.properties ]] || { echo "keystore/keystore.properties missing" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Commit or stash your changes first" >&2; exit 1; }
[[ "$(git branch --show-current)" == main ]] || { echo "Release from main" >&2; exit 1; }

git fetch -q --tags origin
# Next build number = highest v1.0.N tag + 1, so the in-app updater always sees a newer versionCode.
last=$(git tag -l 'v1.0.*' | sed 's/^v1\.0\.//' | sort -n | tail -1)
next=$(( ${last:-0} + 1 ))
tag="v1.0.$next"
echo "Building $tag"

# Parsing tests first: a dflix.live or subtitle-site change shouldn't ship silently.
./gradlew -q testSideloadDebugUnitTest
BUILD_NUMBER=$next ./gradlew -q assembleSideloadRelease bundlePlayRelease

mkdir -p dist
cp app/build/outputs/apk/sideload/release/app-sideload-release.apk dist/dflix-tv.apk
cp app/build/outputs/bundle/playRelease/app-play-release.aab dist/dflix-tv-play.aab

git push -q origin main
# Fixed asset names keep .../releases/latest/download/dflix-tv.apk stable for manual installs.
gh release create "$tag" dist/dflix-tv.apk dist/dflix-tv-play.aab \
  --title "DFLIX TV $tag" \
  --notes "${1:-$(git log -1 --pretty=%B)}" \
  --target "$(git rev-parse HEAD)"

echo "Released $tag. Play bundle: dist/dflix-tv-play.aab"
