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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
     * Called when a selected note head is dragged up or down with a pen and
     * released: the number of staff positions (lines and spaces) it moved,
     * up being positive.
     */
    onDragSteps: ((Grob, Int) -> Unit)? = null,
) {
    var hover by remember { mutableStateOf<Grob?>(null) }
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
                                onTap(lookup?.objectAt(page, (off.x * k).toDouble(), (off.y * k).toDouble()))
                            }
                        }
                        .pointerInput(lookup, page, wPt, selection, onDragSteps) {
                            if (lookup == null || onDragSteps == null) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.type != PointerType.Stylus) return@awaitEachGesture
                                val k = wPt / size.width
                                val g = lookup.objectAt(page, (down.position.x * k).toDouble(), (down.position.y * k).toDouble())
                                    ?.takeIf { it.type == "NoteHead" && it in selection } ?: return@awaitEachGesture
                                // Half a staff space (one line or space) in pixels.
                                val stepPx = ((g.staffSpace ?: 1.0) * lookup.pointsPerStaffSpace / 2 / k).toFloat()
                                var dragging = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    val dy = c.position.y - down.position.y
                                    if (!dragging && kotlin.math.abs(dy) > viewConfiguration.touchSlop) dragging = true
                                    if (dragging) {
                                        c.consume() // no scrolling, and not a tap
                                        drag = g to -(dy / stepPx).roundToInt()
                                    }
                                    if (!c.pressed) break
                                }
                                val result = drag
                                drag = null
                                if (dragging && result != null && result.second != 0) onDragSteps(result.first, result.second)
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
            } else if (down.size == 1 && state.scale > 1.01f) {
                val c = down[0]
                state.panX(c.position.x - c.previousPosition.x)
            }
        } while (event.changes.any { it.pressed })
    }
}
