package org.lilyengine.engine

/**
 * Follows LilyPond's output during a render (feed it [LilyPondEngine.Options.onOutput])
 * and describes how far it has got, e.g. "interpreting music, bar 120" or
 * "fitting music on 34 pages". Thread-safe: [phase] can be read from any thread.
 */
class RenderProgress {
    @Volatile var phase: String = "starting"
        private set

    fun onOutput(line: String) {
        val l = line.trim()
        phase = when {
            l.startsWith("Parsing") -> "reading the input"
            l.startsWith("Interpreting music") -> bars(l) ?: "interpreting music"
            // After a warning, the bar counter goes on at the start of a new line.
            l.startsWith("[") && phase.startsWith("interpreting music") -> bars(l) ?: return
            l.startsWith("Preprocessing graphical objects") -> "placing objects"
            l.startsWith("Finding the ideal number of pages") -> "choosing page breaks"
            l.startsWith("Fitting music on") -> PAGES.find(l)?.let { "fitting music on ${it.groupValues[1]} pages" } ?: "fitting music on pages"
            l.startsWith("Drawing systems") -> "drawing systems"
            l.startsWith("Converting to") || l.startsWith("Layout output to") -> "writing the PDF"
            l.startsWith("MIDI output to") -> "writing MIDI"
            else -> return
        }
    }

    private fun bars(line: String): String? =
        BAR.findAll(line).lastOrNull()?.let { "interpreting music, bar ${it.groupValues[1]}" }

    private companion object {
        val BAR = Regex("""\[(\d+)]""")
        val PAGES = Regex("""on (\d+(?: or \d+)?) pages""")
    }
}
