package org.lilyengine.lilytools

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Structural edits on LilyPond text, done by python-ly (lilyedit.py) in an
 * embedded Python. Each edit takes the whole source and gives back the
 * smallest [TextEdit] that turns it into the result, so the rest of the
 * file, its formatting and comments, stays untouched.
 *
 * Edits keep the other music sounding the same: in \relative music, the
 * octave marks of the notes after a changed note are corrected; a new
 * duration doesn't change the next note's.
 *
 * Offsets are Kotlin string offsets (UTF-16).
 */
class LilyTools(context: Context) {
    private val appContext = context.applicationContext
    private var module: PyObject? = null

    /** A pitch in LilyPond's terms: [octave] 0 is the octave from middle C up (c'). */
    data class Pitch(
        /** 0 = c … 6 = b. */
        val step: Int,
        /** In semitones: 1 = sharp, -1 = flat. */
        val semitones: Int,
        val octave: Int,
    )

    class EditException(message: String) : Exception(message)

    /** Starts Python and loads python-ly (about a second the first time). */
    suspend fun start() = withContext(dispatcher) { lilyedit() }

    suspend fun setPitch(text: String, offset: Int, pitch: Pitch): TextEdit = call(
        text,
        JSONObject()
            .put("op", "set_pitch")
            .put("pos", text.codePointCount(0, offset))
            .put("note", pitch.step)
            .put("alter", pitch.semitones / 2.0)
            .put("octave", pitch.octave + 1), // python-ly: c' is octave 1
    )

    /**
     * Moves the note at [offset] [by] scale steps (up if positive), spelled as
     * the key signature in effect there has it: in G major, e goes up to fis.
     */
    suspend fun step(text: String, offset: Int, by: Int): TextEdit = call(
        text,
        JSONObject().put("op", "step").put("pos", text.codePointCount(0, offset)).put("by", by),
    )

    /** [duration] as written in LilyPond, e.g. "8" or "4.". */
    suspend fun setDuration(text: String, offset: Int, duration: String): TextEdit = call(
        text,
        JSONObject().put("op", "set_duration").put("pos", text.codePointCount(0, offset)).put("duration", duration),
    )

    suspend fun delete(text: String, offset: Int): TextEdit = call(
        text,
        JSONObject().put("op", "delete").put("pos", text.codePointCount(0, offset)),
    )

    /**
     * Adds a mark to the note, chord or rest at [offset], or takes it off if
     * it is there: an articulation ("staccato", "accent", "tenuto",
     * "marcato", "staccatissimo", "portato", "fermata", "trill", "turn",
     * "prall", "mordent", "upbow", "downbow"), a dynamic ("pp" … "ff",
     * "sfz", "fp"…; it replaces another dynamic on the same note), or
     * "slur-start", "slur-end", "cresc", "decresc", "hairpin-end", "tie".
     * A note inside a chord means the chord.
     */
    suspend fun toggle(text: String, offset: Int, what: String): TextEdit = call(
        text,
        JSONObject().put("op", "toggle").put("pos", text.codePointCount(0, offset)).put("what", what),
    )

    /** The result of a range edit: the edit, and where the selected items are in the edited text. */
    data class RangeEdit(val edit: TextEdit, val offsets: List<Int>)

    /** An edit on every note, chord or rest of a selected range. */
    sealed class RangeOp(internal val name: String, internal val arg: Any?) {
        /** Scale steps, following the key, as [step]. */
        class Step(by: Int) : RangeOp("step", by)
        /** A new accidental for every note (1 = sharp, -1 = flat); rests are left alone. */
        class Alter(semitones: Int) : RangeOp("alter", semitones / 2.0)
        class Duration(duration: String) : RangeOp("duration", duration)
        /** Dots every item, or takes the dots off if all have one. */
        object Dot : RangeOp("dot", null)
        /** Adds the mark ([toggle]'s names) to every item, or takes it off if all have it. */
        class Toggle(what: String) : RangeOp("toggle", what)
        object Delete : RangeOp("delete", null)
    }

    /**
     * Applies [op] to the items at [offsets] (chord notes count once), as one
     * edit, keeping the other music sounding as before. The new offsets are
     * of the same items, in the same order (none after a delete).
     */
    suspend fun range(text: String, offsets: List<Int>, op: RangeOp): RangeEdit = rangeCall(
        text,
        JSONObject().put("op", "multi").put("edit", op.name).put("arg", op.arg ?: JSONObject.NULL)
            .put("positions", org.json.JSONArray(offsets.map { text.codePointCount(0, it) })),
    )

    /**
     * A slur or hairpin ("slur", "cresc", "decresc") from the first to the
     * last offset of each pair (one pair per voice of a range); if all have
     * one, they are taken off. [offsets] are found again as in [range].
     */
    suspend fun span(text: String, pairs: List<Pair<Int, Int>>, kind: String, offsets: List<Int>): RangeEdit = rangeCall(
        text,
        JSONObject().put("op", "span").put("kind", kind)
            .put("pairs", org.json.JSONArray(pairs.map { (a, b) -> org.json.JSONArray(listOf(text.codePointCount(0, a), text.codePointCount(0, b))) }))
            .put("positions", org.json.JSONArray(offsets.map { text.codePointCount(0, it) })),
    )

    private suspend fun rangeCall(text: String, request: JSONObject): RangeEdit = withContext(dispatcher) {
        request.put("text", text)
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        val result = reply.getString("text")
        val positions = reply.getJSONArray("positions")
        RangeEdit(TextEdit.between(text, result), (0 until positions.length()).map { result.offsetByCodePoints(0, positions.getInt(it)) })
    }

    /**
     * Makes the items at [offsets] (one stretch of one voice) the first voice
     * of `<< { … } \\ { rests } >>`, the rests as long as the stretch, so a
     * second voice can be entered there. [Inserted.start] is the first rest.
     */
    suspend fun addVoice(text: String, offsets: List<Int>): Inserted = withContext(dispatcher) {
        val request = JSONObject().put("op", "add_voice").put("text", text)
            .put("positions", org.json.JSONArray(offsets.map { text.codePointCount(0, it) }))
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        val result = reply.getString("text")
        Inserted(TextEdit.between(text, result), result.offsetByCodePoints(0, reply.getInt("start")))
    }

    /** Removes the voice [offset] is in from the `<< { } \\ { } >>` around it; the other music sounds as before. */
    suspend fun removeVoice(text: String, offset: Int): TextEdit = call(
        text,
        JSONObject().put("op", "remove_voice").put("pos", text.codePointCount(0, offset)),
    )

    /** A `<< { } \\ { } >>` passage, [start] to [end], whose voices last [lengths] (in whole notes, e.g. "3/4"). */
    data class VoicePassage(val start: Int, val end: Int, val lengths: List<String>)

    /**
     * The passages with voices of different lengths: LilyPond leaves the
     * end of a shorter voice blank (the music after starts when the longest
     * ends). Passages with tuplets, grace notes, repeats or nested << >>
     * aren't checked.
     */
    suspend fun voiceLengths(text: String): List<VoicePassage> = withContext(dispatcher) {
        val request = JSONObject().put("op", "voice_lengths").put("text", text)
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        val list = reply.getJSONArray("passages")
        List(list.length()) { i ->
            val p = list.getJSONObject(i)
            val lengths = p.getJSONArray("lengths")
            VoicePassage(
                text.offsetByCodePoints(0, p.getInt("start")),
                text.offsetByCodePoints(0, p.getInt("end")),
                List(lengths.length()) { lengths.getString(it) },
            )
        }
    }

    /** Fills the shorter voices of the passage at [offset] with rests, up to the longest. */
    suspend fun padVoices(text: String, offset: Int): TextEdit = call(
        text,
        JSONObject().put("op", "pad_voices").put("pos", text.codePointCount(0, offset)),
    )

    /** The marks (as [toggle] names them) on the note, chord or rest at [offset]. */
    suspend fun marks(text: String, offset: Int): Set<String> = withContext(dispatcher) {
        val request = JSONObject().put("op", "marks").put("text", text).put("pos", text.codePointCount(0, offset))
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        val list = reply.getJSONArray("marks")
        (0 until list.length()).map(list::getString).toSet()
    }

    /** Transposes [start]..[end] (the whole text by default), e.g. from "c" to "d". */
    suspend fun transpose(text: String, from: String, to: String, start: Int = 0, end: Int = -1): TextEdit = call(
        text,
        JSONObject()
            .put("op", "transpose").put("from", from).put("to", to)
            .put("start", text.codePointCount(0, start))
            .put("end", if (end < 0) -1 else text.codePointCount(0, end)),
    )

    /** The result of [insert]: the edit, and where the new item starts in the edited text. */
    data class Inserted(
        val edit: TextEdit,
        val start: Int,
        /** The MIDI keys inserted (for a note entered by staff position, with the key signature's accidental). */
        val keys: List<Int> = emptyList(),
    )

    /**
     * Note entry: inserts a note (one MIDI key in [keys], 60 = middle C), a
     * chord (several keys) or a rest (none) at [offset], with [duration]
     * (e.g. "8."; null keeps the current one). With [after], [offset] is on
     * a note, chord or rest and the new one goes after it; otherwise an
     * [offset] inside an item also means after it. Pitches are spelled for
     * the key in effect there (61 is cis in G major, des in F major) and get
     * the octave marks that make them sound as played, in \relative music
     * too, and in either voice of `<< { } \\ { } >>`.
     *
     * With [fill] (and [after] and a [duration]), a rest at [offset], or
     * right after the item there in the same voice, is filled instead: the
     * new item takes its place, and what is left of it stays as rests. So a
     * voice added with [addVoice] fills up as notes are played.
     */
    suspend fun insert(
        text: String, offset: Int, keys: List<Int>, duration: String? = null, after: Boolean = false, fill: Boolean = false,
    ): Inserted = insert(text, offset, JSONObject().put("keys", org.json.JSONArray(keys)), duration, after, fill)

    /**
     * Like [insert], for a note given by its place on the staff: [step]
     * 0 = c … 6 = b and [octave] (0 is the octave from middle C up), with
     * the accidental the key signature there gives it.
     */
    suspend fun insertStep(
        text: String, offset: Int, step: Int, octave: Int, duration: String? = null, after: Boolean = false, fill: Boolean = false,
    ): Inserted = insert(
        text, offset, JSONObject().put("steps", org.json.JSONArray(listOf(org.json.JSONArray(listOf(step, octave))))),
        duration, after, fill,
    )

    private suspend fun insert(
        text: String, offset: Int, what: JSONObject, duration: String?, after: Boolean, fill: Boolean,
    ): Inserted =
        withContext(dispatcher) {
            val request = JSONObject().put("op", "insert").put("text", text)
                .put("pos", text.codePointCount(0, offset))
                .put("after", after)
                .put("fill", fill)
            for (k in what.keys()) request.put(k, what.get(k))
            duration?.let { request.put("duration", it) }
            val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
            if (reply.has("error")) throw EditException(reply.getString("error"))
            val result = reply.getString("text")
            val keys = reply.optJSONArray("keys")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty()
            Inserted(TextEdit.between(text, result), result.offsetByCodePoints(0, reply.getInt("start")), keys)
        }

    suspend fun relativeToAbsolute(text: String): TextEdit = call(text, JSONObject().put("op", "rel2abs"))

    /** How midi2ly reads a MIDI file. */
    data class MidiImportOptions(
        /** Quantise note durations to this note value, e.g. "16"; null keeps them. */
        val durationQuant: String? = "16",
        /** Quantise note starts to this note value. */
        val startQuant: String? = "16",
        /** Tuplet durations allowed, e.g. "8*2/3" for eighth-note triplets. */
        val tuplets: List<String> = emptyList(),
        /** Key signature: sharps (negative for flats) and whether minor. Null: from the file. */
        val key: Pair<Int, Boolean>? = null,
        /** Absolute pitches instead of \relative. */
        val absolute: Boolean = false,
        /** The tracks to import ([MidiTrack.index]); null for all. */
        val tracks: List<Int>? = null,
    )

    /** A track of a MIDI file with notes in it, for choosing which to import. */
    data class MidiTrack(
        val index: Int,
        val name: String,
        val notes: Int,
        /** On the General MIDI drum channel: midi2ly writes drums as pitched notes. */
        val drums: Boolean,
        /** The General MIDI instrument family ("piano", "strings"…), if the track sets one. */
        val instrument: String?,
    )

    /** The tracks of a MIDI file that have notes (tracks without, like the tempo track, are always kept). */
    suspend fun midiTracks(midi: java.io.File): List<MidiTrack> = withContext(dispatcher) {
        lilyedit() // starts Python if needed
        val module = Python.getInstance().getModule("lilyimport")
        val reply = JSONObject(module.callAttr("call", JSONObject().put("op", "midi_tracks").put("path", midi.absolutePath).toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        val list = reply.getJSONArray("tracks")
        List(list.length()) { i ->
            val t = list.getJSONObject(i)
            MidiTrack(
                t.getInt("index"), t.getString("name"), t.getInt("notes"), t.getBoolean("drums"),
                if (t.isNull("instrument")) null else t.getString("instrument"),
            )
        }
    }

    /** The result of an import: LilyPond text in the current syntax, and the tools' messages. */
    data class Imported(val text: String, val log: String)

    /**
     * Converts a MIDI file to LilyPond with LilyPond's own midi2ly (as
     * Frescobaldi does), then updates the result to the current syntax with
     * convert-ly. midi2ly writes one staff per track; notes that sound
     * together in a track become chords.
     */
    suspend fun importMidi(midi: java.io.File, options: MidiImportOptions = MidiImportOptions()): Imported =
        withContext(dispatcher) {
            lilyedit() // starts Python if needed
            val req = JSONObject().put("op", "midi2ly").put("path", midi.absolutePath)
                .put("absolute", options.absolute)
            options.durationQuant?.let { req.put("duration_quant", it) }
            options.startQuant?.let { req.put("start_quant", it) }
            options.key?.let { (sharps, minor) -> req.put("key", "$sharps:${if (minor) 1 else 0}") }
            if (options.tuplets.isNotEmpty()) req.put("tuplets", org.json.JSONArray(options.tuplets))
            options.tracks?.let { req.put("tracks", org.json.JSONArray(it)) }
            val first = importCall(req)
            val converted = importCall(JSONObject().put("op", "convert_ly").put("text", first.text))
            Imported(converted.text, first.log + "\n" + converted.log)
        }

    /** How musicxml2ly reads a MusicXML file. */
    data class MusicXmlImportOptions(
        /** Absolute pitches instead of \relative. */
        val absolute: Boolean = false,
        /** Keep the file's page size, margins and line and page breaks (made for some other page). */
        val keepLayout: Boolean = false,
    )

    /**
     * Converts a MusicXML file (.musicxml, .xml, or compressed .mxl, as
     * MuseScore, Finale, Sibelius or Dorico export) to LilyPond with
     * LilyPond's own musicxml2ly, then updates it to the current syntax.
     * Voices stay voices, and parts become staves.
     */
    suspend fun importMusicXml(file: java.io.File, options: MusicXmlImportOptions = MusicXmlImportOptions()): Imported =
        withContext(dispatcher) {
            lilyedit() // starts Python if needed
            val first = importCall(
                JSONObject().put("op", "musicxml2ly").put("path", file.absolutePath)
                    .put("absolute", options.absolute).put("keep_layout", options.keepLayout),
            )
            val converted = importCall(JSONObject().put("op", "convert_ly").put("text", first.text))
            Imported(converted.text, first.log + "\n" + converted.log)
        }

    private fun importCall(request: JSONObject): Imported {
        val module = Python.getInstance().getModule("lilyimport")
        val reply = JSONObject(module.callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        return Imported(reply.getString("text"), reply.optString("log"))
    }

    private suspend fun call(text: String, request: JSONObject): TextEdit = withContext(dispatcher) {
        request.put("text", text)
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        TextEdit.between(text, reply.getString("text"))
    }

    private fun lilyedit(): PyObject = module ?: run {
        // (also starts Python for the importers)
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        Python.getInstance().getModule("lilyedit").also { module = it }
    }

    companion object {
        /** Python runs on one thread of its own. */
        private val dispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "python").apply { isDaemon = true } }
                .asCoroutineDispatcher()
    }
}

/** Replace [start] until [end] with [replacement]. */
data class TextEdit(val start: Int, val end: Int, val replacement: String) {
    val isEmpty: Boolean get() = start == end && replacement.isEmpty()

    fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)

    companion object {
        /** The smallest single edit that turns [old] into [new]. */
        fun between(old: String, new: String): TextEdit {
            var prefix = 0
            val max = minOf(old.length, new.length)
            while (prefix < max && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            // Don't split a surrogate pair.
            if (prefix > 0 && Character.isHighSurrogate(old[prefix - 1])) prefix--
            if (suffix > 0 && Character.isLowSurrogate(old[old.length - suffix])) suffix--
            return TextEdit(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        }
    }
}
