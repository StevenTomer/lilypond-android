#!/bin/sh
# Full native build: host tools, then both Android ABIs, packaged into
# native/out (or $OUT). Used by CI; runs the same locally.
#
#   ./ci-build.sh               # the version in build.py
#   ./ci-build.sh 2.27.5        # another LilyPond release
#
# Extra build.py options can come from $BUILD_ARGS (e.g. "--build-dir x --out y").
set -eu
cd "$(dirname "$0")"
args=${BUILD_ARGS:-}
[ $# -ge 1 ] && [ -n "$1" ] && args="$args --lilypond-version $1"

step() { echo "::group::$*" 2>/dev/null; echo "== $*"; }
end() { echo "::endgroup::"; }

step host-deps;     ./build.py host-deps $args;     end
step host-lilypond; ./build.py host-lilypond $args; end
for abi in arm64-v8a x86_64; do
    step "deps $abi";     ./build.py deps --abi "$abi" $args;     end
    step "lilypond $abi"; ./build.py lilypond --abi "$abi" $args; end
    step "package $abi";  ./build.py package --abi "$abi" $args;  end
done
