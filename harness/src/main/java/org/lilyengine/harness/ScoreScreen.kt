package org.lilyengine.harness

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import org.lilyengine.lilytools.LilyTools
import org.lilyengine.lilytools.TextEdit
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.lilyengine.engine.Diagnostic
import org.lilyengine.engine.LilyPondEngine
import org.lilyengine.engine.RenderProgress
import org.lilyengine.engine.ScoreMap.Grob
import org.lilyengine.scoreview.ScoreDocument
import org.lilyengine.scoreview.ScoreLookup
import org.lilyengine.scoreview.ScoreView
import org.lilyengine.scoreview.SourcePositions
import org.lilyengine.scoreview.rememberScoreViewState
import org.lilyengine.scoreview.voiceColor
import org.lilyengine.midi.ChordCollector
import org.lilyengine.midi.MidiKeyboard
import org.lilyengine.midi.MidiFile
import org.lilyengine.midi.MidiPlayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState

/**
 * Point-and-click spike (roadmap M2): edit LilyPond source and see the score
 * re-render; tap a note to select its source, or move the text cursor to
 * highlight the note. Both directions keep the voices of a `\\` passage
 * apart.
 */
@OptIn(FlowPreview::class)
@Composable
fun ScoreScreen(engine: LilyPondEngine, keyboard: MidiKeyboard) {
    val appFiles = LocalContext.current.filesDir
    val draftFile = remember { java.io.File(appFiles, DRAFT_FILE) }
    /** The source being edited: the saved draft if there is one, else the sample. */
    var text by remember {
        mutableStateOf(TextFieldValue(runCatching { draftFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: SCORE_SAMPLE))
    }
    // Saved a moment after each change, so switching tabs or Android closing the
    // app in the background doesn't lose the work.
    LaunchedEffect(text.text) {
        delay(DRAFT_SAVE_DELAY_MS)
        val t = text.text
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = java.io.File(draftFile.parentFile, "$DRAFT_FILE.tmp")
                tmp.writeText(t)
                tmp.renameTo(draftFile)
            }
        }
    }
    var document by remember { mutableStateOf<ScoreDocument?>(null) }
    var lookup by remember { mutableStateOf<ScoreLookup?>(null) }
    /** The source the current score was rendered from; the map is stale once the text differs. */
    var renderedText by remember { mutableStateOf("") }
    var midi by remember { mutableStateOf<java.io.File?>(null) }
    var status by remember { mutableStateOf("Rendering…") }
    /** The log of the last render that failed, for sharing. */
    var failedLog by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Diagnostic?>(null) }
    var selection by remember { mutableStateOf<List<Grob>>(emptyList()) }
    var viewHeight by remember { mutableStateOf(0) }
    val viewState = rememberScoreViewState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val softKeyboard = LocalSoftwareKeyboardController.current
    /** What is playing, if anything; the cursor shows the notes sounding in it. */
    var playback by remember { mutableStateOf<Playback?>(null) }
    var cursor by remember { mutableStateOf<List<Grob>>(emptyList()) }
    /** Where playback sounds, as chosen; null: on the keyboard if one is connected, else the tablet. */
    var soundChoice by remember { mutableStateOf<Sound?>(null) }
    val canSend by keyboard.canSend.collectAsState()
    val appsListening by keyboard.appsListening.collectAsState()
    val sounds = listOfNotNull(Sound.TABLET, Sound.KEYBOARD.takeIf { canSend }, Sound.APPS.takeIf { appsListening > 0 })
    val sound = soundChoice?.takeIf { it in sounds } ?: if (canSend) Sound.KEYBOARD else Sound.TABLET
    val context = LocalContext.current
    val tools = remember { LilyTools(context) }
    /** Source offset of the selected note or rest; the selection follows it across re-renders. */
    var selectedOffset by remember { mutableStateOf<Int?>(null) }
    /**
     * A range drawn with the pen: the source offsets of its notes and rests
     * (two or more), which the selection follows across re-renders and edits.
     */
    var range by remember { mutableStateOf<List<Int>>(emptyList()) }
    val undo = remember { UndoHistory() }
    var busy by remember { mutableStateOf(false) }
    // Python starts in the background, so the first edit doesn't wait for it.
    LaunchedEffect(Unit) { runCatching { tools.start() } }
    DisposableEffect(Unit) {
        onDispose {
            playback?.stop()
            document?.close()
        }
    }

    /** How long the last full render took: long scores get a fast preview after an edit. */
    var lastFullMillis by remember { mutableStateOf(0L) }
    /** The file the score was opened from or saved to, if any, and whether it has unsaved changes. */
    var file by remember { mutableStateOf(ScoreFiles.load(context)) }
    val modified = file.savedHash != text.text.hashCode()
    fun setFile(f: ScoreFiles.Current) { file = f; ScoreFiles.store(context, f) }
    /** The last whole render's PDF, copied out for sharing (renders delete their own files). */
    var exportPdf by remember { mutableStateOf<java.io.File?>(null) }
    var fileMenu by remember { mutableStateOf(false) }
    var transposing by remember { mutableStateOf(false) }
    var inserting by remember { mutableStateOf(false) }
    /** Music copied with Copy or Cut, for Paste. */
    var clip by remember { mutableStateOf<LilyTools.Clip?>(null) }
    /** The score's settings while the Score settings dialog is open. */
    var editingSettings by remember { mutableStateOf<LilyTools.ScoreSettings?>(null) }
    /** The New score dialog's templates, once asked for (null: the dialog is closed). */
    var newScoreTemplates by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    /** Where the files the score \includes are copied to for LilyPond (from the folder the user granted). */
    var includeDirs by remember { mutableStateOf(emptyList<java.io.File>()) }
    /** A render failed for an included file it couldn't find: offer to grant the score's folder. */
    var needsFolder by remember { mutableStateOf(false) }
    /** Bumped to render again with the same text (after granting a folder). */
    var rerender by remember { mutableIntStateOf(0) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val f = file.uri
        if (tree == null || f == null) return@rememberLauncherForActivityResult
        runCatching { IncludeFolder.setFolderFor(context, f, tree) }
            .onSuccess { needsFolder = false; rerender++ }
            .onFailure { status = "Can't use that folder: ${it.message}" }
    }
    /** Lyrics mode: verses typed as plain text for the melody of [lyricAnchor] (a note's offset). */
    var lyricsMode by remember { mutableStateOf(false) }
    var lyricAnchor by remember { mutableStateOf<Int?>(null) }
    var verseList by remember { mutableStateOf(emptyList<String>()) }
    var verseIndex by remember { mutableIntStateOf(0) }
    var verseField by remember { mutableStateOf(TextFieldValue("")) }
    val verseText = verseField.text
    /** How each verse fits its melody: syllables against notes to sing. */
    var verseCounts by remember { mutableStateOf(emptyList<LilyTools.VerseCount>()) }
    /** The verse text last written into the source, so loading a verse doesn't write it back. */
    var verseApplied by remember { mutableStateOf<String?>(null) }
    /** Puts a new text in the editor as one undoable step, dropping selection and range. */
    fun replaceText(newText: String) {
        undo.addLast(text.text)
        selectedOffset = null; selection = emptyList(); range = emptyList()
        text = TextFieldValue(newText)
    }
    fun saveTo(uri: android.net.Uri, name: String?) {
        runCatching { ScoreFiles.write(context, uri, text.text) }
            .onSuccess {
                setFile(ScoreFiles.Current(uri, name, text.text.hashCode()))
                status = "Saved ${name ?: "the score"}"
            }
            .onFailure { status = "Can't save there: ${it.message}" }
    }
    val openLy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ScoreFiles.keepAccess(context, uri)
            val t = ScoreFiles.read(context, uri)
            val name = ScoreFiles.displayName(context, uri)
            replaceText(t)
            setFile(ScoreFiles.Current(uri, name, t.hashCode()))
            status = "Opened ${name ?: "the file"}"
        }.onFailure { status = "Can't open that file: ${it.message}" }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-lilypond")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ScoreFiles.keepAccess(context, uri)
        saveTo(uri, ScoreFiles.displayName(context, uri))
    }
    /** A MIDI file picked for import, waiting for the options dialog. */
    var midiToImport by remember { mutableStateOf<java.io.File?>(null) }
    /** A MusicXML file picked for import, waiting for the options dialog. */
    var musicXmlToImport by remember { mutableStateOf<java.io.File?>(null) }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            // Told apart by their first bytes, as file names and types are often wrong.
            when {
                bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "MThd" ->
                    midiToImport = java.io.File(context.cacheDir, "import.mid").apply { writeBytes(bytes) }
                bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                    musicXmlToImport = java.io.File(context.cacheDir, "import.mxl").apply { writeBytes(bytes) }
                String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).let { "<score-partwise" in it || "<score-timewise" in it } ->
                    musicXmlToImport = java.io.File(context.cacheDir, "import.musicxml").apply { writeBytes(bytes) }
                else -> status = "That isn't a MIDI or MusicXML file."
            }
        }.onFailure { status = "Can't read that file: ${it.message}" }
    }
    /** Note entry: notes played or tapped go in after the selected note. */
    var entering by remember { mutableStateOf(false) }
    var entryDuration by remember { mutableStateOf("4") }
    var entryDot by remember { mutableStateOf(false) }
    /** New notes take the place of rests after the selected note (as in a voice just added). */
    var fillRests by remember { mutableStateOf(true) }
    /** New notes join the selected note or chord instead of following it. */
    var chordMode by remember { mutableStateOf(false) }
    /** Recording in real time, if under way; and its tempo and rhythm grid. */
    var recorder by remember { mutableStateOf<Recorder?>(null) }
    var recordBpm by remember { mutableIntStateOf(80) }
    var recordGrid by remember { mutableStateOf("16") }
    /** Record with two hands: notes below middle C go into the staff below. */
    var twoHands by remember { mutableStateOf(true) }
    /** Notes, chords and rests waiting to be entered, in playing order. */
    val entries = remember { Channel<Entry>(Channel.UNLIMITED) }
    val connectedKeyboard by keyboard.connected.collectAsState()
    DisposableEffect(keyboard, entering, recorder) {
        // (while recording, what is played is written when recording stops)
        if (!entering || recorder != null) return@DisposableEffect onDispose { }
        val chords = ChordCollector { keys -> entries.trySend(Entry.Keys(keys)) }
        val listener: (MidiKeyboard.Event) -> Unit = { e ->
            when (e) {
                is MidiKeyboard.Event.NoteOn -> chords.noteOn(e.key)
                is MidiKeyboard.Event.NoteOff -> chords.noteOff(e.key)
                else -> {}
            }
        }
        keyboard.addListener(listener)
        onDispose { keyboard.removeListener(listener) }
    }
    /** Set by an edit: the text it produced and the moment of the edited note. */
    val pendingEdit = remember { mutableStateOf<Pair<String, String>?>(null) }
    /** Set by a MIDI import: its text, of which the first bars are shown before the whole score. */
    val pendingImport = remember { mutableStateOf<String?>(null) }

    /**
     * Renders the whole score, showing in [status] how far LilyPond has got,
     * since a long score (an imported orchestral MIDI file) can take minutes.
     */
    suspend fun renderWhole(source: String, what: String): LilyPondEngine.Result = coroutineScope {
        val progress = RenderProgress()
        val start = System.nanoTime()
        val ticker = launch {
            delay(1500) // most renders are done by now
            while (true) {
                status = "$what: ${progress.phase} (${(System.nanoTime() - start) / 1_000_000_000} s)…"
                delay(500)
            }
        }
        try {
            engine.render(
                source,
                LilyPondEngine.Options(
                    midi = true, scoreMap = true, timeoutSeconds = WHOLE_SCORE_TIMEOUT_S, onOutput = progress::onOutput,
                    includeDirs = includeDirs,
                ),
            )
        } finally {
            ticker.cancel()
        }
    }

    /** A copy of [file] that later renders leave alone, for Play (older copies are removed). */
    fun keepForPlayback(file: java.io.File): java.io.File {
        val copy = java.io.File(context.cacheDir, "playback-${System.nanoTime()}.mid")
        file.copyTo(copy)
        context.cacheDir.listFiles { f -> f.name.startsWith("playback-") && f != copy }?.forEach { it.delete() }
        return copy
    }

    /** Shows a render's PDF and score map; returns false if it failed. */
    suspend fun show(r: LilyPondEngine.Result, source: String): Boolean {
        error = r.diagnostics.firstOrNull { it.severity == Diagnostic.Severity.ERROR }
        val pdf = r.outputs.firstOrNull()
        if (!r.success || pdf == null) {
            status = "LilyPond failed" + when {
                error != null -> ": line ${error!!.line}: ${error!!.message}"
                r.log.trimEnd().endsWith("s]") && "[timed out after" in r.log ->
                    ": it took longer than ${WHOLE_SCORE_TIMEOUT_S / 60} minutes and was stopped"
                r.exitCode >= 128 -> ": it crashed (signal ${r.exitCode - 128})"
                else -> " (exit code ${r.exitCode})"
            }
            failedLog = r.log
            needsFolder = "cannot find file" in r.log && IncludeFolder.includes(source).isNotEmpty()
            return false
        }
        failedLog = null
        val newDoc = ScoreDocument.open(pdf)
        document?.close()
        document = newDoc
        lookup = r.scoreMap?.let { ScoreLookup(it) }
        renderedText = source
        selection = (range.ifEmpty { listOfNotNull(selectedOffset) }).flatMap { off ->
            val (line, char) = SourcePositions.locationOf(source, off)
            lookup?.objectsAtSource(line, char, "score.ly").orEmpty()
        }.distinct()
        // Keep the edited note in view: the preview and the whole score put it on different pages.
        val l = lookup
        selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }?.let { g ->
            val h = newDoc.pageSizes.getOrNull(g.page - 1)?.second ?: return@let
            if (l != null) viewState.reveal(g.page, l.pageRect(g)[1] / h, viewHeight)
        }
        return true
    }

    // Re-render after typing pauses (at once after an edit button); a newer
    // edit cancels a render in progress. After an edit to a long score, the
    // bars around the edit are engraved first, then the whole score.
    LaunchedEffect(Unit) {
        snapshotFlow { text.text to rerender }
            .distinctUntilChanged()
            .debounce { if (pendingEdit.value?.first == it.first) 0L else 600L }
            .collectLatest { (source, _) ->
                // Included files from the score's folder, copied where LilyPond can read them.
                val folder = IncludeFolder.folderFor(context, file.uri)
                includeDirs = if (folder == null || IncludeFolder.includes(source).isEmpty()) emptyList()
                else withContext(Dispatchers.IO) {
                    val dest = java.io.File(context.cacheDir, "includes")
                    runCatching { IncludeFolder.sync(context, folder, source, dest) }
                    listOf(dest)
                }
                val edit = pendingEdit.value?.takeIf { it.first == source }
                pendingEdit.value = null
                val imported = pendingImport.value == source
                pendingImport.value = null
                val l = lookup
                if (imported) {
                    // An imported file can be long: show its beginning first.
                    status = "Engraving the first bars…"
                    val p = engine.render(source, LilyPondEngine.Options(midi = false, scoreMap = true, window = "0" to FIRST_LOOK_WHOLE_NOTES, includeDirs = includeDirs))
                    if (show(p, source)) {
                        status = "Preview: the first bars (${p.elapsedMillis} ms); whole score in progress…"
                    }
                } else if (edit != null && l != null && lastFullMillis > PREVIEW_THRESHOLD_MS) {
                    status = "Engraving the bars around the edit…"
                    val window = l.windowAround(edit.second)
                    val p = engine.render(source, LilyPondEngine.Options(midi = false, scoreMap = true, window = window, includeDirs = includeDirs))
                    if (show(p, source)) {
                        status = "Preview: bars around the edit (${p.elapsedMillis} ms); whole score in progress…"
                    }
                } else {
                    status = "Rendering…"
                }
                val r = renderWhole(source, if (lookup != null && renderedText == source) "Whole score" else "Rendering")
                if (show(r, source)) {
                    lastFullMillis = r.elapsedMillis
                    // Copied out: the engine deletes a render's files when the next one starts,
                    // which would leave Play with nothing to play.
                    midi = r.midi.firstOrNull()?.let { keepForPlayback(it) }
                    exportPdf = r.outputs.firstOrNull()?.let {
                        it.copyTo(ScoreFiles.exportFile(context, "score.pdf"), overwrite = true)
                    }
                    status = "${document?.pageCount} page(s), ${r.elapsedMillis} ms" +
                        (error?.let { " — error on line ${it.line}: ${it.message}" } ?: "")
                }
            }
    }

    fun selectFromCursor(value: TextFieldValue) {
        val l = lookup ?: return
        if (value.text != renderedText) return // positions have moved since the render
        val (line, char) = SourcePositions.locationOf(value.text, value.selection.start)
        range = emptyList()
        selection = l.objectsAtSource(line, char, "score.ly")
        selectedOffset = selection.firstNotNullOfOrNull { it.origin }
            ?.let { SourcePositions.offsetOf(value.text, it.line, it.char) }
        selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }?.let { g ->
            val (_, top) = l.pageRect(g).let { it[0] to it[1] }
            val h = document?.pageSizes?.getOrNull(g.page - 1)?.second ?: return@let
            scope.launch { viewState.reveal(g.page, top / h, viewHeight) }
        }
    }

    fun onTap(g: Grob?) {
        val l = lookup ?: return
        range = emptyList()
        if (g == null) { selection = emptyList(); selectedOffset = null; return }
        selection = l.sameOrigin(g)
        val origin = g.origin ?: return
        if (text.text != renderedText) return
        val start = SourcePositions.offsetOf(text.text, origin.line, origin.char)
        if (lyricsMode && g.type == "LyricText") {
            // A sung word: open its verse, with the cursor on the word.
            scope.launch {
                val place = runCatching { tools.verseAt(text.text, start) }.getOrNull() ?: return@launch
                val list = runCatching { tools.verses(text.text, place.melodyOffset) }.getOrNull() ?: return@launch
                lyricAnchor = place.melodyOffset
                selectedOffset = place.melodyOffset
                verseList = list
                verseIndex = place.verse
                val verse = list.getOrElse(place.verse) { "" }
                verseField = TextFieldValue(verse, TextRange(wordStart(verse, place.word)))
                verseApplied = verse
            }
            return
        }
        selectedOffset = start
        text = text.copy(selection = TextRange(start, SourcePositions.tokenEnd(text.text, start)))
        focus.requestFocus()
        softKeyboard?.hide() // a pen tap shouldn't bring up the keyboard
    }

    /** A range drawn with the pen: its notes and rests are selected. */
    fun onSelectRange(grobs: List<Grob>) {
        if (text.text != renderedText) return
        val items = grobs.filter { it.origin != null }
        val offsets = items.map { SourcePositions.offsetOf(text.text, it.origin!!.line, it.origin!!.char) }.distinct().sorted()
        when (offsets.size) {
            0 -> onTap(null)
            1 -> onTap(items.first())
            else -> {
                range = offsets
                selection = grobs
                selectedOffset = offsets.first()
                text = text.copy(selection = TextRange(offsets.first(), SourcePositions.tokenEnd(text.text, offsets.last())))
                softKeyboard?.hide()
            }
        }
    }

    /** Passages whose voices aren't equally long (checked shortly after each change). */
    var uneven by remember { mutableStateOf<List<LilyTools.VoicePassage>>(emptyList()) }
    LaunchedEffect(text.text) {
        delay(VOICE_CHECK_DELAY_MS)
        uneven = runCatching { tools.voiceLengths(text.text) }.getOrDefault(emptyList())
    }

    // Lyrics: the selected note's melody, its verses, and typing written in shortly after.
    LaunchedEffect(lyricsMode, selectedOffset) {
        if (lyricsMode && selectedOffset != null && selectedOffset != lyricAnchor) {
            val at = selectedOffset!!
            val list = runCatching { tools.verses(text.text, at) }.getOrElse { status = "No melody here for lyrics: ${it.message}"; return@LaunchedEffect }
            lyricAnchor = at
            verseList = list
            verseIndex = 0
            verseField = TextFieldValue(list.firstOrNull().orEmpty())
            verseApplied = list.firstOrNull().orEmpty()
        }
    }
    LaunchedEffect(lyricAnchor, text.text) {
        val at = lyricAnchor
        verseCounts = if (!lyricsMode || at == null || at > text.text.length) emptyList()
        else runCatching { tools.verseCheck(text.text, at) }.getOrDefault(emptyList())
    }
    LaunchedEffect(verseText, verseIndex) {
        val at = lyricAnchor ?: return@LaunchedEffect
        if (!lyricsMode || verseText == verseApplied) return@LaunchedEffect
        delay(LYRICS_APPLY_DELAY_MS)
        snapshotFlow { busy }.first { !it }
        busy = true
        try {
            val before = text.text
            val r = tools.setVerse(before, at, verseIndex, verseText)
            if (!r.edit.isEmpty) {
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                lyricAnchor = r.offset
                selectedOffset = r.offset
                text = TextFieldValue(after, TextRange(r.offset))
                verseList = tools.verses(after, r.offset)
            }
            verseApplied = verseText
        } catch (ex: LilyTools.EditException) {
            status = "Can't write these lyrics: ${ex.message}"
        } finally {
            busy = false
        }
    }

    /** Colours in the source editor. */
    val highlighting = remember { LilyHighlight.Transformation() }

    /** The marks on the selected note, or on every item of the range (articulations, dynamics, slurs…), as LilyTools.toggle names them. */
    var selectedMarks by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(selectedOffset, range, text.text) {
        val at = range.ifEmpty { listOfNotNull(selectedOffset) }.filter { it < text.text.length }
        selectedMarks = if (at.isEmpty() || at.size > MAX_MARKS_LOOKUPS) emptySet()
        else runCatching { at.map { tools.marks(text.text, it) }.reduce { a, b -> a intersect b } }.getOrDefault(emptySet())
    }

    /** Runs an edit on every item of the range, as one text change; the range follows its items. */
    fun rangeEdit(action: suspend (source: String, offsets: List<Int>) -> LilyTools.RangeEdit) {
        if (busy || range.size < 2) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val r = action(before, range)
                if (!r.edit.isEmpty) {
                    undo.addLast(before)
                    val after = r.edit.applyTo(before)
                    selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                    range = r.offsets.takeIf { it.size >= 2 }.orEmpty()
                    selectedOffset = r.offsets.firstOrNull()
                    if (r.offsets.isEmpty()) selection = emptyList()
                    text = TextFieldValue(
                        after,
                        if (r.offsets.isEmpty()) TextRange(0)
                        else TextRange(r.offsets.first(), SourcePositions.tokenEnd(after, r.offsets.last())),
                    )
                }
            } catch (ex: LilyTools.EditException) {
                status = "Can't do that here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Adds a second voice over the range (or the selected note): rests to be filled by note entry. */
    fun addVoice() {
        val offsets = range.ifEmpty { listOfNotNull(selectedOffset) }
        if (busy || offsets.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val r = tools.addVoice(before, offsets)
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                range = emptyList()
                selectedOffset = r.start
                text = TextFieldValue(after, TextRange(r.start, SourcePositions.tokenEnd(after, r.start)))
                entering = true
                fillRests = true
                status = "Added a voice: play or tap its notes, and they take the place of the rests."
            } catch (ex: LilyTools.EditException) {
                status = "Can't add a voice here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Stops recording and writes what was played after the selected note. */
    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        val played = rec.stop()
        val g = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
        val at = selectedOffset
        if (played.isEmpty() || g == null || at == null) {
            status = if (played.isEmpty()) "Nothing was played." else "Select the note to write after first."
            return
        }
        // The new music starts when the selected note ends: for its bar lines.
        val start = (g.momentValue ?: 0.0) + (g.duration?.let { ScoreLookup.durationLength(it) } ?: 0.0)
        // With two hands, notes below middle C go on in the staff below, from the same moment.
        val below = if (twoHands) lookup?.alignedBelow(g)?.origin?.let { SourcePositions.offsetOf(text.text, it.line, it.char) } else null
        val parts = if (below == null) listOf(at to played)
        else listOf(at to played.filter { it.key >= SPLIT_KEY }, below to played.filter { it.key < SPLIT_KEY })
            .filter { it.second.isNotEmpty() }
        busy = true
        scope.launch {
            try {
                val before = text.text
                var after = before
                var span: Pair<Int, Int>? = null
                // The later place first: writing there leaves the earlier offset as it was.
                for ((offset, notes) in parts.sortedByDescending { it.first }) {
                    val r = tools.record(after, offset, notes, start, recordGrid)
                    after = r.edit.applyTo(after)
                    if (offset == at) span = r.start to r.end
                }
                undo.addLast(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                selectedOffset = null
                text = TextFieldValue(after, span?.let { TextRange(it.first, it.second) } ?: TextRange(at))
                status = "Recorded ${played.size} notes" + if (parts.size > 1) ", in both hands." else "."
            } catch (ex: LilyTools.EditException) {
                status = "Can't write the recording: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    /** A slur or hairpin over the range: from its first to its last note or rest in each voice. */
    fun spanRange(kind: String) {
        val r = renderedText
        val pairs = selection.filter { (it.type == "NoteHead" || it.type == "Rest") && it.origin != null }
            .groupBy { it.voiceId.orEmpty() }
            .mapNotNull { (_, gs) ->
                val offs = gs.map { SourcePositions.offsetOf(r, it.origin!!.line, it.origin!!.char) }
                (offs.min() to offs.max()).takeIf { it.first != it.second }
            }
        if (pairs.isEmpty()) {
            status = "Select two or more notes in a voice for a slur or hairpin."
            return
        }
        rangeEdit { src, offsets -> tools.span(src, pairs, kind, offsets) }
    }

    /** Runs a structural edit on the selected item and applies the result as one text change. */
    fun edit(at: Int? = null, action: suspend (source: String, offset: Int) -> TextEdit) {
        val offset = at ?: selectedOffset ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val e = action(before, offset)
                if (!e.isEmpty) {
                    undo.addLast(before)
                    val after = e.applyTo(before)
                    val moment = (lookup?.objectsAtSource(
                        SourcePositions.locationOf(before, offset).first,
                        SourcePositions.locationOf(before, offset).second, "score.ly",
                    ).orEmpty() + selection).firstNotNullOfOrNull { it.moment }
                    if (moment != null) pendingEdit.value = after to moment
                    // The item still starts at the same offset unless the edit removed it.
                    val keep = offset.coerceAtMost(after.length)
                    text = TextFieldValue(after, TextRange(keep, SourcePositions.tokenEnd(after, keep)))
                }
            } catch (ex: LilyTools.EditException) {
                status = "Can't do that here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    // Enters queued notes one at a time, each after the one before.
    LaunchedEffect(Unit) {
        for (entry in entries) {
            snapshotFlow { busy }.first { !it }
            busy = true
            try {
                val before = text.text
                val selected = selectedOffset?.takeIf { it < before.length }
                val at = selected ?: text.selection.start
                val duration = entryDuration + if (entryDot) "." else ""
                val toChord = chordMode && selected != null
                val r = when {
                    entry is Entry.Keys && toChord && entry.keys.isNotEmpty() -> tools.addToChord(before, at, entry.keys)
                    entry is Entry.Keys -> tools.insert(before, at, entry.keys, duration, after = selected != null, fill = fillRests)
                    entry is Entry.Step && toChord -> tools.addStepToChord(before, at, entry.step, entry.octave)
                        .also { keyboard.play(it.keys, 300) }
                    entry is Entry.Step -> tools.insertStep(before, at, entry.step, entry.octave, duration, after = selected != null, fill = fillRests)
                        .also { keyboard.play(it.keys, 300) } // the note tapped on the staff, as entered
                    else -> error("unreachable")
                }
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                selectedOffset = r.start
                text = TextFieldValue(after, TextRange(r.start, SourcePositions.tokenEnd(after, r.start)))
            } catch (ex: LilyTools.EditException) {
                status = "Can't enter a note here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    // The playback cursor: the notes sounding now, in every voice, kept in view.
    LaunchedEffect(playback) {
        val p = playback ?: return@LaunchedEffect
        while (p.active) {
            val m = p.file.wholeNotes(p.file.tick(p.positionMicros))
            val l = lookup
            val now = l?.soundingAt(m).orEmpty()
            cursor = now
            val top = now.minWithOrNull(compareBy<Grob>({ it.page }, { -it.bbox[3] }))
            val h = top?.let { document?.pageSizes?.getOrNull(it.page - 1)?.second }
            if (top != null && h != null && l != null) viewState.reveal(top.page, l.pageRect(top)[1] / h, viewHeight)
            delay(33)
        }
        if (playback === p) { playback = null; cursor = emptyList() }
    }

    /** Copies the range or the selected item; with [cut], removes it too. */
    fun copySelection(cut: Boolean) {
        val offsets = range.ifEmpty { listOfNotNull(selectedOffset) }
        if (offsets.isEmpty() || text.text != renderedText) return
        scope.launch {
            runCatching { tools.copy(text.text, offsets) }
                .onSuccess { c ->
                    clip = c
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("LilyPond", c.fragment))
                    status = (if (cut) "Cut " else "Copied ") + c.fragment.take(40) + if (c.fragment.length > 40) "…" else ""
                    if (cut) {
                        if (offsets.size >= 2) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Delete) }
                        else edit { src, off -> tools.delete(src, off) }
                    }
                }
                .onFailure { status = "Can't copy that: ${it.message}" }
        }
    }

    /** Pastes the copied music after the selected item. */
    fun pasteClip() {
        val c = clip ?: return
        val at = selectedOffset ?: return
        if (busy || text.text != renderedText) return
        busy = true
        scope.launch {
            try {
                val before = text.text
                val r = tools.paste(before, at, c)
                undo.addLast(before)
                val after = r.edit.applyTo(before)
                selection.firstNotNullOfOrNull { it.moment }?.let { pendingEdit.value = after to it }
                range = emptyList(); selectedOffset = null
                text = TextFieldValue(after, TextRange(r.start, r.end))
            } catch (ex: LilyTools.EditException) {
                status = "Can't paste here: ${ex.message}"
            } finally {
                busy = false
            }
        }
    }

    fun undoLast() {
        val previous = undo.undo(text.text) ?: return
        range = emptyList()
        text = TextFieldValue(previous, TextRange(selectedOffset?.coerceAtMost(previous.length) ?: 0))
    }

    fun redoLast() {
        val next = undo.redo(text.text) ?: return
        range = emptyList()
        text = TextFieldValue(next, TextRange(selectedOffset?.coerceAtMost(next.length) ?: 0))
    }

    fun jumpTo(d: Diagnostic) {
        val start = SourcePositions.offsetOf(text.text, d.line, (d.column - 1).coerceAtLeast(0))
        text = text.copy(selection = TextRange(start))
        focus.requestFocus()
    }

    /** The tracks of the MIDI file picked for import, once read. */
    var midiTracks by remember { mutableStateOf<List<LilyTools.MidiTrack>?>(null) }
    LaunchedEffect(midiToImport) {
        midiTracks = null
        val f = midiToImport ?: return@LaunchedEffect
        midiTracks = runCatching { tools.midiTracks(f) }.getOrElse { status = "Can't read the tracks: ${it.message}"; emptyList() }
    }
    midiToImport?.let { file ->
        MidiImportDialog(
            tracks = midiTracks,
            onDismiss = { midiToImport = null },
            onImport = { options ->
                midiToImport = null
                status = "Importing MIDI…"
                scope.launch {
                    try {
                        val imported = tools.importMidi(file, options)
                        // Until the whole score is engraved (minutes, for a big file), Play
                        // plays the MIDI file itself.
                        midi = file.copyTo(java.io.File(context.cacheDir, "import-playback.mid"), overwrite = true)
                        undo.addLast(text.text)
                        selectedOffset = null
                        selection = emptyList()
                        pendingImport.value = imported.text
                        text = TextFieldValue(imported.text)
                    } catch (e: LilyTools.EditException) {
                        status = "MIDI import failed: ${e.message}"
                    }
                }
            },
        )
    }

    newScoreTemplates?.let { templates ->
        NewScoreDialog(
            templates = templates,
            onDismiss = { newScoreTemplates = null },
            onCreate = { spec ->
                newScoreTemplates = null
                scope.launch {
                    runCatching { tools.newScore(spec) }
                        .onSuccess { t ->
                            replaceText(t)
                            setFile(ScoreFiles.Current(null, null, null))
                            status = "New score: tap a rest and Enter notes to fill it."
                        }
                        .onFailure { status = "Can't make that score: ${it.message}" }
                }
            },
        )
    }

    editingSettings?.let { current ->
        SettingsDialog(
            initial = current,
            onDismiss = { editingSettings = null },
            onApply = { new ->
                editingSettings = null
                scope.launch {
                    runCatching { tools.setSettings(text.text, new) }
                        .onSuccess { e ->
                            if (!e.isEmpty) {
                                undo.addLast(text.text)
                                range = emptyList(); selectedOffset = null; selection = emptyList()
                                text = TextFieldValue(e.applyTo(text.text))
                            }
                        }
                        .onFailure { status = "Can't change the settings: ${it.message}" }
                }
            },
        )
    }

    if (inserting) {
        InsertDialog(
            canRepeat = range.isNotEmpty() || selectedOffset != null,
            onDismiss = { inserting = false },
            onInsert = { what ->
                inserting = false
                val offsets = range.ifEmpty { listOfNotNull(selectedOffset) }
                if (offsets.isEmpty()) return@InsertDialog
                when (what) {
                    is Insertion.Change -> edit(if (what.after) offsets.max() else offsets.min()) { src, off ->
                        tools.change(src, off, what.kind, what.written, what.after)
                    }
                    is Insertion.Words -> edit(offsets.min()) { src, off -> tools.addText(src, off, what.words, what.above) }
                    is Insertion.Repeat -> edit(offsets.min()) { src, _ -> tools.repeat(src, offsets, what.times) }
                }
                range = emptyList()
            },
        )
    }

    if (transposing) {
        // The range, else the selected note or chord, else everything.
        val sel = range.ifEmpty { listOfNotNull(selectedOffset) }.filter { it <= text.text.length }
        val part = if (sel.isEmpty()) null else sel.min() to SourcePositions.tokenEnd(text.text, sel.max())
        TransposeDialog(
            what = when {
                range.size >= 2 -> "the selected notes"
                selectedOffset != null -> "the selected note"
                else -> "the whole score"
            },
            onDismiss = { transposing = false },
            onTranspose = { to ->
                transposing = false
                if (busy) return@TransposeDialog
                busy = true
                scope.launch {
                    try {
                        val before = text.text
                        val e = if (part == null) tools.transpose(before, "c", to) else tools.transpose(before, "c", to, part.first, part.second)
                        if (!e.isEmpty) {
                            undo.addLast(before)
                            // Note names change length (c -> des): the old offsets no longer fit.
                            range = emptyList(); selectedOffset = null; selection = emptyList()
                            text = TextFieldValue(e.applyTo(before))
                        }
                    } catch (ex: LilyTools.EditException) {
                        status = "Can't transpose: ${ex.message}"
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    musicXmlToImport?.let { file ->
        MusicXmlImportDialog(
            onDismiss = { musicXmlToImport = null },
            onImport = { options ->
                musicXmlToImport = null
                status = "Importing MusicXML…"
                scope.launch {
                    try {
                        val imported = tools.importMusicXml(file, options)
                        midi = null // the old score's; Play waits for the whole score's
                        undo.addLast(text.text)
                        selectedOffset = null
                        selection = emptyList()
                        range = emptyList()
                        pendingImport.value = imported.text
                        text = TextFieldValue(imported.text)
                    } catch (e: LilyTools.EditException) {
                        status = "MusicXML import failed: ${e.message}"
                    }
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val piano: @Composable () -> Unit = {
            PianoBar(onKey = { k -> keyboard.tap(k) })
        }
        val score: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Box {
                        TextButton(onClick = { fileMenu = true }) {
                            Text((file.name ?: "Untitled") + (if (modified) " •" else "") + " ▾", maxLines = 1)
                        }
                        DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                            @Composable
                            fun Item(label: String, enabled: Boolean = true, action: () -> Unit) =
                                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { fileMenu = false; action() })
                            Item("New…") {
                                scope.launch { newScoreTemplates = runCatching { tools.templates() }.getOrElse { listOf("melody" to "Melody") } }
                            }
                            Item("Open…") { openLy.launch(LY_TYPES) }
                            Item("Save", enabled = modified || file.uri == null) {
                                val uri = file.uri
                                if (uri != null) saveTo(uri, file.name) else saveAs.launch(file.name ?: "score.ly")
                            }
                            Item("Save as…") { saveAs.launch(file.name ?: "score.ly") }
                            Item("Score settings…") {
                                scope.launch { editingSettings = runCatching { tools.settings(text.text) }.getOrElse { LilyTools.ScoreSettings() } }
                            }
                            HorizontalDivider()
                            Item("Share PDF", enabled = exportPdf != null) {
                                exportPdf?.let { ScoreFiles.share(context, it, "application/pdf", ScoreFiles.baseName(file.name) + ".pdf") }
                            }
                            Item("Share MIDI", enabled = midi != null) {
                                midi?.let { m ->
                                    val out = ScoreFiles.exportFile(context, ScoreFiles.baseName(file.name) + ".mid")
                                    m.copyTo(out, overwrite = true)
                                    ScoreFiles.share(context, out, "audio/midi", out.name)
                                }
                            }
                            Item("Share LilyPond source") {
                                val out = ScoreFiles.exportFile(context, ScoreFiles.baseName(file.name) + ".ly")
                                out.writeText(text.text)
                                ScoreFiles.share(context, out, "text/x-lilypond", out.name)
                            }
                            HorizontalDivider()
                            Item("Sample: piano") { replaceText(SCORE_SAMPLE); setFile(ScoreFiles.Current(null, null, null)) }
                            Item("Sample: 15-page piece") { replaceText(LONG_SAMPLE); setFile(ScoreFiles.Current(null, null, null)) }
                        }
                    }
                    error?.let { e -> TextButton(onClick = { jumpTo(e) }) { Text("Go to error") } }
                    if (lyricsMode) Button(onClick = { lyricsMode = false; lyricAnchor = null }) { Text("✓ Lyrics") }
                    else TextButton(onClick = { lyricsMode = true; entering = false; lyricAnchor = null }) { Text("Lyrics") }
                    if (entering) Button(onClick = { entering = false }) { Text("✓ Enter notes") }
                    else TextButton(onClick = { entering = true; lyricsMode = false }) { Text("Enter notes") }
                    TextButton(onClick = {
                        pickImport.launch(IMPORT_TYPES)
                    }) { Text("Import") }

                    if (sounds.size > 1) TextButton(onClick = { soundChoice = sounds[(sounds.indexOf(sound) + 1) % sounds.size] }) {
                        Text("Sound: ${sound.label}")
                    }
                    Button(enabled = midi != null || playback != null, onClick = {
                        val current = playback
                        if (current != null) {
                            current.stop(); playback = null; cursor = emptyList()
                        } else midi?.let { f ->
                            val start = selection.firstNotNullOfOrNull { it.momentValue }
                            playback = runCatching {
                                Playback.start(
                                    f, start,
                                    when (sound) {
                                        Sound.TABLET -> null
                                        Sound.KEYBOARD -> { b -> keyboard.send(b) }
                                        Sound.APPS -> { b -> keyboard.sendToApps(b) }
                                    },
                                )
                            }.onFailure { status = "Can't play: ${it.message}" }.getOrNull()
                        }
                    }) { Text(if (playback != null) "Stop" else if (selection.isNotEmpty()) "Play from here" else "Play") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp).weight(1f))
                    if (needsFolder) {
                        if (file.uri == null) Text("Save the score in its folder first, to include files from there.", fontSize = 12.sp)
                        else {
                            TextButton(onClick = { pickFolder.launch(null) }) { Text("Choose its folder") }
                            // Android refuses Downloads itself (and the top of storage) as a folder to grant.
                            Text("(a folder of its own, e.g. Download/scores)", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    failedLog?.let { log ->
                        TextButton(onClick = {
                            shareText(context, "LilyPond log", SelfTest.deviceInfo() + "\n" + log.takeLast(SHARED_LOG_CHARS))
                        }) { Text("Share log") }
                    }
                }
                uneven.firstOrNull()?.let { p ->
                    val line = SourcePositions.locationOf(text.text, p.start.coerceAtMost(text.text.length)).first
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFFFF3E0)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⚠ Line $line: voices of unequal length (" + p.lengths.joinToString(" / ") + " whole notes)" +
                                if (uneven.size > 1) ", +${uneven.size - 1} more" else "",
                            fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (p.end <= text.text.length) {
                                text = text.copy(selection = TextRange(p.start, p.end))
                                selectFromCursor(text)
                            }
                        }) { Text("Go there") }
                        TextButton(onClick = { edit(p.start + 2) { src, off -> tools.padVoices(src, off) } }) { Text("Fill with rests") }
                    }
                }
                SelectionInfo(selection, lookup, range.size)
                if (lyricsMode) LyricsBar(
                    hasMelody = lyricAnchor != null,
                    verses = verseList.size,
                    index = verseIndex,
                    field = verseField,
                    count = verseCounts.getOrNull(verseIndex),
                    onField = { verseField = it },
                    onVerse = { i ->
                        verseIndex = i
                        verseField = TextFieldValue(verseList.getOrElse(i) { "" })
                        verseApplied = verseList.getOrElse(i) { "" }
                    },
                ) else if (entering) EntryBar(
                    voices = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.takeIf { text.text == renderedText }
                        ?.let { g -> lookup?.voicesAt(g)?.takeIf { it.size > 1 }?.map { it to (it.sameVoiceAs(g)) } }
                        .orEmpty(),
                    voiceColors = lookup?.map?.grobs?.mapNotNull { it.voice }?.distinct()?.sorted().orEmpty(),
                    onVoice = { g -> onTap(g) },
                    duration = entryDuration,
                    dot = entryDot,
                    keyboardName = connectedKeyboard?.let(MidiKeyboard::name),
                    hasPlace = selectedOffset != null,
                    canUndo = undo.isNotEmpty(),
                    onDuration = { entryDuration = it },
                    onDot = { entryDot = !entryDot },
                    onRest = { entries.trySend(Entry.Keys(emptyList())) },
                    fillRests = fillRests,
                    onFillRests = { fillRests = !fillRests },
                    chordMode = chordMode,
                    onChordMode = { chordMode = !chordMode },
                    recordingBeat = recorder?.beat,
                    bpm = recordBpm,
                    onBpm = { recordBpm = (recordBpm + it).coerceIn(30, 240) },
                    grid = recordGrid,
                    onGrid = { recordGrid = if (recordGrid == "16") "8" else "16" },
                    canTwoHands = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.let { lookup?.alignedBelow(it) } != null,
                    twoHands = twoHands,
                    onTwoHands = { twoHands = !twoHands },
                    onRecord = {
                        if (recorder != null) stopRecording()
                        else if (selectedOffset == null) status = "Select the note to record after."
                        else recorder = Recorder(keyboard, recordBpm).also { it.start(scope) }
                    },
                    onUndo = ::undoLast,
                    canRedo = undo.canRedo,
                    onRedo = ::redoLast,
                ) else {
                    val selected = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" }
                        ?.takeIf { text.text == renderedText }
                    val inRange = range.size >= 2 && selected != null
                    val pitch = selected?.pitch
                    EditBar(
                        enabled = selected != null,
                        pitched = if (inRange) selection.any { it.type == "NoteHead" } else pitch != null,
                        canUndo = undo.isNotEmpty(),
                        onStep = { by ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Step(by)) }
                            else edit { src, off -> tools.step(src, off, by) }
                        },
                        onAlter = { semitones ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Alter(semitones)) }
                            else if (pitch != null) {
                                val p = LilyTools.Pitch(pitch.notename, semitones, pitch.octave)
                                edit { src, off -> tools.setPitch(src, off, p) }
                            }
                        },
                        onDuration = { d ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Duration(d)) }
                            else edit { src, off -> tools.setDuration(src, off, d) }
                        },
                        onDot = {
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Dot) }
                            else selected?.duration?.let { d ->
                                val nd = if (d.endsWith(".")) d.trimEnd('.') else "$d."
                                edit { src, off -> tools.setDuration(src, off, nd) }
                            }
                        },
                        onDelete = {
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Delete) }
                            else edit { src, off -> tools.delete(src, off) }
                        },
                        onRespell = {
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Respell) }
                            else edit { src, off -> tools.respell(src, off) }
                        },
                        onTranspose = { transposing = true },
                        onInsert = { inserting = true },
                        onCopy = { copySelection(cut = false) },
                        onCut = { copySelection(cut = true) },
                        canPaste = clip != null,
                        onPaste = ::pasteClip,
                        onAddVoice = ::addVoice,
                        inVoice = selected?.voiceId?.isNotEmpty() == true,
                        onRemoveVoice = {
                            range = emptyList()
                            edit { src, off -> tools.removeVoice(src, off) }
                        },
                        onUndo = ::undoLast,
                        canRedo = undo.canRedo,
                        onRedo = ::redoLast,
                    )
                    MarksBar(
                        enabled = selected != null,
                        range = inRange,
                        on = selectedMarks,
                        onToggle = { what ->
                            if (inRange) rangeEdit { src, o -> tools.range(src, o, LilyTools.RangeOp.Toggle(what)) }
                            else edit { src, off -> tools.toggle(src, off, what) }
                        },
                        onSpan = ::spanRange,
                    )
                }
                if (entering && !landscape) piano()
                ScoreView(
                    document, lookup, if (playback != null) cursor else selection, ::onTap,
                    onSelectRange = ::onSelectRange,
                    onStaffTap = if (entering) { p -> entries.trySend(Entry.Step(p.step, p.octave)) } else null,
                    modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewHeight = it.height },
                    state = viewState,
                    onDragSteps = { g, steps ->
                        val o = g.origin
                        if (o != null && text.text == renderedText) {
                            val at = SourcePositions.offsetOf(text.text, o.line, o.char)
                            selectedOffset = at
                            edit(at) { src, off -> tools.step(src, off, steps) }
                        }
                    },
                )
            }
        }
        val editor: @Composable (Modifier) -> Unit = { m ->
          Column(m) {
            Box(Modifier.weight(1f).background(Color(0xFFFAFAFA)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { v ->
                        val moved = v.selection != text.selection
                        if (v.text != text.text) range = emptyList() // typed: the offsets have moved
                        text = v
                        if (moved) selectFromCursor(v)
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    visualTransformation = highlighting,
                    // Password mode turns off word suggestions and learning (the text isn't hidden).
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (entering && landscape) piano()
          }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                editor(Modifier.weight(0.4f).fillMaxHeight())
                score(Modifier.weight(0.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                score(Modifier.weight(0.6f).fillMaxWidth())
                editor(Modifier.weight(0.4f).fillMaxWidth())
            }
        }
    }
}

/** Something to enter: notes played or tapped on the piano (MIDI keys; none for a rest), or a place tapped on the staff. */
private sealed interface Entry {
    data class Keys(val keys: List<Int>) : Entry
    /** [step] 0 = c … 6 = b, [octave] 0 = from middle C up; the key signature gives the accidental. */
    data class Step(val step: Int, val octave: Int) : Entry
}

/**
 * Undo and redo of edits, as whole texts: an edit (addLast) makes the
 * previous text undoable and forgets what could be redone.
 */
private class UndoHistory {
    private val back = ArrayDeque<String>()
    private val forward = ArrayDeque<String>()
    /** Read in composition, so the buttons follow. */
    var canRedo by mutableStateOf(false)
        private set

    fun addLast(before: String) {
        back.addLast(before)
        forward.clear()
        canRedo = false
    }

    fun isNotEmpty() = back.isNotEmpty()

    /** The text before the last edit, if any; [current] can then be redone. */
    fun undo(current: String): String? = back.removeLastOrNull()?.also { forward.addLast(current); canRedo = true }

    fun redo(current: String): String? = forward.removeLastOrNull()?.also {
        back.addLast(current)
        canRedo = forward.isNotEmpty()
    }
}

/** Where playback sounds. */
private enum class Sound(val label: String) {
    TABLET("tablet"),
    KEYBOARD("keyboard"),
    /** Apps listening to LilyPond's own MIDI device, e.g. a synthesizer. */
    APPS("MIDI apps"),
}

/**
 * A MIDI file playing, sent to a MIDI keyboard or app ([MidiPlayer]) or on
 * the tablet's own synthesizer ([MediaPlayer]), from a point in the music.
 */
private class Playback private constructor(
    val file: MidiFile,
    private val sequencer: MidiPlayer?,
    private val media: MediaPlayer?,
) {
    @Volatile private var stopped = false

    val active: Boolean get() = !stopped && (sequencer?.playing ?: runCatching { media!!.isPlaying }.getOrDefault(false))

    val positionMicros: Long
        get() = sequencer?.positionMicros ?: runCatching { media!!.currentPosition * 1000L }.getOrDefault(0L)

    fun stop() {
        if (stopped) return
        stopped = true
        sequencer?.stop()
        media?.release()
    }

    companion object {
        /** Starts at [fromWholeNotes] into the music (null: the start), sending to [send] if given. */
        fun start(midi: java.io.File, fromWholeNotes: Double?, send: ((ByteArray) -> Unit)?): Playback {
            val file = MidiFile.read(midi)
            val from = fromWholeNotes?.let { file.micros((it * 4 * file.division).toLong()) } ?: 0L
            return if (send != null) {
                val s = MidiPlayer(file, send)
                s.start(from)
                Playback(file, s, null)
            } else {
                val mp = MediaPlayer()
                mp.setDataSource(midi.path)
                mp.prepare()
                if (from > 0) mp.seekTo((from / 1000).toInt())
                mp.start()
                Playback(file, null, mp)
            }
        }
    }
}

/** The score's titles, paper and staff size. */
@Composable
private fun SettingsDialog(initial: LilyTools.ScoreSettings, onDismiss: () -> Unit, onApply: (LilyTools.ScoreSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    @Composable
    fun Field(label: String, value: String?, onChange: (String) -> Unit) =
        OutlinedTextField(value = value.orEmpty(), onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    @Composable
    fun Chips(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) =
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((value, label) in options) {
                val pad = PaddingValues(horizontal = 8.dp)
                if (value == selected) Button(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
                else OutlinedButton(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Score settings") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Title", s.title) { s = s.copy(title = it) }
                Field("Subtitle", s.subtitle) { s = s.copy(subtitle = it) }
                Field("Composer", s.composer) { s = s.copy(composer = it) }
                Field("Arranger", s.arranger) { s = s.copy(arranger = it) }
                Field("Lyricist", s.poet) { s = s.copy(poet = it) }
                Field("Copyright", s.copyright) { s = s.copy(copyright = it) }
                Text("Paper")
                Chips(listOf(null to "default (A4)", "a4" to "A4", "letter" to "Letter", "a5" to "A5", "a3" to "A3", "legal" to "Legal"), s.paper) { s = s.copy(paper = it) }
                Chips(listOf("portrait" to "portrait", "landscape" to "landscape"), if (s.landscape) "landscape" else "portrait") {
                    s = s.copy(landscape = it == "landscape", paper = s.paper ?: "a4")
                }
                Text("Staff size")
                Chips(
                    listOf(null to "default (20)") + listOf(14, 16, 18, 20, 23, 26).map { "$it" to "$it" },
                    s.staffSize?.let { if (it % 1.0 == 0.0) "${it.toInt()}" else "$it" },
                ) { s = s.copy(staffSize = it?.toDouble()) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(s) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the Insert dialog puts into the music. */
private sealed interface Insertion {
    /** A clef, key, time, tempo or bar line ([kind]) written as [written]. */
    data class Change(val kind: String, val written: String, val after: Boolean = false) : Insertion
    data class Words(val words: String, val above: Boolean) : Insertion
    data class Repeat(val times: Int) : Insertion
}

/** Clef, key, time, tempo and bar line changes, text, and repeats, at the selected note. */
@Composable
private fun InsertDialog(canRepeat: Boolean, onDismiss: () -> Unit, onInsert: (Insertion) -> Unit) {
    var what by remember { mutableStateOf("key") }
    var clef by remember { mutableStateOf("bass") }
    var tonic by remember { mutableStateOf("c") }
    var minor by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("3/4") }
    var bpm by remember { mutableStateOf("100") }
    var tempoWord by remember { mutableStateOf("") }
    var bar by remember { mutableStateOf("||") }
    var barAfter by remember { mutableStateOf(true) }
    var words by remember { mutableStateOf("") }
    var above by remember { mutableStateOf(true) }
    var times by remember { mutableStateOf("2") }
    @Composable
    fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) =
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((value, label) in options) {
                val pad = PaddingValues(horizontal = 8.dp)
                if (value == selected) Button(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
                else OutlinedButton(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert at the selected note") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chips(
                    listOf("clef" to "Clef", "key" to "Key", "time" to "Time", "tempo" to "Tempo", "bar" to "Bar line", "text" to "Text") +
                        if (canRepeat) listOf("repeat" to "Repeat") else emptyList(),
                    what,
                ) { what = it }
                when (what) {
                    "clef" -> Chips(listOf("treble", "bass", "alto", "tenor", "treble_8", "percussion").map { it to it.replace("_8", " 8vb") }, clef) { clef = it }
                    "key" -> {
                        Chips(KEYS.map { it to keyLabel(it) }, tonic) { tonic = it }
                        Chips(listOf("major" to "major", "minor" to "minor"), if (minor) "minor" else "major") { minor = it == "minor" }
                    }
                    "time" -> Chips(TIMES.map { it to it }, time) { time = it }
                    "tempo" -> {
                        OutlinedTextField(value = tempoWord, onValueChange = { tempoWord = it }, label = { Text("Word (e.g. Allegro), optional") }, singleLine = true)
                        OutlinedTextField(
                            value = bpm, onValueChange = { bpm = it.filter(Char::isDigit).take(3) }, label = { Text("♩ = (optional)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    "bar" -> {
                        Chips(listOf("||" to "double", "|." to "final", ".|:" to "start repeat", ":|." to "end repeat", ":..:" to "end and start"), bar) { bar = it }
                        Chips(listOf("before" to "before the note", "after" to "after it"), if (barAfter) "after" else "before") { barAfter = it == "after" }
                    }
                    "text" -> {
                        OutlinedTextField(value = words, onValueChange = { words = it }, label = { Text("Text, e.g. dolce") }, singleLine = true)
                        Chips(listOf("above" to "above", "below" to "below"), if (above) "above" else "below") { above = it == "above" }
                    }
                    "repeat" -> {
                        Text("Repeats the selected notes (one voice), with repeat signs.", fontSize = 13.sp)
                        Chips(listOf("2", "3", "4").map { it to "$it times" }, times) { times = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val insertion = when (what) {
                    "clef" -> Insertion.Change("clef", if (clef.contains("_")) "\\clef \"$clef\"" else "\\clef $clef")
                    "key" -> Insertion.Change("key", "\\key $tonic \\" + if (minor) "minor" else "major")
                    "time" -> Insertion.Change("time", "\\time $time")
                    "tempo" -> {
                        val w = tempoWord.trim().takeIf { it.isNotEmpty() }?.let { "\"${it.replace("\"", "")}\"" }
                        val n = bpm.toIntOrNull()?.let { "4 = $it" }
                        if (w == null && n == null) null else Insertion.Change("tempo", "\\tempo " + listOfNotNull(w, n).joinToString(" "))
                    }
                    "bar" -> Insertion.Change("bar", "\\bar \"$bar\"", after = barAfter)
                    "text" -> words.trim().takeIf { it.isNotEmpty() }?.let { Insertion.Words(it, above) }
                    "repeat" -> Insertion.Repeat(times.toInt())
                    else -> null
                }
                if (insertion != null) onInsert(insertion) else onDismiss()
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A new score: template, title and composer, key, time, tempo, number of bars. */
@Composable
private fun NewScoreDialog(templates: List<Pair<String, String>>, onDismiss: () -> Unit, onCreate: (LilyTools.NewScore) -> Unit) {
    var template by remember { mutableStateOf(templates.first().first) }
    var title by remember { mutableStateOf("") }
    var composer by remember { mutableStateOf("") }
    var tonic by remember { mutableStateOf("c") }
    var minor by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("4/4") }
    var tempo by remember { mutableStateOf("") }
    var bars by remember { mutableStateOf("8") }
    @Composable
    fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) =
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((value, label) in options) {
                val pad = PaddingValues(horizontal = 8.dp)
                if (value == selected) Button(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
                else OutlinedButton(onClick = { onSelect(value) }, contentPadding = pad) { Text(label) }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New score") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, name) in templates) {
                    Row(Modifier.fillMaxWidth().clickable { template = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = template == id, onClick = { template = id })
                        Text(name)
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text("Composer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Key")
                Chips(KEYS.map { it to keyLabel(it) }, tonic) { tonic = it }
                Chips(listOf("major" to "major", "minor" to "minor"), if (minor) "minor" else "major") { minor = it == "minor" }
                Text("Time")
                Chips(TIMES.map { it to it }, time) { time = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempo, onValueChange = { tempo = it.filter(Char::isDigit).take(3) },
                        label = { Text("Tempo ♩=") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = bars, onValueChange = { bars = it.filter(Char::isDigit).take(3) },
                        label = { Text("Bars") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    LilyTools.NewScore(
                        template, title.trim(), composer.trim(), tonic, minor, time,
                        tempo.toIntOrNull()?.takeIf { it in 20..400 }, (bars.toIntOrNull() ?: 8).coerceIn(1, 500),
                    ),
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Key tonics for a new score, as LilyPond writes them, from flats to sharps. */
private val KEYS = listOf("ges", "des", "as", "es", "bes", "f", "c", "g", "d", "a", "e", "b", "fis", "cis")

/** "fis" -> "F♯", "bes" -> "B♭". */
private fun keyLabel(tonic: String): String {
    val letter = tonic.first().uppercaseChar()
    return when {
        tonic.length == 1 -> "$letter"
        tonic.endsWith("is") -> "$letter♯"
        else -> "$letter♭"
    }
}

private val TIMES = listOf("4/4", "3/4", "2/4", "2/2", "6/8", "3/8", "9/8", "12/8", "5/4")

/** Transposing by an interval: up or down, from a minor second to an octave. */
@Composable
private fun TransposeDialog(what: String, onDismiss: () -> Unit, onTranspose: (toPitch: String) -> Unit) {
    var up by remember { mutableStateOf(true) }
    var interval by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transpose $what") },
        text = {
            Column {
                Row {
                    for ((label, value) in listOf("Up" to true, "Down" to false)) {
                        Row(Modifier.clickable { up = value }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = up == value, onClick = { up = value })
                            Text(label)
                        }
                    }
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    INTERVALS.forEachIndexed { i, (name, _, _) ->
                        Row(Modifier.fillMaxWidth().clickable { interval = i }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = interval == i, onClick = { interval = i })
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTranspose(INTERVALS[interval].let { (_, upTo, downTo) -> if (up) upTo else downTo }) }) { Text("Transpose") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Intervals for transposing: name, and where c goes up and down (as LilyPond pitches). */
private val INTERVALS = listOf(
    Triple("Minor second", "des", "b,"),
    Triple("Major second", "d", "bes,"),
    Triple("Minor third", "es", "a,"),
    Triple("Major third", "e", "as,"),
    Triple("Perfect fourth", "f", "g,"),
    Triple("Augmented fourth", "fis", "ges,"),
    Triple("Perfect fifth", "g", "f,"),
    Triple("Minor sixth", "as", "e,"),
    Triple("Major sixth", "a", "es,"),
    Triple("Minor seventh", "bes", "d,"),
    Triple("Major seventh", "b", "des,"),
    Triple("Octave", "c'", "c,"),
)

/** Options for musicxml2ly. */
@Composable
private fun MusicXmlImportDialog(onDismiss: () -> Unit, onImport: (LilyTools.MusicXmlImportOptions) -> Unit) {
    var absolute by remember { mutableStateOf(false) }
    var keepLayout by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import MusicXML") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { absolute = !absolute }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = absolute, onCheckedChange = { absolute = it })
                    Text("Absolute pitches (not \\relative)")
                }
                Row(Modifier.fillMaxWidth().clickable { keepLayout = !keepLayout }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepLayout, onCheckedChange = { keepLayout = it })
                    Text("Keep the file's page size and line breaks")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(LilyTools.MusicXmlImportOptions(absolute, keepLayout)) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What Open offers in the file picker: LilyPond files are often typed loosely. */
private val LY_TYPES = arrayOf("text/x-lilypond", "text/plain", "application/octet-stream", "*/*")

/** What Import offers in the file picker: MIDI and MusicXML (some apps label files loosely). */
private val IMPORT_TYPES = arrayOf(
    "audio/midi", "audio/x-midi", "audio/sp-midi",
    "application/vnd.recordare.musicxml+xml", "application/vnd.recordare.musicxml",
    "application/xml", "text/xml", "application/zip", "application/octet-stream",
)

/** Options for midi2ly: how finely to quantise, and whether triplets are allowed. */
@Composable
private fun MidiImportDialog(
    /** The file's tracks with notes, or null while they are being read. */
    tracks: List<LilyTools.MidiTrack>?,
    onDismiss: () -> Unit,
    onImport: (LilyTools.MidiImportOptions) -> Unit,
) {
    var quant by remember { mutableStateOf("16") }
    var triplets by remember { mutableStateOf(false) }
    /** The tracks to import: all but drums at first (midi2ly writes drums as pitched notes). */
    var chosen by remember(tracks) { mutableStateOf(tracks.orEmpty().filter { !it.drums }.map { it.index }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import MIDI") },
        text = {
            Column {
                Text("Round note starts and lengths to:")
                for ((q, label) in listOf("8" to "eighth notes", "16" to "sixteenth notes", "32" to "thirty-second notes")) {
                    Row(Modifier.fillMaxWidth().clickable { quant = q }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = quant == q, onClick = { quant = q })
                        Text(label)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { triplets = !triplets }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = triplets, onCheckedChange = { triplets = it })
                    Text("Allow triplets")
                }
                if (tracks == null) {
                    Text("Reading the tracks…", fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                } else if (tracks.size > 1) {
                    Text("Tracks (each becomes a staff; fewer engrave much faster):", modifier = Modifier.padding(top = 6.dp))
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        for (t in tracks) {
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = if (t.index in chosen) chosen - t.index else chosen + t.index },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = t.index in chosen, onCheckedChange = { chosen = if (it) chosen + t.index else chosen - t.index })
                                Text(
                                    t.name + listOfNotNull(t.instrument, "${t.notes} notes").joinToString(", ", " (", ")"),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Uses LilyPond's midi2ly. Notes that sound together in a track become chords.",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = tracks != null && (tracks.size <= 1 || chosen.isNotEmpty()), onClick = {
                val tupletUnit = (quant.toInt() / 2).coerceAtLeast(4)
                val all = tracks.orEmpty().map { it.index }.toSet()
                onImport(
                    LilyTools.MidiImportOptions(
                        durationQuant = quant, startQuant = quant,
                        tuplets = if (triplets) listOf("$tupletUnit*2/3", "${tupletUnit * 2}*2/3") else emptyList(),
                        tracks = if (tracks.orEmpty().size <= 1 || chosen == all) null else chosen.sorted(),
                    ),
                )
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Note entry: the duration for new notes, a rest, and undo. Notes come
 * from a MIDI keyboard (a chord played on it comes in whole) or [PianoBar].
 */
@Composable
private fun EntryBar(
    /** The voices on the selected note's staff at its moment, with the one it is in; empty for one voice. */
    voices: List<Pair<Grob, Boolean>>,
    voiceColors: List<Int>,
    onVoice: (Grob) -> Unit,
    duration: String,
    dot: Boolean,
    keyboardName: String?,
    hasPlace: Boolean,
    canUndo: Boolean,
    onDuration: (String) -> Unit,
    onDot: () -> Unit,
    onRest: () -> Unit,
    fillRests: Boolean,
    onFillRests: () -> Unit,
    /** Notes join the selected note or chord. */
    chordMode: Boolean,
    onChordMode: () -> Unit,
    /** While recording: the beat (negative while counting in); null when not recording. */
    recordingBeat: Int?,
    bpm: Int,
    onBpm: (Int) -> Unit,
    grid: String,
    onGrid: () -> Unit,
    /** The selected note has a staff below to record a left hand into. */
    canTwoHands: Boolean,
    twoHands: Boolean,
    onTwoHands: () -> Unit,
    onRecord: () -> Unit,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(ENTRY_BACKGROUND).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (voices.isNotEmpty()) {
                // Switch the voice new notes go into: its note at the same moment is selected.
                voices.forEachIndexed { i, (g, current) ->
                    val label = "Voice " + (g.voiceId?.toIntOrNull() ?: (i + 1))
                    val color = voiceColor(g.voice, voiceColors)
                    val pad = PaddingValues(horizontal = 10.dp)
                    if (current) Button(onClick = { onVoice(g) }, contentPadding = pad, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text(label) }
                    else OutlinedButton(onClick = { onVoice(g) }, contentPadding = pad) { Text(label, color = color) }
                }
                Spacer(Modifier.width(8.dp))
            }
            Text("New notes:", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            for (d in listOf("1", "2", "4", "8", "16", "32")) {
                if (d == duration) Button(onClick = { onDuration(d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(d) }
                else OutlinedButton(onClick = { onDuration(d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(d) }
            }
            if (dot) Button(onClick = onDot, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Dot") }
            else OutlinedButton(onClick = onDot, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Dot") }
            OutlinedButton(onClick = onRest, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Rest") }
            if (fillRests) Button(onClick = onFillRests, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Fill rests") }
            else OutlinedButton(onClick = onFillRests, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Fill rests") }
            if (chordMode) Button(onClick = onChordMode, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Chord") }
            else OutlinedButton(onClick = onChordMode, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Chord") }
            Spacer(Modifier.width(8.dp))
            // Real-time recording: tempo, rhythm grid, record / stop.
            if (recordingBeat == null) {
                OutlinedButton(onClick = { onBpm(-5) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−") }
                Text("♩=$bpm", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                OutlinedButton(onClick = { onBpm(5) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+") }
                OutlinedButton(onClick = onGrid, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("grid 1/$grid") }
                if (canTwoHands) {
                    if (twoHands) Button(onClick = onTwoHands, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Two hands") }
                    else OutlinedButton(onClick = onTwoHands, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Two hands") }
                }
                Button(
                    onClick = onRecord, contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("● Record") }
            } else {
                Button(onClick = onRecord, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("■ Stop") }
                Text(
                    if (recordingBeat < 0) "Count-in: ${recordingBeat + 5}…" else "● Recording, beat ${recordingBeat + 1}",
                    fontSize = 14.sp, color = Color(0xFFC62828), modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            OutlinedButton(onClick = onUndo, enabled = canUndo, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Undo") }
            OutlinedButton(onClick = onRedo, enabled = canRedo, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Redo") }
            Text(
                (if (hasPlace && chordMode) "Into the selected chord. " else if (hasPlace) "After the selected note. " else "At the cursor. ") +
                    (keyboardName?.let { "Play on $it, tap the piano, or tap a line or space on the staff." }
                        ?: "Tap the piano or a line or space on the staff, or plug in a MIDI keyboard."),
                fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

private val ENTRY_BACKGROUND = Color(0xFFEEF3FA)

/** The on-screen piano with octave buttons. */
@Composable
private fun PianoBar(onKey: (Int) -> Unit) {
    var low by remember { mutableIntStateOf(60) }
    Row(Modifier.fillMaxWidth().background(ENTRY_BACKGROUND), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { low = (low - 12).coerceAtLeast(24) }) { Text("◀") }
        Piano(low, octaves = 2, onKey = onKey, modifier = Modifier.weight(1f).height(72.dp))
        TextButton(onClick = { low = (low + 12).coerceAtMost(84) }) { Text("▶") }
    }
}

/** A piano keyboard from [low] (a C) over [octaves] octaves and one more C; taps give MIDI keys. */
@Composable
private fun Piano(low: Int, octaves: Int, onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    val whiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whites = octaves * 7 + 1
    fun whiteKey(i: Int) = low + 12 * (i / 7) + whiteSteps[i % 7]
    fun hasBlackAfter(i: Int) = i < whites - 1 && i % 7 !in intArrayOf(2, 6)
    Canvas(
        modifier.pointerInput(low) {
            detectTapGestures { p ->
                val w = size.width.toFloat() / whites
                val i = (p.x / w).toInt().coerceIn(0, whites - 1)
                val key = if (p.y < size.height * 0.6f) {
                    val frac = p.x / w - i
                    when {
                        frac > 0.7f && hasBlackAfter(i) -> whiteKey(i) + 1
                        frac < 0.3f && i > 0 && hasBlackAfter(i - 1) -> whiteKey(i - 1) + 1
                        else -> whiteKey(i)
                    }
                } else whiteKey(i)
                onKey(key)
            }
        },
    ) {
        val w = size.width / whites
        for (i in 0 until whites) {
            drawRect(if (whiteKey(i) == 60) Color(0xFFFFF4D6) else Color.White, Offset(i * w, 0f), Size(w, size.height))
            drawRect(Color.Gray, Offset(i * w, 0f), Size(w, size.height), style = Stroke(1f))
        }
        for (i in 0 until whites) if (hasBlackAfter(i)) {
            drawRect(Color.Black, Offset((i + 0.7f) * w, 0f), Size(0.6f * w, size.height * 0.6f))
        }
    }
}

/**
 * Lyrics for the selected note's melody: a verse typed as plain text
 * ("Twin-kle twin-kle lit-tle star_"), and buttons for the verses.
 */
@Composable
private fun LyricsBar(
    hasMelody: Boolean,
    verses: Int,
    index: Int,
    field: TextFieldValue,
    /** How this verse fits the melody, if known. */
    count: LilyTools.VerseCount?,
    onField: (TextFieldValue) -> Unit,
    onVerse: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(ENTRY_BACKGROUND).padding(horizontal = 6.dp, vertical = 2.dp)) {
        if (!hasMelody) {
            Text("Tap a note of the melody that the words are for.", fontSize = 13.sp, modifier = Modifier.padding(6.dp))
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Verse:", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            val pad = PaddingValues(horizontal = 10.dp)
            for (i in 0 until maxOf(verses, 1)) {
                if (i == index) Button(onClick = { onVerse(i) }, contentPadding = pad) { Text("${i + 1}") }
                else OutlinedButton(onClick = { onVerse(i) }, contentPadding = pad) { Text("${i + 1}") }
            }
            if (verses > 0 && index < verses) OutlinedButton(onClick = { onVerse(verses) }, contentPadding = pad) { Text("+ verse") }
            count?.let { c ->
                val fits = c.syllables == c.notes
                Text(
                    if (fits) "✓ ${c.syllables} syllables, one per note"
                    else "${c.syllables} syllables for ${c.notes} notes" + if (c.syllables < c.notes) " (${c.notes - c.syllables} short)" else " (${c.syllables - c.notes} too many)",
                    fontSize = 13.sp,
                    color = if (fits) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                "Words with spaces; a-part for syllables; word_ to hold it; _ to skip a note.",
                fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        OutlinedTextField(
            value = field,
            onValueChange = onField,
            placeholder = { Text("Twin-kle twin-kle lit-tle star_") },
            textStyle = TextStyle(fontSize = 16.sp),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Articulations, dynamics, slurs, ties and hairpins for the selected note; those on it are filled. */
@Composable
private fun MarksBar(
    enabled: Boolean,
    /** A range is selected: the lines go over it, from its first to its last note in each voice. */
    range: Boolean,
    on: Set<String>,
    onToggle: (String) -> Unit,
    onSpan: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pad = PaddingValues(horizontal = 8.dp)
        for ((group, marks) in MARKS) {
            Text(group, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
            if (group == LINES && range) {
                for ((kind, label) in RANGE_LINES) OutlinedButton(onClick = { onSpan(kind) }, enabled = enabled, contentPadding = pad) { Text(label) }
                for ((what, label) in marks.filter { it.first == "tie" }) {
                    if (what in on) Button(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                    else OutlinedButton(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                }
                continue
            }
            for ((what, label) in marks) {
                if (what in on) Button(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
                else OutlinedButton(onClick = { onToggle(what) }, enabled = enabled, contentPadding = pad) { Text(label) }
            }
        }
    }
}

private const val LINES = "Lines"

/** With a range selected, slurs and hairpins go over it (LilyTools.span's kinds). */
private val RANGE_LINES = listOf("slur" to "slur ( )", "cresc" to "cresc <", "decresc" to "dim >")

/** Where the source being edited is kept between sessions (in the app's files). */
private const val DRAFT_FILE = "score-draft.ly"

/** How long after a change the draft is saved. */
private const val DRAFT_SAVE_DELAY_MS = 1000L

/** Whether two note heads or rests are in the same voice. */
private fun Grob.sameVoiceAs(other: Grob) = voice == other.voice && voiceId == other.voiceId

/** Where word [n] (0-based, separated by spaces) starts in [text]. */
private fun wordStart(text: String, n: Int): Int {
    var word = -1
    var i = 0
    while (i < text.length) {
        if (!text[i].isWhitespace() && (i == 0 || text[i - 1].isWhitespace())) {
            word++
            if (word == n) return i
        }
        i++
    }
    return text.length
}

/** Recording with two hands: from this key up (middle C) to the upper staff, below it to the lower. */
private const val SPLIT_KEY = 60

/** How long after typing stops the lyrics are written into the source. */
private const val LYRICS_APPLY_DELAY_MS = 600L

/** How long after a change the voices are checked for equal lengths. */
private const val VOICE_CHECK_DELAY_MS = 700L

/** Above this many items in a range, the marks on all of them aren't looked up. */
private const val MAX_MARKS_LOOKUPS = 40

/** The marks bar's buttons: what LilyTools.toggle calls each mark, and its label. */
private val MARKS = listOf(
    "Marks" to listOf(
        "staccato" to "•", "accent" to ">", "tenuto" to "–", "marcato" to "^",
        "staccatissimo" to "▾", "fermata" to "fermata", "trill" to "tr",
    ),
    "Dynamics" to listOf("pp", "p", "mp", "mf", "f", "ff", "sfz").map { it to it },
    LINES to listOf(
        "slur-start" to "(", "slur-end" to ")", "tie" to "tie",
        "cresc" to "cresc <", "decresc" to "dim >", "hairpin-end" to "end",
    ),
)

/** Pitch, duration and delete buttons for the selected note (or rest). */
@Composable
private fun EditBar(
    enabled: Boolean,
    /** There is a note to change the pitch of (not only rests). */
    pitched: Boolean,
    canUndo: Boolean,
    onStep: (Int) -> Unit,
    /** A new accidental: 1 sharp, -1 flat, 0 natural. */
    onAlter: (Int) -> Unit,
    /** The other spelling, same pitch (cis <-> des). */
    onRespell: () -> Unit,
    onDuration: (String) -> Unit,
    onDot: () -> Unit,
    onDelete: () -> Unit,
    onTranspose: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    canPaste: Boolean,
    onPaste: () -> Unit,
    onAddVoice: () -> Unit,
    /** The selection is in one of several voices, so its voice can be removed. */
    inVoice: Boolean,
    onRemoveVoice: () -> Unit,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun B(label: String, enabled: Boolean = true, onClick: () -> Unit) =
            OutlinedButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
        B("▲", pitched) { onStep(+1) }
        B("▼", pitched) { onStep(-1) }
        B("♯", pitched) { onAlter(1) }
        B("♭", pitched) { onAlter(-1) }
        B("♮", pitched) { onAlter(0) }
        B("♯⇄♭", pitched, onRespell)
        for (d in listOf("1", "2", "4", "8", "16")) B(d, enabled) { onDuration(d) }
        B("Dot", enabled, onDot)
        B("Delete", enabled, onDelete)
        B("Copy", enabled, onCopy)
        B("Cut", enabled, onCut)
        B("Paste", enabled && canPaste, onPaste)
        B("Insert…", enabled, onInsert)
        B("Transpose…", true, onTranspose)
        B("Add voice", enabled, onAddVoice)
        B("Remove voice", enabled && inVoice, onRemoveVoice)
        B("Undo", canUndo, onUndo)
        B("Redo", canRedo, onRedo)
    }
}

private object ScoreMapRational {
    fun parse(s: String): Double {
        val slash = s.indexOf('/')
        return if (slash < 0) s.toDouble() else s.substring(0, slash).toDouble() / s.substring(slash + 1).toDouble()
    }
}

@Composable
private fun SelectionInfo(selection: List<Grob>, lookup: ScoreLookup?, rangeItems: Int) {
    val g = selection.firstOrNull { it.type == "NoteHead" || it.type == "Rest" } ?: selection.firstOrNull()
    val voices = remember(lookup) { lookup?.map?.grobs?.mapNotNull { it.voice }?.distinct()?.sorted().orEmpty() }
    // One line, always: a taller line would move the score under the pen whenever the selection changes.
    Row(
        Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (g == null) {
            Text("Tap a note, or draw a box around notes with the pen.", fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        if (rangeItems >= 2) {
            val inRange = selection.filter { it.type == "NoteHead" || it.type == "Rest" }
            for (v in inRange.map { it.voice }.distinct()) Box(Modifier.size(12.dp).background(voiceColor(v, voices)))
            val heads = inRange.count { it.type == "NoteHead" }
            val rests = inRange.count { it.type == "Rest" }
            val voiceCount = inRange.map { it.voiceId }.distinct().size
            Text(
                listOfNotNull(
                    heads.takeIf { it > 0 }?.let { "$it note${if (it > 1) "s" else ""}" },
                    rests.takeIf { it > 0 }?.let { "$it rest${if (it > 1) "s" else ""}" },
                ).joinToString(" and ") + " selected" + if (voiceCount > 1) ", in $voiceCount voices" else "",
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }
        Box(Modifier.size(12.dp).background(voiceColor(g.voice, voices)))
        val parts = buildList {
            add(g.type)
            g.pitch?.let { add(it.name) }
            g.duration?.let { add("duration $it") }
            g.voice?.let { add(g.voiceId?.takeIf { id -> id.isNotEmpty() }?.let { id -> "voice \"$id\"" } ?: "unnamed voice") }
            g.moment?.let { add("at $it") }
            g.origin?.let { add("line ${it.line}, column ${it.char + 1}") }
        }
        Text(parts.joinToString(" · "), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Above this, an edit first engraves only the bars around it (see [ScoreLookup.windowAround]). */
private const val PREVIEW_THRESHOLD_MS = 1500L

/** How much of an imported file is engraved first, in whole notes: 4 bars of 4/4. */
private const val FIRST_LOOK_WHOLE_NOTES = "4"

/** The end of a failed render's log that "Share log" sends (the start is mostly warnings). */
private const val SHARED_LOG_CHARS = 20_000

/**
 * A whole-score render may take this long before it's given up: a long
 * orchestral import takes minutes on a tablet, and a new edit cancels it anyway.
 */
private const val WHOLE_SCORE_TIMEOUT_S = 1800L

/** 400 bars of piano music with two voices in the right hand: about 15 pages. */
private val LONG_SAMPLE = pianoPiece(400)

/** Piano music with two voices in the right hand, and a chord, over a few systems. */
internal val SCORE_SAMPLE = """
\version "2.26.0"
\header { title = "Point and click" }
upper = \relative c'' {
  \key g \major \time 4/4
  << { b4 c d2 | e4 d c b | a2 b4 c | d1 } \\ { g,2 fis | g4 a fis g | e2 g4 a | <fis a>1 } >>
  g4 fis e d | c2 b | a4 b c a | g1 \bar "|."
}
lower = \relative c {
  \clef bass \key g \major
  g2 d' | c b | c g | d'1 |
  e4 d c b | a2 g | d' d, | g1
}
\score {
  \new PianoStaff << \new Staff \upper \new Staff \lower >>
  \layout { }
}
""".trimStart()
