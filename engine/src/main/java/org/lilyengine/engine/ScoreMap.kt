package org.lilyengine.engine

import org.json.JSONObject
import java.io.File

/**
 * What LilyPond printed and where, written by lilyengine/scoremap.ly during
 * a render: one [Grob] per printed note head, rest, stem, articulation,
 * bar line, staff symbol, etc., with its page box, source location, Voice
 * and Staff, and musical moment.
 *
 * Coordinates are LilyPond's: staff spaces from the top-left corner of the
 * page, y growing upwards (so y is negative on the page). [toPdfRect]
 * converts to PDF points.
 */
class ScoreMap(
    val paperWidth: Double,
    val paperHeight: Double,
    /** Millimetres per staff space. */
    val outputScale: Double,
    val grobs: List<Grob>,
) {
    data class Location(val file: String, val line: Int, val char: Int, val column: Int)

    data class Pitch(
        /** 0 = c … 6 = b. */
        val notename: Int,
        /** In whole tones: "1/2" is a sharp, "-1/2" a flat. */
        val alteration: String,
        /** 0 is the octave starting at middle C (c'). */
        val octave: Int,
        /** Absolute LilyPond name, e.g. "fis''". */
        val name: String,
    )

    data class Grob(
        /** 1-based book number (a file can produce several PDFs). */
        val book: Int,
        /** 1-based page number within the book's PDF. */
        val page: Int,
        val type: String,
        /** x1, y1, x2, y2 in staff spaces, LilyPond page coordinates. */
        val bbox: DoubleArray,
        /** Voice context instance; distinct for each `\\` voice. */
        val voice: Int?,
        val voiceId: String?,
        val staff: Int?,
        val staffId: String?,
        /** Main part of the moment as a rational string, e.g. "3/2". */
        val moment: String?,
        val graceMoment: String?,
        val origin: Location?,
        val pitch: Pitch?,
        /** LilyPond duration, e.g. "4", "8.". */
        val duration: String?,
        val staffPosition: Int?,
        /** StaffSymbol only: line positions in half staff spaces. */
        val linePositions: List<Double>?,
        val staffSpace: Double?,
        /** KeySignature only: the alteration (in whole tones, 1/2 = sharp) of each step (0 = c) it changes. */
        val keyAlterations: Map<Int, Double>? = null,
    ) {
        val momentValue: Double? get() = moment?.let(::parseRational)
    }

    /** Box in PDF user space (points, y up from the bottom of the page). */
    fun toPdfRect(grob: Grob, pageHeightPt: Double): DoubleArray {
        val k = outputScale * 72.0 / 25.4
        val (x1, y1, x2, y2) = grob.bbox.toList()
        return doubleArrayOf(x1 * k, pageHeightPt + y1 * k, x2 * k, pageHeightPt + y2 * k)
    }

    /** Grobs on one page whose box contains the point (staff spaces). */
    fun hitTest(book: Int, page: Int, x: Double, y: Double, slop: Double = 0.0): List<Grob> =
        grobs.filter {
            it.book == book && it.page == page &&
                x >= it.bbox[0] - slop && x <= it.bbox[2] + slop &&
                y >= it.bbox[1] - slop && y <= it.bbox[3] + slop
        }

    companion object {
        fun parse(json: String): ScoreMap {
            val o = JSONObject(json)
            val arr = o.getJSONArray("grobs")
            val grobs = List(arr.length()) { parseGrob(arr.getJSONObject(it)) }
            return ScoreMap(
                o.getDouble("paperWidth"),
                o.getDouble("paperHeight"),
                o.getDouble("outputScale"),
                grobs,
            )
        }

        fun read(file: File): ScoreMap = parse(file.readText())

        private fun parseGrob(g: JSONObject): Grob {
            val bbox = g.getJSONArray("bbox")
            val moment = g.optJSONObject("moment")
            val origin = g.optJSONObject("origin")
            val pitch = g.optJSONObject("pitch")
            return Grob(
                book = g.getInt("book"),
                page = g.getInt("page"),
                type = g.getString("type"),
                bbox = DoubleArray(4) { bbox.getDouble(it) },
                voice = g.optIntOrNull("voice"),
                voiceId = g.optStringOrNull("voiceId"),
                staff = g.optIntOrNull("staff"),
                staffId = g.optStringOrNull("staffId"),
                moment = moment?.getString("main"),
                graceMoment = moment?.getString("grace"),
                origin = origin?.let {
                    Location(it.getString("file"), it.getInt("line"), it.getInt("char"), it.getInt("column"))
                },
                pitch = pitch?.let {
                    Pitch(it.getInt("notename"), it.getString("alteration"), it.getInt("octave"), it.getString("name"))
                },
                duration = g.optStringOrNull("duration"),
                staffPosition = g.optIntOrNull("staffPosition"),
                linePositions = g.optJSONArray("linePositions")?.let { a ->
                    List(a.length()) { a.getDouble(it) }
                },
                staffSpace = if (g.isNull("staffSpace")) null else g.getDouble("staffSpace"),
                keyAlterations = g.optJSONArray("keyAlterations")?.let { a ->
                    List(a.length()) { a.getJSONObject(it) }
                        .associate { it.getInt("step") to parseRational(it.getString("alteration")) }
                },
            )
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else getDouble(key).toInt()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        internal fun parseRational(s: String): Double {
            val slash = s.indexOf('/')
            return if (slash < 0) s.toDouble()
            else s.substring(0, slash).toDouble() / s.substring(slash + 1).toDouble()
        }
    }
}
