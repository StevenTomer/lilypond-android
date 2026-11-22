package org.lilyengine.scoreview

import org.lilyengine.engine.ScoreMap
import org.lilyengine.engine.ScoreMap.Grob
import kotlin.math.max

/**
 * Point-and-click in both directions, without any UI: from a point on a page
 * to the printed object under it, and between objects and source positions.
 *
 * Page points here are PDF points measured from the page's top-left corner,
 * y growing downwards (the way a view lays pages out). The score map uses
 * staff spaces with y growing upwards; [toStaffSpaces] and [pageRect] convert.
 */
class ScoreLookup(val map: ScoreMap, val book: Int = 1) {
    /** PDF points per staff space. */
    val pointsPerStaffSpace: Double = map.outputScale * 72.0 / 25.4

    private val byPage: Map<Int, List<Grob>> = map.grobs.filter { it.book == book }.groupBy { it.page }

    /** Every object that came from some place in the source (a note, rest, dynamic…), by origin. */
    private val byOrigin: Map<ScoreMap.Location, List<Grob>> =
        map.grobs.filter { it.book == book && it.origin != null }.groupBy { it.origin!! }

    fun grobsOnPage(page: Int): List<Grob> = byPage[page].orEmpty()

    /** A grob's box in page points: left, top, right, bottom. */
    fun pageRect(grob: Grob): FloatArray {
        val k = pointsPerStaffSpace
        val (x1, y1, x2, y2) = grob.bbox.toList()
        return floatArrayOf((x1 * k).toFloat(), (-y2 * k).toFloat(), (x2 * k).toFloat(), (-y1 * k).toFloat())
    }

    fun toStaffSpaces(xPt: Double, yPt: Double): Pair<Double, Double> =
        xPt / pointsPerStaffSpace to -yPt / pointsPerStaffSpace

    /**
     * The selectable object at a point on [page] (1-based), or null. A miss
     * by up to [slop] staff spaces still counts, since note heads are small
     * under a finger. When several objects are in reach, the closest wins;
     * among overlapping ones, note heads and rests beat articulations and
     * slurs, and smaller boxes beat larger ones.
     */
    fun objectAt(page: Int, xPt: Double, yPt: Double, slop: Double = 0.6): Grob? {
        val (x, y) = toStaffSpaces(xPt, yPt)
        return grobsOnPage(page)
            .filter { it.origin != null && it.type in SELECTABLE }
            .map { it to distance(it, x, y) }
            .filter { it.second <= slop }
            .minWithOrNull(
                compareBy<Pair<Grob, Double>> { it.second }
                    .thenBy { SELECTABLE.indexOf(it.first.type) }
                    .thenBy { area(it.first) },
            )?.first
    }

    /**
     * The one of [candidates] on [page] closest to a point, if it is within
     * [slop] staff spaces: for grabbing an already selected note with a
     * finger, which covers more than the note head.
     */
    fun nearestOf(candidates: Collection<Grob>, page: Int, xPt: Double, yPt: Double, slop: Double): Grob? {
        val (x, y) = toStaffSpaces(xPt, yPt)
        return candidates.filter { it.page == page && it.book == book }
            .map { it to distance(it, x, y) }
            .filter { it.second <= slop }
            .minByOrNull { it.second }?.first
    }

    /** All printed objects made by the same piece of source (a note's head, accidental, dots). */
    fun sameOrigin(grob: Grob): List<Grob> = grob.origin?.let { byOrigin[it] }.orEmpty()

    /**
     * Objects for a text cursor at [line] (1-based) and [char] (0-based, in
     * characters): those made by the last source item that starts on that
     * line at or before the cursor. Placing the cursor anywhere inside
     * `fis'8` finds the F sharp. Only origins in [file] count, when given
     * (compared by file name, since LilyPond may report a relative or
     * absolute path).
     */
    fun objectsAtSource(line: Int, char: Int, file: String? = null): List<Grob> {
        val fileName = file?.substringAfterLast('/')
        val origin = byOrigin.keys
            .filter { it.line == line && it.char <= char && (fileName == null || it.file.substringAfterLast('/') == fileName) }
            .maxByOrNull { it.char }
            ?: return emptyList()
        return byOrigin[origin].orEmpty()
    }

    /**
     * A stretch of music around [moment] for a fast preview, as start and end
     * moments (rational strings, for [org.lilyengine.engine.LilyPondEngine.Options.window]):
     * from [barsBefore] bars before the bar containing [moment] to [barsAfter]
     * bars after it, aligned to the bar lines LilyPond printed. Past the last
     * bar line the end is open (the engine stops at the end of the music).
     */
    fun windowAround(moment: String, barsBefore: Int = 1, barsAfter: Int = 2): Pair<String, String> {
        val m = rational(moment)
        val bars = (map.grobs.filter { it.type == "BarLine" }.mapNotNull { it.moment } + "0")
            .distinctBy { rational(it) }
            .sortedBy { rational(it) }
        val i = bars.indexOfLast { rational(it) <= m }.coerceAtLeast(0)
        val start = bars[(i - barsBefore).coerceAtLeast(0)]
        val end = bars.getOrNull(i + 1 + barsAfter) ?: OPEN_END
        return start to end
    }

    /** Notes and rests with their start and end, in whole notes, sorted by start. */
    private val timed: List<Triple<Double, Double, Grob>> by lazy {
        map.grobs.filter { it.book == book && (it.type == "NoteHead" || it.type == "Rest") }
            .mapNotNull { g ->
                val start = g.momentValue ?: return@mapNotNull null
                if (g.graceMoment?.let { rational(it) != 0.0 } == true) return@mapNotNull null // grace notes
                Triple(start, start + (g.duration?.let(::durationLength) ?: 0.0), g)
            }
            .sortedBy { it.first }
    }

    /**
     * The notes and rests sounding at [wholeNotes] from the start, in every
     * voice: for a playback cursor. (Positions in the music, not the MIDI
     * file: for music with repeats unfolded only in MIDI they differ.)
     */
    fun soundingAt(wholeNotes: Double): List<Grob> {
        val end = timed.binarySearchBy(wholeNotes + 1e-9) { it.first }.let { if (it < 0) -it - 1 else it }
        // Notes started before; a long one may have started well before, so look back far enough.
        return timed.subList(0, end).asReversed().asSequence()
            .takeWhile { wholeNotes - it.first < MAX_NOTE_LENGTH }
            .filter { wholeNotes < it.second }
            .map { it.third }.toList()
    }

    companion object {
        private const val MAX_NOTE_LENGTH = 8.0 // a maxima

        /**
         * The length in whole notes of a LilyPond duration as
         * ly:duration->string writes it: "4.", "8*2/3", "\breve".
         */
        fun durationLength(s: String): Double? {
            val star = s.indexOf('*')
            val base = if (star < 0) s else s.substring(0, star)
            val dots = base.length - base.trimEnd('.').length
            val log = base.trimEnd('.')
            val value = when (log) {
                "\\breve" -> 2.0
                "\\longa" -> 4.0
                "\\maxima" -> 8.0
                else -> log.toIntOrNull()?.takeIf { it > 0 }?.let { 1.0 / it } ?: return null
            }
            val factor = if (star < 0) 1.0 else rational(s.substring(star + 1))
            return value * (2 - Math.pow(0.5, dots.toDouble())) * factor
        }

        /** Window end meaning "to the end of the music". */
        const val OPEN_END = "1000000"

        private fun rational(s: String): Double {
            val slash = s.indexOf('/')
            return if (slash < 0) s.toDouble() else s.substring(0, slash).toDouble() / s.substring(slash + 1).toDouble()
        }

        /** Grob types a tap can select, in order of preference. */
        val SELECTABLE = listOf(
            "NoteHead", "Rest", "MultiMeasureRest", "Accidental", "Dots",
            "Script", "DynamicText", "Hairpin", "Slur", "PhrasingSlur", "Tie",
            "LyricText", "ChordName", "Clef", "KeySignature", "TimeSignature",
        )

        /** Distance in staff spaces from a point to a grob's box; 0 inside it. */
        internal fun distance(g: Grob, x: Double, y: Double): Double {
            val dx = max(max(g.bbox[0] - x, x - g.bbox[2]), 0.0)
            val dy = max(max(g.bbox[1] - y, y - g.bbox[3]), 0.0)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private fun area(g: Grob) = (g.bbox[2] - g.bbox[0]) * (g.bbox[3] - g.bbox[1])
    }
}

/**
 * Converting between LilyPond source locations (1-based line, 0-based
 * character within the line, counted in Unicode characters as Guile does)
 * and offsets into a Kotlin string (UTF-16 units).
 */
object SourcePositions {
    /** The string offset of [line]:[char], clamped to the text. */
    fun offsetOf(text: String, line: Int, char: Int): Int {
        var start = 0
        repeat(line - 1) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return text.length
            start = nl + 1
        }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        var offset = start
        var n = 0
        while (offset < end && n < char) {
            offset += Character.charCount(text.codePointAt(offset))
            n++
        }
        return offset
    }

    /** The 1-based line and 0-based character of a string offset. */
    fun locationOf(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until o) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return line to text.codePointCount(lineStart, o)
    }

    /**
     * The end of the source item starting at [offset]: a note or rest with
     * its octave marks, duration and dots (`fis''8.`), a command
     * (`\fermata`), or a chord (`<g b d>2`).
     */
    fun tokenEnd(text: String, offset: Int): Int {
        if (offset >= text.length) return text.length
        if (text[offset] == '<') {
            val close = text.indexOf('>', offset)
            if (close > 0) {
                var i = close + 1
                while (i < text.length && !text[i].isWhitespace() && text[i] !in STOP) i++
                return i
            }
        }
        var i = offset
        if (i < text.length && (text[i] == '\\' || text[i] == '-' || text[i] == '^' || text[i] == '_')) i++
        while (i < text.length && !text[i].isWhitespace() && text[i] !in STOP) i++
        return max(i, offset + 1).coerceAtMost(text.length)
    }

    private const val STOP = "{}<>|()[]~\\"
}
