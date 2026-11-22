package org.lilyengine.engine

/** A LilyPond error or warning tied to a position in the input file. */
data class Diagnostic(
    val severity: Severity,
    /** 1-based. */
    val line: Int,
    /** 1-based, as LilyPond prints it (the score map's `char` is 0-based). */
    val column: Int,
    val message: String,
) {
    enum class Severity { ERROR, WARNING, PROGRAMMING_ERROR }

    companion object {
        // e.g. "/path/score.ly:3:10: error: syntax error, unexpected ..."
        private val PATTERN = Regex(
            """^(.+?):(\d+):(\d+): (error|warning|programming error): (.*)$""",
        )

        /** Extracts diagnostics for [inputPath] from LilyPond's log output. */
        fun parse(log: String, inputPath: String): List<Diagnostic> =
            log.lineSequence().mapNotNull { line ->
                val m = PATTERN.matchEntire(line) ?: return@mapNotNull null
                val (file, ln, col, sev, msg) = m.destructured
                if (file != inputPath && !inputPath.endsWith("/$file")) return@mapNotNull null
                Diagnostic(
                    when (sev) {
                        "error" -> Severity.ERROR
                        "warning" -> Severity.WARNING
                        else -> Severity.PROGRAMMING_ERROR
                    },
                    ln.toInt(),
                    col.toInt(),
                    msg,
                )
            }.toList()
    }
}
