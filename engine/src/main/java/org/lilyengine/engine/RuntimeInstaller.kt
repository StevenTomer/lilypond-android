package org.lilyengine.engine

import android.content.Context
import android.system.Os
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the LilyPond runtime (assets/lilypond-runtime.zip) into
 * filesDir/lilypond/<version>/ using upstream LilyPond's binary package
 * layout:
 *
 *   bin/lilypond      -> symlink to nativeLibraryDir/liblilypond.so
 *   libexec/gs        -> symlink to nativeLibraryDir/libgs.so
 *   share/lilypond/<ver>/, share/guile/, lib/guile/3.0/ccache/,
 *   lib/lilypond/<ver>/ccache/, etc/relocate/, etc/fonts/
 *
 * LilyPond computes its prefix from argv[0], so running bin/lilypond makes
 * its own relocation logic (the .reloc files in etc/relocate) find everything.
 */
internal class RuntimeInstaller(private val context: Context) {
    private val base = File(context.filesDir, "lilypond")

    private fun bundledVersion(): String =
        context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }

    fun ensureInstalled(): File {
        val version = bundledVersion()
        val root = File(base, version)
        val stamp = File(root, ".installed")
        if (stamp.exists()) return root

        // Remove older runtimes and any half-finished install.
        base.listFiles()?.forEach { it.deleteRecursively() }
        val tmp = File(base, "$version.partial").apply { mkdirs() }
        unzip(tmp)
        fixBytecodeTimestamps(tmp)
        linkExecutables(tmp)
        writeFontconfig(tmp)
        check(tmp.renameTo(root)) { "Could not move runtime into place: $root" }
        // The fonts.conf written above embeds the path, so rewrite after the move.
        writeFontconfig(root)
        stamp.writeText(version)
        return root
    }

    private fun unzip(dest: File) {
        val canonicalDest = dest.canonicalPath + File.separator
        ZipInputStream(context.assets.open(RUNTIME_ASSET).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name)
                require(out.canonicalPath.startsWith(canonicalDest)) { "Bad entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                if (entry.time > 0) out.setLastModified(entry.time)
            }
        }
    }

    /**
     * Guile only uses a .go file if it is not older than its .scm source.
     * Give every source a fixed old timestamp and every .go the current time.
     */
    private fun fixBytecodeTimestamps(root: File) {
        val old = 946684800000L // 2000-01-01
        val now = System.currentTimeMillis()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            f.setLastModified(if (f.extension == "go") now else old)
        }
    }

    private fun linkExecutables(root: File) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        for ((dir, name, lib) in listOf(
            Triple("bin", "lilypond", "liblilypond.so"),
            Triple("libexec", "gs", "libgs.so"),
        )) {
            val d = File(root, dir).apply { mkdirs() }
            val link = File(d, name)
            link.delete()
            Os.symlink("$nativeDir/$lib", link.path)
        }
    }

    private fun writeFontconfig(root: File) {
        val dir = File(root, "etc/fonts").apply { mkdirs() }
        val cache = File(context.cacheDir, "fontconfig").path
        // LilyPond registers its own font directory (share/lilypond/<ver>/fonts/otf)
        // with Fontconfig itself, so only the cache location is needed here.
        File(dir, "fonts.conf").writeText(
            """
            <?xml version="1.0"?>
            <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
            <fontconfig>
              <cachedir>$cache</cachedir>
              <config><rescan><int>0</int></rescan></config>
            </fontconfig>
            """.trimIndent() + "\n",
        )
    }

    /** Environment for the LilyPond process (relocation files set the same). */
    fun environment(root: File, nativeDir: File): Map<String, String> {
        val guile = "3.0"
        return mapOf(
            "HOME" to context.filesDir.path,
            "TMPDIR" to context.cacheDir.path,
            "XDG_CACHE_HOME" to context.cacheDir.path,
            "LANG" to "C.UTF-8",
            "PATH" to "${root.path}/libexec:${root.path}/bin:/system/bin",
            "GUILE_LOAD_PATH" to "${root.path}/share/guile/$guile",
            "GUILE_LOAD_COMPILED_PATH" to "${root.path}/lib/guile/$guile/ccache",
            "GUILE_AUTO_COMPILE" to "0",
            "FONTCONFIG_FILE" to "${root.path}/etc/fonts/fonts.conf",
            "FONTCONFIG_PATH" to "${root.path}/etc/fonts",
            "LD_LIBRARY_PATH" to nativeDir.path,
        )
    }

    fun executable(root: File): File = File(root, "bin/lilypond")

    companion object {
        const val RUNTIME_ASSET = "lilypond-runtime.zip"
        const val VERSION_ASSET = "lilypond-runtime.version"
    }
}
