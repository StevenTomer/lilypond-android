package org.lilyengine.scoreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.lilyengine.engine.ScoreMap.Grob
import kotlin.math.roundToInt

/** Zoom and scroll position of a [ScoreView]; survives re-renders of the score. */
@Stable
class ScoreViewState {
    val list = LazyListState()

    /** 1 = pages as wide as the view. */
    var scale by mutableFloatStateOf(1f)
        private set

    /** Horizontal pan in pixels (0 or negative), for zoomed-in pages. */
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** Set while a note is being dragged: one-finger panning stops. */
    internal var noteDrag = false

    /** The zoom pages are drawn sharp for; follows [scale] once a pinch ends. */
    internal var renderScale by mutableFloatStateOf(1f)
    internal var viewportWidth = 0f

    internal fun zoomBy(factor: Float, focus: Offset) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        if (ratio == 1f) return
        // Keep the page point under the fingers where it is.
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.lastOrNull { it.offset <= focus.y } ?: info.visibleItemsInfo.firstOrNull()
        if (item != null) {
            val within = focus.y - item.offset
            list.requestScrollToItem(item.index, (within * ratio - focus.y).roundToInt().coerceAtLeast(0))
        }
        offsetX = focus.x - (focus.x - offsetX) * ratio
        scale = newScale
        clampX()
    }

    internal fun panX(dx: Float) {
        offsetX += dx
        clampX()
    }

    private fun clampX() {
        offsetX = offsetX.coerceIn(viewportWidth - viewportWidth * scale, 0f)
    }

    /** Scrolls so that a point [topFraction] of the way down 1-based [page] is in view, if it isn't already. */
    suspend fun reveal(page: Int, topFraction: Float, viewportHeight: Int) {
        val index = page - 1
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            val y = item.offset + item.size * topFraction
            if (y > viewportHeight * 0.1f && y < viewportHeight * 0.85f) return
            list.animateScrollToItem(index, (item.size * topFraction - viewportHeight * 0.3f).roundToInt().coerceAtLeast(0))
        } else {
            list.scrollToItem(index)
            val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: return
            list.scrollToItem(index, (size * topFraction - viewportHeight * 0.3f).roundToInt().coerceAtLeast(0))
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        /** Sharp rendering stops here; beyond it pages are scaled up (bitmaps get too big). */
        const val MAX_RENDER_SCALE = 2.5f
    }
}

@Composable
fun rememberScoreViewState(): ScoreViewState = remember { ScoreViewState() }

/** Colours for each Voice, so the voices of a `\\` passage are told apart. */
val VOICE_COLORS = listOf(
    Color(0xFF1565C0), // blue
    Color(0xFF2E7D32), // green
    Color(0xFFC62828), // red
    Color(0xFF6A1B9A), // purple
    Color(0xFFEF6C00), // orange
)

/** A stable colour for a grob's Voice context. */
fun voiceColor(voiceIndex: Int?, voices: List<Int>): Color {
    val i = voices.indexOf(voiceIndex)
    return if (i < 0) Color.DarkGray else VOICE_COLORS[i % VOICE_COLORS.size]
}

/**
 * Shows the pages of a rendered score, one under another, with the score
 * map's objects selectable. Pinch to zoom; drag to scroll. Tapping (finger or
 * pen) reports the object under the point, or null for empty space; with a
 * pen, hovering above the screen outlines the object the pen points at.
 *
 * [selection] is drawn in each object's voice colour. The previous pages stay
 * on screen until a new [document]'s pages are drawn, so re-rendering after
 * an edit doesn't flash.
 */
@Composable
fun ScoreView(
    document: ScoreDocument?,
    lookup: ScoreLookup?,
    selection: List<Grob>,
    onTap: (Grob?) -> Unit,
    modifier: Modifier = Modifier,
    state: ScoreViewState = rememberScoreViewState(),
    /**
     * Called when a selected note head is dragged up or down and released:
     * the number of staff positions (lines and spaces) it moved, up being
     * positive. A pen drags at once; a finger after a long press.
     */
    onDragSteps: ((Grob, Int) -> Unit)? = null,
    /**
     * Called when the pen draws a box (from anywhere but a selected note
     * head): the note heads and rests it touches, on one page. A finger
     * still scrolls.
     */
    onSelectRange: ((List<Grob>) -> Unit)? = null,
    /**
     * Note entry on the staff: when set, a tap on a staff line or space (not
     * on an object) reports the place and its note, and the pen's hover shows
     * a ghost note there.
     */
    onStaffTap: ((ScoreLookup.StaffPitch) -> Unit)? = null,
) {
    var hover by remember { mutableStateOf<Grob?>(null) }
    /** Where a note would go, under the hovering pen, in note entry. */
    var ghost by remember { mutableStateOf<Pair<Int, ScoreLookup.StaffPitch>?>(null) }
    val currentOnStaffTap by rememberUpdatedState(onStaffTap)
    // Read inside the drag gesture, which mustn't restart when a long press selects a note.
    val currentSelection by rememberUpdatedState(selection)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragSteps by rememberUpdatedState(onDragSteps)
    val currentOnSelectRange by rememberUpdatedState(onSelectRange)
    /** A box being drawn with the pen: its page, and its corners in points. */
    var lasso by remember { mutableStateOf<Pair<Int, Rect>?>(null) }
    val haptics = LocalHapticFeedback.current
    /** A note head being dragged with the pen, and by how many staff positions. */
    var drag by remember { mutableStateOf<Pair<Grob, Int>?>(null) }
    val voices = remember(lookup) {
        lookup?.map?.grobs?.mapNotNull { it.voice }?.distinct()?.sorted().orEmpty()
    }
    // Pinch ends: draw sharp at the new zoom shortly after.
    LaunchedEffect(state.scale) {
        delay(250)
        state.renderScale = state.scale.coerceAtMost(ScoreViewState.MAX_RENDER_SCALE)
    }
    val pages = document?.pageSizes.orEmpty()

    BoxWithConstraints(
        modifier
            .clipToBounds() // zoomed pages are wider than the view
            .background(Color(0xFFE0E0E0))
            .onSizeChanged { state.viewportWidth = it.width.toFloat() }
            .pointerInput(state) { pinchAndPan(state) },
    ) {
        val density = LocalDensity.current
        val contentWidth = maxWidth * state.scale
        LazyColumn(
            state = state.list,
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .requiredWidth(contentWidth)
                .graphicsLayer { translationX = state.offsetX },
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pages) { index, (wPt, hPt) ->
                val page = index + 1
                val renderWidth = (with(density) { maxWidth.toPx() } * state.renderScale).roundToInt()
                var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(document, renderWidth) {
                    document?.render(index, renderWidth)?.let { bitmap = it.asImageBitmap() }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(wPt / hPt)
                        .background(Color.White)
                        .pointerInput(lookup, page, wPt) {
                            detectTapGestures { off ->
                                val k = wPt / size.width
                                val x = (off.x * k).toDouble()
                                val y = (off.y * k).toDouble()
                                val g = lookup?.objectAt(page, x, y)
                                val place = if (g == null) currentOnStaffTap?.let { lookup?.pitchAt(page, x, y) } else null
                                if (place != null) currentOnStaffTap?.invoke(place) else currentOnTap(g)
                            }
                        }
                        .pointerInput(lookup, page, wPt, onDragSteps != null, onSelectRange != null) {
                            if (lookup == null || (currentOnDragSteps == null && currentOnSelectRange == null)) return@pointerInput
                            awaitEachGesture {
                                // The Initial pass reaches this page before the list sees the
                                // event, so a drag claimed here never scrolls or pans.
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val k = wPt / size.width
                                val x = (down.position.x * k).toDouble()
                                val y = (down.position.y * k).toDouble()
                                val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                                val grabbed = if (pen) {
                                    lookup.objectAt(page, x, y)?.takeIf { it.type == "NoteHead" && it in currentSelection && currentOnDragSteps != null }
                                } else null
                                if (pen && grabbed == null) {
                                    // Not on a selected note: the pen draws a box to select a range.
                                    if (currentOnSelectRange != null) {
                                        drawBox(down, k, page, lookup, state, show = { lasso = it }) { currentOnSelectRange?.invoke(it) }
                                    }
                                    return@awaitEachGesture
                                }
                                val g = if (pen) {
                                    grabbed!!
                                } else if (currentOnDragSteps == null) {
                                    return@awaitEachGesture
                                } else {
                                    // A fingertip covers more than a note head, and the point it
                                    // reports is often off the head: take the nearest selected
                                    // note, or else any note in reach (selected on the long press).
                                    val slop = maxOf(FINGER_REACH.toPx() * k / lookup.pointsPerStaffSpace, 0.6)
                                    lookup.nearestOf(currentSelection.filter { it.type == "NoteHead" }, page, x, y, slop)
                                        ?: lookup.objectAt(page, x, y, slop)?.takeIf { it.type == "NoteHead" }
                                } ?: return@awaitEachGesture
                                if (!pen) {
                                    // A finger has to hold still on the note first (long press);
                                    // otherwise it's a tap or a scroll as usual.
                                    val aborted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val c = awaitPointerEvent(PointerEventPass.Initial).changes
                                                .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                            if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                return@withTimeoutOrNull true
                                            }
                                        }
                                        @Suppress("UNREACHABLE_CODE") false
                                    }
                                    if (aborted == true) return@awaitEachGesture
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (g !in currentSelection) currentOnTap(g)
                                }
                                state.noteDrag = true
                                down.consume()
                                // Half a staff space (one line or space) in pixels.
                                val stepPx = ((g.staffSpace ?: 1.0) * lookup.pointsPerStaffSpace / 2 / k).toFloat()
                                drag = g to 0
                                // Summed from each move rather than measured from the first
                                // touch: selecting the note can shift the page under the finger.
                                var dy = 0f
                                try {
                                    while (true) {
                                        val c = awaitPointerEvent(PointerEventPass.Initial).changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        c.consume() // not a scroll, pan or tap
                                        dy += c.position.y - c.previousPosition.y
                                        drag = g to -(dy / stepPx).roundToInt()
                                        if (!c.pressed) break
                                    }
                                    val steps = drag?.second ?: 0
                                    if (steps != 0) currentOnDragSteps?.invoke(g, steps)
                                } finally {
                                    drag = null
                                    state.noteDrag = false
                                }
                            }
                        }
                        .pointerInput(lookup, page, wPt) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull() ?: continue
                                    if (c.type != PointerType.Stylus) continue
                                    hover = when {
                                        e.type == PointerEventType.Exit -> null
                                        c.pressed -> hover
                                        else -> {
                                            val k = wPt / size.width
                                            lookup?.objectAt(page, (c.position.x * k).toDouble(), (c.position.y * k).toDouble())
                                        }
                                    }
                                    ghost = when {
                                        e.type == PointerEventType.Exit || c.pressed || hover != null || currentOnStaffTap == null -> null
                                        else -> {
                                            val k = wPt / size.width
                                            lookup?.pitchAt(page, (c.position.x * k).toDouble(), (c.position.y * k).toDouble())?.let { page to it }
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    bitmap?.let {
                        Image(it, contentDescription = "Page $page", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                    }
                    if (lookup != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val px = size.width / wPt
                            fun box(g: Grob, pad: Float): Pair<Offset, Size> {
                                val r = lookup.pageRect(g)
                                val p = pad * lookup.pointsPerStaffSpace.toFloat() * px
                                return Offset(r[0] * px - p, r[1] * px - p) to
                                    Size((r[2] - r[0]) * px + 2 * p, (r[3] - r[1]) * px + 2 * p)
                            }
                            for (g in selection) {
                                if (g.page != page || g.book != lookup.book) continue
                                val (o, s) = box(g, 0.25f)
                                val color = voiceColor(g.voice, voices)
                                drawRect(color.copy(alpha = 0.4f), o, s)
                                drawRect(color, o, s, style = Stroke(width = 2.dp.toPx()))
                            }
                            drag?.takeIf { it.first.page == page }?.let { (g, steps) ->
                                // A ghost note head where the note would go.
                                val (o, sz) = box(g, 0.1f)
                                val dy = -steps * ((g.staffSpace ?: 1.0) * lookup.pointsPerStaffSpace / 2).toFloat() * px
                                val color = voiceColor(g.voice, voices)
                                drawOval(color.copy(alpha = 0.5f), o.copy(y = o.y + dy), sz)
                                drawOval(color, o.copy(y = o.y + dy), sz, style = Stroke(width = 2.dp.toPx()))
                                // A ring around the grabbed note: the drag has started.
                                drawCircle(
                                    color,
                                    radius = 1.6f * lookup.pointsPerStaffSpace.toFloat() * px,
                                    center = Offset(o.x + sz.width / 2, o.y + sz.height / 2),
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                            ghost?.takeIf { it.first == page }?.let { (_, p) ->
                                // A note head where the note would go, with ledger lines off the staff.
                                val half = p.halfSpacePt.toFloat() * px
                                val cx = p.xPt.toFloat() * px
                                val cy = p.yPt.toFloat() * px
                                val color = Color(0xFF3F51B5)
                                val top = (p.staff.linePositions?.maxOrNull() ?: 4.0).toInt()
                                val bottom = (p.staff.linePositions?.minOrNull() ?: -4.0).toInt()
                                val ledgers = when {
                                    p.position > top + 1 -> (top + 2..p.position step 2)
                                    p.position < bottom - 1 -> (p.position..bottom - 2).filter { (it - bottom) % 2 == 0 }
                                    else -> emptyList()
                                }
                                for (l in ledgers) {
                                    val ly = cy + (p.position - l) * half
                                    drawLine(color, Offset(cx - 2.2f * half, ly), Offset(cx + 2.2f * half, ly), strokeWidth = 1.5.dp.toPx())
                                }
                                val sz = Size(2.6f * half, 2f * half)
                                drawOval(color.copy(alpha = 0.45f), Offset(cx - sz.width / 2, cy - sz.height / 2), sz)
                                drawOval(color, Offset(cx - sz.width / 2, cy - sz.height / 2), sz, style = Stroke(width = 1.5.dp.toPx()))
                            }
                            lasso?.takeIf { it.first == page }?.let { (_, rect) ->
                                val o = Offset(rect.left * px, rect.top * px)
                                val sz = Size(rect.width * px, rect.height * px)
                                drawRect(Color(0x223F51B5), o, sz)
                                drawRect(Color(0xFF3F51B5), o, sz, style = Stroke(width = 1.5.dp.toPx()))
                            }
                            hover?.takeIf { it.page == page }?.let { g ->
                                val (o, s) = box(g, 0.3f)
                                drawRect(voiceColor(g.voice, voices), o, s, style = Stroke(width = 1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pen draws a box from [down]: once it has moved past the touch slop
 * (before that it may still be a tap), the box follows it, shown through
 * [show] in page points (k points per pixel), and on lifting the note heads
 * and rests it touches are passed to the range callback.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.drawBox(
    down: PointerInputChange,
    k: Float,
    page: Int,
    lookup: ScoreLookup,
    state: ScoreViewState,
    show: (Pair<Int, Rect>?) -> Unit,
    onDone: (List<Grob>) -> Unit,
) {
    while (true) {
        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return
        if (!c.pressed) return // a tap
        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
    }
    state.noteDrag = true // no panning meanwhile
    fun rect(p: Offset) = Rect(
        minOf(down.position.x, p.x) * k, minOf(down.position.y, p.y) * k,
        maxOf(down.position.x, p.x) * k, maxOf(down.position.y, p.y) * k,
    )
    var box = rect(down.position)
    try {
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            c.consume() // not a scroll or tap
            box = rect(c.position)
            show(page to box)
            if (!c.pressed) break
        }
        onDone(lookup.grobsOnPage(page).filter { g ->
            (g.type == "NoteHead" || g.type == "Rest") &&
                lookup.pageRect(g).let { r -> r[2] >= box.left && r[0] <= box.right && r[3] >= box.top && r[1] <= box.bottom }
        })
    } finally {
        show(null)
        state.noteDrag = false
    }
}

/**
 * Two fingers zoom; when zoomed, one finger also pans sideways (the list
 * scrolls vertically by itself). Runs before the list sees the events, and
 * consumes only pinches.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchAndPan(state: ScoreViewState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.size >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) state.zoomBy(zoom, event.calculateCentroid())
                event.changes.forEach { it.consume() }
            } else if (down.size == 1 && state.scale > 1.01f && !state.noteDrag) {
                val c = down[0]
                state.panX(c.position.x - c.previousPosition.x)
            }
        } while (event.changes.any { it.pressed })
    }
}

/** How far from a selected note head a finger can be and still grab it. */
private val FINGER_REACH = 24.dp
