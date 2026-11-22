#!/bin/sh
# Phase-2 test: recreate the app's runtime layout under /data/local/tmp and
# render a score with adb shell.  Usage: tools/device-test-lilypond.sh [abi] [file.ly]
set -u
ABI=${1:-x86_64}
HERE=$(cd "$(dirname "$0")/.." && pwd)
LY=${2:-$HERE/tools/samples/test.ly}
OUT=$HERE/out
D=/data/local/tmp/lily
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/root" && tar -xJf "$OUT/assets/lilypond-runtime.tar.xz" -C "$STAGE/root"
mkdir -p "$STAGE/root/bin" "$STAGE/root/libexec"
cp "$OUT/jniLibs/$ABI/liblilypond.so" "$STAGE/root/bin/lilypond"
cp "$OUT/jniLibs/$ABI/libgs.so" "$STAGE/root/libexec/gs"
cp "$LY" "$STAGE/root/test.ly"
tar -C "$STAGE/root" -czf "$STAGE/r.tgz" .

adb shell rm -rf "$D" && adb shell mkdir -p "$D"
adb push "$STAGE/r.tgz" "$D/r.tgz" >/dev/null
adb shell "cd $D && tar -xzf r.tgz && rm r.tgz && chmod 755 bin/lilypond libexec/gs"
# Same environment the app sets (see RuntimeInstaller.environment).
ENV="HOME=$D TMPDIR=$D XDG_CACHE_HOME=$D/cache LANG=C.UTF-8 GUILE_AUTO_COMPILE=0"
[ $# -ge 2 ] && shift 2 || shift $#
adb shell "cd $D && time env $ENV bin/lilypond $* --pdf --png -dresolution=110 test.ly; echo exit=\$?"
adb pull "$D/test.pdf" "$HERE/build/device-test.pdf" >/dev/null && echo "pulled build/device-test.pdf"
adb pull "$D/test.png" "$HERE/build/device-test.png" >/dev/null 2>&1 && echo "pulled build/device-test.png" || true
