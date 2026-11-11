#!/bin/sh
# Builds the release APK of a commit from a fresh clone in a temporary directory and prints its
# SHA-256; given an APK, says whether the two are the same bytes.
#
#   scripts/reproduce.sh [-r <ref>] [-u <api base url>] [<unsigned apk to compare with>]
#
# -r: the tag or commit to build (default: HEAD). -u: the server the release talks to, if the
# build being compared was given one (its release notes say). The clone is of this checkout,
# so the ref must exist here. Needs a JDK to start Gradle and the Android SDK (ANDROID_HOME, or
# this checkout's local.properties).
set -eu

ref=HEAD
url=
while getopts r:u: o; do
    case $o in
        r) ref=$OPTARG ;;
        u) url=$OPTARG ;;
        *) echo "usage: $0 [-r ref] [-u api-base-url] [apk]" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
other=${1:-}

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git clone -q "$root" "$work/src"
git -C "$work/src" checkout -q --detach "$(git -C "$root" rev-parse "$ref^{commit}")"
[ -f "$root/local.properties" ] && cp "$root/local.properties" "$work/src/"

# A clean tree and no build cache: what a build leaves behind can change the next one's bytes.
(cd "$work/src" && ./gradlew --no-daemon --no-build-cache -q ${url:+-Psefaria.apiBaseUrl="$url"} :app:assembleRelease)

apk=$work/src/app/build/outputs/apk/release/app-release-unsigned.apk
sum() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
mine=$(sum "$apk")
echo "$(git -C "$work/src" rev-parse HEAD)  $mine"

if [ -n "$other" ]; then
    theirs=$(sum "$other")
    if [ "$mine" = "$theirs" ]; then
        echo "reproduced: $other is this build"
    else
        echo "DIFFERENT: $other is $theirs" >&2
        cp "$apk" ./reproduced-unsigned.apk
        echo "this build is kept as ./reproduced-unsigned.apk to compare (diffoscope)" >&2
        exit 1
    fi
fi
