package org.lilyengine.engine

import android.content.Context
import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream

/**
 * Unpacks the LilyPond runtime (assets/lilypond-runtime.tar.xz) into
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
        if (stamp.exists()) {
            // nativeLibraryDir moves to a new random path whenever the app is
            // updated or reinstalled, so re-point the symlinks if needed.
            linkExecutables(root)
            return root
        }

        // Remove older runtimes and any half-finished install.
        base.listFiles()?.forEach { it.deleteRecursively() }
        val tmp = File(base, "$version.partial").apply { mkdirs() }
        untar(tmp)
        fixBytecodeTimestamps(tmp)
        linkExecutables(tmp)
        writeFontconfig(tmp)
        check(tmp.renameTo(root)) { "Could not move runtime into place: $root" }
        // The fonts.conf written above embeds the path, so rewrite after the move.
        writeFontconfig(root)
        stamp.writeText(version)
        return root
    }

    /** Extracts the ustar archive written by native/android_overlay.py. */
    private fun untar(dest: File) {
        val canonicalDest = dest.canonicalPath + File.separator
        val header = ByteArray(512)
        XZInputStream(context.assets.open(RUNTIME_ASSET).buffered(1 shl 16)).buffered(1 shl 16).use { tar ->
            while (true) {
                if (!tar.readFully(header)) break
                if (header.all { it == 0.toByte() }) break // end-of-archive block
                val name = header.field(345, 155).let { prefix ->
                    val base = header.field(0, 100)
                    if (prefix.isEmpty()) base else "$prefix/$base"
                }
                val size = header.field(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val out = File(dest, name)
                require(out.canonicalPath.startsWith(canonicalDest)) { "Bad entry $name" }
                when (type) {
                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyExactly(it, size) }
                        val mode = header.field(100, 8).trim().toInt(8)
                        if (mode and 0b001_000_000 != 0) out.setExecutable(true)
                    }
                    '5' -> out.mkdirs()
                    else -> tar.copyExactly(null, size)
                }
                val pad = (512 - size % 512) % 512
                tar.skipNBytesCompat(pad)
            }
        }
    }

    private fun ByteArray.field(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    /** Fills [buf] completely; false on a clean end of stream. */
    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = read(buf, n, buf.size - n)
            if (r < 0) {
                check(n == 0) { "Truncated runtime archive" }
                return false
            }
            n += r
        }
        return true
    }

    private fun InputStream.copyExactly(out: java.io.OutputStream?, count: Long) {
        val buf = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            check(r >= 0) { "Truncated runtime archive" }
            out?.write(buf, 0, r)
            left -= r
        }
    }

    // InputStream.skipNBytes needs API 33.
    private fun InputStream.skipNBytesCompat(count: Long) = copyExactly(null, count)

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
            val target = "$nativeDir/$lib"
            if (runCatching { Os.readlink(link.path) }.getOrNull() == target) continue
            link.delete()
            Os.symlink(target, link.path)
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
        const val RUNTIME_ASSET = "lilypond-runtime.tar.xz"
        const val VERSION_ASSET = "lilypond-runtime.version"
    }
}
