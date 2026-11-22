#!/bin/sh
# Phase-1 smoke test: push the cross-compiled Guile to a device/emulator and
# run it from /data/local/tmp.  Usage: tools/device-test-guile.sh [abi]
set -eu
ABI=${1:-x86_64}
HERE=$(cd "$(dirname "$0")/.." && pwd)
INST="$HERE/build/android-$ABI/dependencies/install/guile-3.0.11"
DEST=/data/local/tmp/guile-test
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/bin" "$STAGE/share" "$STAGE/lib/guile"
cp "$INST/bin/guile" "$STAGE/bin/"
cp -a "$INST/share/guile" "$STAGE/share/"
cp -a "$INST/lib/guile/3.0" "$STAGE/lib/guile/"
tar -C "$STAGE" -czf "$STAGE.tgz" .

adb shell rm -rf "$DEST"
adb shell mkdir -p "$DEST"
adb push "$STAGE.tgz" "$DEST/g.tgz" >/dev/null
rm -f "$STAGE.tgz"
adb shell "cd $DEST && tar -xzf g.tgz && rm g.tgz"

ENV="GUILE_LOAD_PATH=$DEST/share/guile/3.0 GUILE_LOAD_COMPILED_PATH=$DEST/lib/guile/3.0/ccache GUILE_AUTO_COMPILE=0 HOME=$DEST"
adb shell "$ENV $DEST/bin/guile -c '(display (list (version) %host-type (* 1.5 2) (expt 2 100))) (newline)'"
adb shell "$ENV $DEST/bin/guile -c '(use-modules (ice-9 format) (srfi srfi-1)) (format #t \"~a primes: ~a~%\" (quote sieve) (filter (lambda (n) (not (any (lambda (d) (zero? (remainder n d))) (iota (- n 2) 2)))) (iota 30 2)))'"
