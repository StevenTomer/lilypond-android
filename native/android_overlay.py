"""Android (NDK) cross-compilation overlay for LilyPond's upstream
release/binaries build scripts.

Upstream's scripts (``release/binaries/lib`` inside every LilyPond source
tarball) know how to build a static LilyPond for Linux, macOS, FreeBSD and
(cross-compiled) mingw.  This module adds an Android target *without
modifying upstream files*: it subclasses ``Config`` the same way upstream's
``MingwConfig`` does and wraps a small number of per-package methods.  The
goal is that a new LilyPond release only needs a version bump here.

Import this module only after upstream's ``lib`` directory is on sys.path.
"""

import functools
import os
import re
import shutil
import sysconfig
from typing import Callable, Dict, List

# Upstream modules (from <lilypond-src>/release/binaries/lib).
from lib import build as up_build
from lib import config as up_config
from lib import dependencies as up_deps

ANDROID_ABIS = {
    # Android ABI name -> (clang/autoconf triple, meson cpu family)
    "arm64-v8a": ("aarch64-linux-android", "aarch64"),
    "x86_64": ("x86_64-linux-android", "x86_64"),
}

OVERLAY_DIR = os.path.dirname(os.path.realpath(__file__))


class AndroidConfig(up_config.Config):
    """Cross-compilation to Android with the NDK, modeled on MingwConfig.

    The platform is reported as LINUX so that upstream's generic (non-mingw,
    non-macOS) code paths are used; Android specifics are keyed off
    ``is_android(c)`` below.
    """

    def __init__(
        self,
        base_dir: str,
        native_config: up_config.Config,
        abi: str,
        ndk: str,
        api: int,
    ):
        triple, cpu_family = ANDROID_ABIS[abi]
        super().__init__(
            base_dir=base_dir,
            downloads_dir=native_config.downloads_dir,
            jobs=native_config.jobs,
            platform=up_config.Platform.LINUX,
            architecture=cpu_family,
            triple=triple,
            native_config=native_config,
        )
        self.android = True
        self.abi = abi
        self.ndk = ndk
        self.api = api
        self.cpu_family = cpu_family

    @property
    def toolchain_bin(self) -> str:
        return os.path.join(
            self.ndk, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin"
        )

    def tool(self, name: str) -> str:
        return os.path.join(self.toolchain_bin, name)

    @property
    def cc(self) -> str:
        return self.tool(f"{self.triple}{self.api}-clang")

    @property
    def cxx(self) -> str:
        return self.tool(f"{self.triple}{self.api}-clang++")

    @property
    def shim_include_dir(self) -> str:
        return os.path.join(OVERLAY_DIR, "shims", "include")

    @property
    def meson_cross_file(self) -> str:
        return os.path.join(self.base_dir, "android_cross.txt")

    def toolchain_env(self) -> Dict[str, str]:
        """Environment for every command run during the Android build."""
        return {
            "CC": self.cc,
            "CXX": self.cxx,
            "AR": self.tool("llvm-ar"),
            "RANLIB": self.tool("llvm-ranlib"),
            "STRIP": self.tool("llvm-strip"),
            "NM": self.tool("llvm-nm"),
            "OBJDUMP": self.tool("llvm-objdump"),
            # Compilers for tools that run on the build machine.
            "CC_FOR_BUILD": "gcc",
            "CXX_FOR_BUILD": "g++",
            "CCAUX": "gcc",
            "BUILD_CC": "gcc",
        }

    def write_meson_cross_file(self):
        pkg_config = os.path.join(up_build.lib_path, "pkg-config-static.sh")
        content = f"""\
[host_machine]
system = 'android'
cpu_family = '{self.cpu_family}'
cpu = '{self.cpu_family}'
endian = 'little'

[binaries]
c = '{self.cc}'
cpp = '{self.cxx}'
ar = '{self.tool("llvm-ar")}'
strip = '{self.tool("llvm-strip")}'
pkg-config = '{pkg_config}'

[properties]
needs_exe_wrapper = true
"""
        with open(self.meson_cross_file, "w", encoding="utf-8") as f:
            f.write(content)

    def create_directories(self):
        super().create_directories()
        self.write_meson_cross_file()

    def __repr__(self) -> str:
        return f"<AndroidConfig {self.abi} API {self.api}>"


def is_android(c: up_config.Config) -> bool:
    return getattr(c, "android", False)


def _wrap(cls, name: str):
    """Decorator: replace cls.name with fn(orig, self, c, *args)."""

    def decorator(fn: Callable):
        orig = getattr(cls, name)

        @functools.wraps(orig)
        def wrapper(self, c, *args, **kwargs):
            return fn(orig, self, c, *args, **kwargs)

        setattr(cls, name, wrapper)
        return fn

    return decorator


# --- Generic build-system hooks -------------------------------------------


@_wrap(up_build.Package, "build_env_extra")
def _package_env(orig, self, c):
    env = orig(self, c)
    if is_android(c):
        # Package-specific values set by upstream win over the toolchain
        # defaults (but not for CC/CXX, which must always be the NDK's).
        merged = c.toolchain_env()
        merged.update(env)
        merged["CC"] = c.cc
        merged["CXX"] = c.cxx
        return merged
    return env


@_wrap(up_build.MesonPackage, "meson_args_static")
def _meson_static(orig, self, c):
    args = orig(self, c)
    if is_android(c):
        # Upstream only adds a cross file for mingw; it is added right
        # before the static arguments, so prepending here is equivalent.
        args = [f"--cross-file={c.meson_cross_file}"] + args
    return args


# --- Per-package adjustments ----------------------------------------------


@_wrap(up_deps.Zlib, "build_env_extra")
def _zlib_env(orig, self, c):
    env = orig(self, c)
    if is_android(c):
        # zlib's configure does not take --host; it uses CHOST.
        env["CHOST"] = c.triple
    return env


@_wrap(up_deps.Libiconv, "enabled")
def _libiconv_enabled(orig, self, c):
    # Bionic's iconv only knows UTF-8/ASCII/UTF-16/UTF-32, not ISO-8859-1.
    # Guile stores narrow strings as Latin-1 and converts them with iconv
    # whenever the locale is not UTF-8, so it aborts at startup without GNU
    # libiconv (Termux's Guile depends on libiconv for the same reason).
    return orig(self, c) or is_android(c)


@_wrap(up_deps.Gettext, "enabled")
def _gettext_enabled(orig, self, c):
    # Bionic has no libintl; GLib needs one and wrap fallbacks are disabled.
    return orig(self, c) or is_android(c)


@_wrap(up_deps.Python, "enabled")
def _python_enabled(orig, self, c):
    # Python is only needed for LilyPond's helper scripts (convert-ly,
    # musicxml2ly, ...), not for the engine.  Added later via CPython's own
    # Android support.
    return orig(self, c) and not is_android(c)


@_wrap(up_deps.Guile, "configure_args")
def _guile_args(orig, self, c):
    args = orig(self, c)
    if is_android(c):
        guile_for_build = self.exe_path(c.native_config)
        if not os.path.exists(guile_for_build):
            raise SystemExit(
                f"Native Guile not found at {guile_for_build}; "
                "build the host dependencies first."
            )
        args += [
            # Guile cannot bootstrap itself when cross-compiling; use the
            # native build of the same version to compile the .go files.
            f"GUILE_FOR_BUILD={guile_for_build}",
            # Autoconf cannot run test programs when cross-compiling; these
            # answers are the ones Termux uses for Bionic.
            "ac_cv_func_posix_spawn=yes",
            "ac_cv_func_posix_spawnp=yes",
            "gl_cv_func_posix_spawn_works=yes",
            "gl_cv_func_posix_spawnp_secure_exec=yes",
            "ac_cv_type_complex_double=no",
            # (Termux uses "=false", but the value is a linker flag; "no" means
            # "not found" and keeps "false" out of guile-3.0.pc.)
            "ac_cv_search_clock_getcpuclockid=no",
            "ac_cv_func_GC_move_disappearing_link=yes",
            "ac_cv_func_GC_is_heap_ptr=yes",
            # Header shims for Bionic: <malloc.h> needs FILE declared, and
            # <time.h> lacks mktime_z()/localtime_rz() below API 35.
            f"CPPFLAGS=-I{c.shim_include_dir}",
        ]
    return args


def setup_android_build(c: AndroidConfig):
    """Sanity checks before starting an Android build."""
    for exe in [c.cc, c.cxx, c.tool("llvm-ar")]:
        if not os.path.exists(exe):
            raise SystemExit(f"NDK tool not found: {exe}")
    if shutil.which("gcc") is None:
        raise SystemExit("A native gcc is required for build-machine tools.")
    # Upstream's Config derives the build triple from the running Python.
    assert sysconfig.get_config_var("HOST_GNU_TYPE")


@_wrap(up_deps.Ghostscript, "configure_args")
def _ghostscript_args(orig, self, c):
    args = orig(self, c)
    if is_android(c):
        # Ghostscript generates arch-config/arch_autoconf.h in the *build*
        # directory when cross-compiling, but its Makefile looks for it next
        # to the sources; point it at the generated file explicitly.
        arch_h = os.path.join(
            self.build_directory(c), "arch-config", "arch_autoconf.h"
        )
        args += [f"--with-arch_h={arch_h}"]
    return args


@_wrap(up_deps.Ghostscript, "apply_patches")
def _ghostscript_slim_romfs(orig, self, c):
    """Leave unused resources out of Ghostscript's built-in ROM file system.

    LilyPond embeds every font it uses in its PostScript output (CJK fonts
    as CFF CIDFonts loaded with `/CIDFont findresource`), so Ghostscript's
    own URW fonts (Resource/Font), its CJK fallback font (CIDFSubst) and
    the CJK CMaps are never needed. Dropping them saves about 6 MB in the
    APK. Only the -dgs-load-fonts option, which loads fonts from disk
    instead of embedding them, would need the Font resources.
    """
    orig(self, c)
    if not is_android(c):
        return

    def patch(content: str) -> str:
        replacements = [
            ("PDF_RESOURCE_LIST=CMap$(D)*", "PDF_RESOURCE_LIST=CMap$(D)Identity-*"),
            ("PS_RESOURCE_LIST=SubstCID$(D)* CIDFSubst$(D)* ", "PS_RESOURCE_LIST=SubstCID$(D)* "),
            ("PS_FONT_RESOURCE_LIST=-B -b Font$(D)*", "PS_FONT_RESOURCE_LIST="),
        ]
        for old, new in replacements:
            if old not in content:
                raise RuntimeError(f"psromfs.mak changed upstream; update the overlay: {old}")
            content = content.replace(old, new)
        return content

    self.patch_file(c, "psi/psromfs.mak", patch)


# --- Host (native) build fixes for newer distributions ---------------------
# Upstream builds its Linux binaries on AlmaLinux 8.  On glibc >= 2.43 with
# GCC >= 15, glibc defines the C23 const-preserving functions (bsearch,
# strchr, wmemchr, ...) as function-like _Generic macros, and the gnulib
# wrappers bundled in libunistring and Guile then fail on their own
# redeclarations ("expected identifier before '_Generic'").  Parenthesizing
# the declared name suppresses macro expansion and is valid everywhere.


_GL_DECL = re.compile(r"^(_GL_EXTERN_C\b[^(;]*?[\s*])([A-Za-z_]\w*) \(", re.M)


def _fix_gnulib_c23(orig, self, c):
    orig(self, c)

    def patch(content: str) -> str:
        return _GL_DECL.sub(r"\1(\2) (", content)

    for rel in ("lib/stdlib.in.h", "lib/string.in.h", "lib/wchar.in.h"):
        if os.path.exists(os.path.join(self.src_directory(c), rel)):
            self.patch_file(c, rel, patch)


for _cls in (up_deps.Libunistring, up_deps.Guile):
    _wrap(_cls, "apply_patches")(_fix_gnulib_c23)


# --- LilyPond itself -------------------------------------------------------

from lib import lilypond as up_lily  # noqa: E402  pylint: disable=wrong-import-position


@_wrap(up_lily.LilyPond, "dependencies")
def _lilypond_deps(orig, self, c):
    if not is_android(c):
        return orig(self, c)
    # Same as upstream minus Python (upstream asserts that some Python is
    # bundled; on Android the engine runs without it).
    deps = [
        up_deps.freetype,
        up_deps.fontconfig,
        up_deps.ghostscript,
        up_deps.glib,
        up_deps.guile,
        up_deps.pango,
        up_deps.libpng,
        up_deps.cairo,
    ]
    if up_deps.gettext.enabled(c):
        deps += [up_deps.gettext]
    return deps


@_wrap(up_lily.LilyPond, "configure_args")
def _lilypond_args(orig, self, c):
    args = orig(self, c)
    if is_android(c):
        args += [f"--with-flexlexer-dir={self.flexlexer_directory(c)}"]
    return args


@_wrap(up_lily.LilyPond, "build")
def _lilypond_build(orig, self, c):
    if is_android(c):
        # The cross compiler does not search /usr/include (same as mingw).
        flexlexer_dir = self.flexlexer_directory(c)
        os.makedirs(flexlexer_dir, exist_ok=True)
        shutil.copy("/usr/include/FlexLexer.h", flexlexer_dir)
    return orig(self, c)


# --- Packaging for the APK -------------------------------------------------

import hashlib  # noqa: E402  pylint: disable=wrong-import-position
import subprocess  # noqa: E402  pylint: disable=wrong-import-position
import lzma  # noqa: E402  pylint: disable=wrong-import-position
import tarfile  # noqa: E402  pylint: disable=wrong-import-position


class AndroidPackager(up_lily.LilyPondPackager):
    """Stage upstream's binary package layout, then split it for an APK:

    out/jniLibs/<abi>/liblilypond.so, libgs.so   (executables)
    out/assets/lilypond-runtime.zip              (everything else)
    out/assets/lilypond-runtime.version
    """

    def __init__(self, lilypond, c: AndroidConfig, out_dir: str):
        super().__init__(lilypond, c)
        self.out_dir = out_dir

    def _strip_to(self, src: str, dst: str):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(src, dst)
        subprocess.run([self.c.tool("llvm-strip"), dst], check=True)

    def prepare_package(self):
        c = self.c
        if os.path.exists(self.package_dir):
            shutil.rmtree(self.package_dir)
        shutil.copytree(self.lilypond.install_directory(c), self.package_dir)

        jni = os.path.join(self.out_dir, "jniLibs", c.abi)
        self._strip_to(os.path.join(self.bin_dir, "lilypond"),
                       os.path.join(jni, "liblilypond.so"))
        self._strip_to(up_deps.ghostscript.exe_path(c), os.path.join(jni, "libgs.so"))
        # bin/ only holds the executable and Python scripts; the app creates
        # bin/lilypond and libexec/gs as symlinks into nativeLibraryDir.
        shutil.rmtree(self.bin_dir)

        self._copy_guile_files()
        self._copy_fontconfig_files()
        self._copy_font_files()
        self._copy_relocation_files()
        if not os.path.isdir(os.path.join(self.package_dir, "lib", "lilypond")):
            # LilyPond's bytecode is compiled by running LilyPond, which the
            # cross build cannot do; use the native build's (as for mingw).
            self._copy_native_bytecode()
        self._copy_license_files()

        # Drop what the engine does not need at runtime.
        for rel in ("share/man", "share/info", "share/doc", "share/emacs",
                    "share/vim", "share/locale", "include"):
            path = os.path.join(self.package_dir, rel)
            if os.path.exists(path):
                shutil.rmtree(path)

    def package_apk(self) -> str:
        """Write assets/lilypond-runtime.tar.xz plus its version stamp.

        xz is about 40% smaller than zip's deflate for this content (mostly
        Guile bytecode). The archive is a plain ustar file with fixed
        metadata, so identical inputs give an identical archive.
        """
        self.prepare_package()
        assets = os.path.join(self.out_dir, "assets")
        os.makedirs(assets, exist_ok=True)
        for old in ("lilypond-runtime.zip",):
            if os.path.exists(os.path.join(assets, old)):
                os.remove(os.path.join(assets, old))
        archive = os.path.join(assets, "lilypond-runtime.tar.xz")
        names = sorted(os.listdir(self.package_dir))
        # lib/ (bytecode) last, so .go files are never older than sources.
        names = [n for n in names if n != "lib"] + ["lib"]
        digest = hashlib.sha256()
        with lzma.open(archive, "wb", preset=9 | lzma.PRESET_EXTREME) as xz, \
                tarfile.open(fileobj=xz, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            for top in names:
                for root, dirs, files in os.walk(os.path.join(self.package_dir, top)):
                    dirs.sort()
                    for name in sorted(files):
                        path = os.path.join(root, name)
                        if os.path.islink(path):
                            continue
                        arc = os.path.relpath(path, self.package_dir)
                        info = tar.gettarinfo(path, arc)
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        info.mtime = 946684800  # 2000-01-01; the app resets times
                        info.mode = 0o755 if os.access(path, os.X_OK) else 0o644
                        with open(path, "rb") as f:
                            tar.addfile(info, f)
                        digest.update(arc.encode())
                        with open(path, "rb") as f:
                            digest.update(f.read())
        version = f"{self.lilypond.version}-{digest.hexdigest()[:12]}"
        with open(os.path.join(assets, "lilypond-runtime.version"), "w") as f:
            f.write(version + "\n")
        return version


@_wrap(up_lily.LilyPond, "build_env_extra")
def _lilypond_env(orig, self, c):
    env = orig(self, c)
    if is_android(c):
        # Upstream's --enable-static-gxx targets GCC's libstdc++.a; with the
        # NDK's clang the C++ runtime is libc++, linked statically like this
        # (otherwise the executable needs libc++_shared.so at runtime).
        env["LDFLAGS"] = (env.get("LDFLAGS", "") + " -static-libstdc++").strip()
    return env
