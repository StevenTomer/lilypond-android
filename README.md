# lilypond-android

The real [GNU LilyPond](https://lilypond.org) engine (2.26, Guile 3) running natively
inside an Android APK, with no Termux and no server. This repository is the foundation for
a pen-first tablet LilyPond editor. Right now it contains:

- `native/`: builds LilyPond and all its dependencies with the Android NDK.
- `engine/`: an Android library (`LilyPondEngine`) that unpacks the runtime and runs LilyPond.
- `harness/`: a throwaway test app (edit, render, view page 1, read the log) plus instrumented tests.

Where this is heading: [docs/ROADMAP.md](docs/ROADMAP.md).

## How it works

**Build.** Every LilyPond source tarball ships upstream's own static-binary build scripts
(`release/binaries/lib`). `native/android_overlay.py` adds an Android target to them,
modeled on upstream's mingw cross-compilation. It subclasses `Config` and wraps a few
per-package methods, and **it never modifies upstream files**. Dependency versions
therefore come from whichever LilyPond release `native/build.py` points at.

**Runtime.** LilyPond runs as a child process. It cannot run in-process: it keeps
global state, calls `exit()`, and Guile's GC owns the whole process.

- The executables ship as `jniLibs/<abi>/liblilypond.so` and `libgs.so` (Ghostscript).
  Android installs them into `nativeLibraryDir`, the only place an app may `exec()` from
  on Android 10 and later.
- Everything else ships as `assets/lilypond-runtime.tar.xz`: Scheme and `.ly` files,
  Guile's library and bytecode, LilyPond's bytecode, and fonts. This is upstream's binary
  package layout.
- On first use (about 4 s) the engine unpacks the archive into `filesDir/lilypond/<version>/` and creates
  two symlinks: `bin/lilypond` → `liblilypond.so` and `libexec/gs` → `libgs.so`.
  LilyPond derives its data prefix from `argv[0]`, so upstream's own relocation files
  then set up Guile, Fontconfig and `PATH`.

## Building

Requirements:

- Ubuntu with the packages from upstream's playbook (`release/binaries/ansible`):
  `bison flex fontforge gcc g++ gettext gperf m4 make meson ninja-build pkgconf
  python3-packaging t1utils texlive-base texlive-metapost`.
- Android NDK r30. The default path is `~/Android/Sdk/ndk/30.0.16248370`; override it
  with `--ndk` or `ANDROID_NDK`.

```sh
cd native
./build.py host-deps                    # native deps (Guile bootstraps itself: slow, once)
./build.py host-lilypond                # native LilyPond: bytecode + fonts for the cross build
./build.py deps     --abi arm64-v8a     # cross-compile dependencies
./build.py lilypond --abi arm64-v8a     # cross-compile LilyPond
./build.py package  --abi arm64-v8a     # -> out/jniLibs/<abi>/, out/assets/
# repeat the last three with --abi x86_64 for the emulator
cd .. && ./gradlew :harness:installDebug         # or :harness:connectedDebugAndroidTest
```

`native/ci-build.sh [version]` runs all of the native steps above for both ABIs.

### CI

`.github/workflows/build.yml` runs on GitHub Actions:

- **native:** the full native build, about 2–4 hours on a standard runner. Its output
  (`native/out`) is cached under a key made from the LilyPond version, the NDK version and
  a hash of `native/build.py`, `android_overlay.py`, `shims/` and `ci-build.sh`. Commits
  that only change the app reuse it.
- **app:** unit tests, lint, and the arm64 release APK as a downloadable artifact. The APK
  is signed if the `LILYENGINE_*` repository secrets are set; the workflow file lists them.
- **device-test:** the instrumented tests on x86_64 emulators running API 30 (Android 11)
  and API 35.
- **Mondays:** a scheduled run builds the newest LilyPond release, stable or development
  (`native/tools/latest-lilypond.py`). An upstream change that breaks the Android build
  therefore shows up as a failed run.
- **Manual runs** take any version (`lilypond_version`, or `latest`).

Device checks that run without the app:

- `native/tools/device-test-guile.sh [abi]`: runs Guile on a device.
- `native/tools/device-test-lilypond.sh [abi] [file.ly]`: renders a score in
  `/data/local/tmp` and pulls the PDF and PNG back.

Release APKs: `./gradlew :harness:assembleRelease -Plilyengine.abis=arm64-v8a` builds a
single-ABI APK (about 22 MB), signed if `~/.android-keys/lilyengine-release.properties`
exists.

### Updating LilyPond

1. Bump `LILYPOND_VERSION` in `native/build.py`.
2. Delete `native/build/`.
3. Rebuild.

To try a release without touching the default build, pass `--lilypond-version X.Y.Z
--build-dir <dir> --out <dir>` to every `build.py` command, and build the APK with
`-Plilyengine.nativeOut=<dir>`.

Only `android_overlay.py` is ours to maintain.

## Android-specific fixes (all in `native/android_overlay.py` and `native/shims/`)

| Problem | Fix |
|---|---|
| Guile cannot bootstrap when cross-compiling | `GUILE_FOR_BUILD` = the host Guile of the same version (as for mingw) |
| Autoconf cannot run test programs for Bionic | cache answers for posix_spawn, complex, GC functions (from Termux) |
| Bionic `<malloc.h>` uses `FILE` undeclared under gnulib | `shims/include/malloc.h` (from Termux) |
| NDK ≥ r26 declares `timezone_t`, but `mktime_z`/`localtime_rz` only exist from API 35 | `shims/include/time.h` maps them onto `mktime`/`timegm`/`localtime_r`/`gmtime_r` |
| Termux's `ac_cv_search_clock_getcpuclockid=false` puts `false` into `guile-3.0.pc` | use `=no` |
| Bionic iconv lacks ISO-8859-1, which Guile uses for narrow strings → abort at boot | enable upstream's GNU libiconv recipe (as for macOS/mingw) |
| Bionic has no libintl, and GLib needs one | enable upstream's gettext recipe |
| Ghostscript looks for its generated `arch.h` in the source directory when cross-building out-of-tree | `--with-arch_h=<build>/arch-config/arch_autoconf.h` |
| `--enable-static-gxx` assumes GCC's libstdc++ | add `-static-libstdc++` (libc++) to LilyPond's link |
| Cross compiler doesn't see `/usr/include/FlexLexer.h` | copy it (as upstream does for mingw) |
| Python is not needed by the engine | disabled for Android (CPython has official Android support for later) |
| Host build on glibc ≥ 2.43 + GCC 15: gnulib redeclares C23 `_Generic` functions | parenthesize declared names in gnulib's headers |

## Size

The arm64 release APK is about 22 MB:

| Part | In APK |
|---|---|
| Runtime data (`.tar.xz`, mostly Guile bytecode; 77 MB unpacked) | 10.0 MB |
| `liblilypond.so` | 6.2 MB |
| `libgs.so` | 4.1 MB |
| App code (R8-shrunk) | 1.8 MB |

What made it smaller (it was 61 MB):

- **R8 shrinking** in release builds: about −23 MB.
- **xz instead of zip** for the runtime: −7 MB.
- **A slimmer Ghostscript:** about −8 MB. LilyPond embeds every font in its PostScript,
  so Ghostscript's built-in URW fonts, CJK fallback font and CJK CMaps are left out of
  its ROM file system (`_ghostscript_slim_romfs`). The one thing this breaks is
  `-dgs-load-fonts`.

Not done:

- **Stripping debug info from Guile's `.go` files.** It's only about 2 MB of 60 MB.
- **Pruning Guile modules.** User scores may `use-modules` anything.

## Licensing

LilyPond is GPLv3+. Ghostscript is AGPLv3. The APK as a whole is therefore distributed
under the AGPLv3, and the license files of every bundled component are included in the
runtime (`licenses/`).
