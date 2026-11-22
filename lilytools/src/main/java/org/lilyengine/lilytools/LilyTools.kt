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

    /** [duration] as written in LilyPond, e.g. "8" or "4.". */
    suspend fun setDuration(text: String, offset: Int, duration: String): TextEdit = call(
        text,
        JSONObject().put("op", "set_duration").put("pos", text.codePointCount(0, offset)).put("duration", duration),
    )

    suspend fun delete(text: String, offset: Int): TextEdit = call(
        text,
        JSONObject().put("op", "delete").put("pos", text.codePointCount(0, offset)),
    )

    /** Transposes [start]..[end] (the whole text by default), e.g. from "c" to "d". */
    suspend fun transpose(text: String, from: String, to: String, start: Int = 0, end: Int = -1): TextEdit = call(
        text,
        JSONObject()
            .put("op", "transpose").put("from", from).put("to", to)
            .put("start", text.codePointCount(0, start))
            .put("end", if (end < 0) -1 else text.codePointCount(0, end)),
    )

    suspend fun relativeToAbsolute(text: String): TextEdit = call(text, JSONObject().put("op", "rel2abs"))

    private suspend fun call(text: String, request: JSONObject): TextEdit = withContext(dispatcher) {
        request.put("text", text)
        val reply = JSONObject(lilyedit().callAttr("call", request.toString()).toString())
        if (reply.has("error")) throw EditException(reply.getString("error"))
        TextEdit.between(text, reply.getString("text"))
    }

    private fun lilyedit(): PyObject = module ?: run {
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
