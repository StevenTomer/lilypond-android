#!/usr/bin/env python3
"""Build LilyPond's dependencies (and later LilyPond) for Android.

Uses the upstream build scripts shipped inside the LilyPond source tarball
(release/binaries/lib) plus android_overlay.py.  Usage:

  ./build.py host-deps                 # native build, needed for bootstrapping
  ./build.py deps --abi arm64-v8a      # cross-compile dependencies
  ./build.py deps --abi x86_64
  ./build.py host-lilypond             # native LilyPond (bytecode, fonts)
  ./build.py lilypond --abi x86_64     # cross-compile LilyPond
  ./build.py package --abi x86_64      # -> out/jniLibs/<abi>, out/assets

Bumping LILYPOND_VERSION (or passing --lilypond-version) picks up upstream's
new dependency versions.  --build-dir and --out keep builds of different
versions apart.
"""

import argparse
import logging
import os
import sys
import tarfile
import urllib.request

LILYPOND_VERSION = "2.26.0"

HERE = os.path.dirname(os.path.realpath(__file__))
DOWNLOADS = os.path.join(HERE, "downloads")
SRC = os.path.join(HERE, "src")
BUILD = os.path.join(HERE, "build")
OUT = os.path.join(HERE, "out")
DEFAULT_NDK = os.path.expanduser("~/Android/Sdk/ndk/30.0.16248370")
DEFAULT_API = 28


def lilypond_tarball() -> str:
    os.makedirs(DOWNLOADS, exist_ok=True)
    name = f"lilypond-{LILYPOND_VERSION}.tar.gz"
    path = os.path.join(DOWNLOADS, name)
    if not os.path.exists(path):
        series = ".".join(LILYPOND_VERSION.split(".")[:2])
        url = f"https://lilypond.org/download/source/v{series}/{name}"
        logging.info("Downloading %s", url)
        urllib.request.urlretrieve(url, path)
    return path


def lilypond_source() -> str:
    src = os.path.join(SRC, f"lilypond-{LILYPOND_VERSION}")
    if not os.path.isdir(src):
        os.makedirs(SRC, exist_ok=True)
        with tarfile.open(lilypond_tarball()) as tar:
            tar.extractall(SRC, filter="data")
    return src


def import_upstream():
    sys.path.insert(0, os.path.join(lilypond_source(), "release", "binaries"))
    import lib  # pylint: disable=import-outside-toplevel

    return lib


def host_config(lib, jobs):
    base = os.path.join(BUILD, "host")
    os.makedirs(base, exist_ok=True)
    return lib.Config(base, jobs=jobs)


def android_config(lib, overlay, args):
    native = host_config(lib, args.jobs)
    base = os.path.join(BUILD, f"android-{args.abi}")
    os.makedirs(base, exist_ok=True)
    return overlay.AndroidConfig(
        base, native_config=native, abi=args.abi, ndk=args.ndk, api=args.api
    )


def build_dependencies(lib, c, only=None) -> bool:
    c.create_directories()
    packages = [p for p in lib.all_dependencies + lib.all_fonts if p.enabled(c)]
    for package in packages:
        package.download(c)
    for package in lib.all_dependencies:
        if not package.enabled(c):
            continue
        if only and str(package).split()[0].lower() not in only:
            continue
        logging.info("Building %s", package)
        if not package.prepare_sources(c):
            return False
        if not package.build(c):
            logging.info("See %s for more information", package.log_path(c))
            return False
    for font in lib.all_fonts:
        if not font.prepare_sources(c):
            return False
        font.build(c)
    return True


def build_lilypond(lib, c) -> bool:
    lilypond = lib.LilyPond(lilypond_tarball())
    logging.info("Building %s for %s", lilypond, c)
    if not lilypond.prepare_sources(c):
        return False
    if not lilypond.build(c):
        logging.info("See %s for more information", lilypond.log_path(c))
        return False
    return True


def main():
    global LILYPOND_VERSION, BUILD, OUT  # pylint: disable=global-statement
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "command", choices=["host-deps", "deps", "host-lilypond", "lilypond", "package"]
    )
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK", DEFAULT_NDK))
    parser.add_argument("--api", type=int, default=DEFAULT_API)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 1)
    parser.add_argument(
        "--only",
        help="comma-separated package names to (re)build, e.g. guile,bdwgc",
    )
    parser.add_argument(
        "--lilypond-version",
        default=os.environ.get("LILYPOND_VERSION", LILYPOND_VERSION),
        help=f"LilyPond release to build (default {LILYPOND_VERSION})",
    )
    parser.add_argument("--build-dir", help="build directory (default native/build)")
    parser.add_argument("--out", help="package output directory (default native/out)")
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    LILYPOND_VERSION = args.lilypond_version
    if args.build_dir:
        BUILD = os.path.abspath(args.build_dir)
    if args.out:
        OUT = os.path.abspath(args.out)

    logging.basicConfig(
        format="%(message)s", level=logging.DEBUG if args.debug else logging.INFO
    )

    lib = import_upstream()
    import android_overlay as overlay  # pylint: disable=import-outside-toplevel
    only = set(args.only.lower().split(",")) if args.only else None

    if args.command == "host-deps":
        ok = build_dependencies(lib, host_config(lib, args.jobs), only)
    elif args.command == "host-lilypond":
        ok = build_lilypond(lib, host_config(lib, args.jobs))
    else:
        c = android_config(lib, overlay, args)
        overlay.setup_android_build(c)
        if args.command == "deps":
            ok = build_dependencies(lib, c, only)
        elif args.command == "lilypond":
            ok = build_lilypond(lib, c)
        else:
            packager = overlay.AndroidPackager(
                lib.LilyPond(lilypond_tarball()), c, OUT
            )
            logging.info("Packaged runtime %s", packager.package_apk())
            ok = True

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
