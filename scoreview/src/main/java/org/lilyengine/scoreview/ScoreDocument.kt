package org.lilyengine.scoreview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * A rendered score (one PDF from LilyPond), drawn with Android's own PDF
 * renderer. Nothing else is needed: hit-testing comes from the score map,
 * not from the PDF, so a full PDF library (MuPDF adds about 7 MB per ABI)
 * isn't.
 *
 * Page sizes are in PDF points. All rendering runs on one thread, since
 * PdfRenderer allows only one open page at a time.
 */
class ScoreDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Width and height of each page, in points. */
    val pageSizes: List<Pair<Float, Float>>,
    /** The PDF this was opened from; the caller keeps it until [close]. */
    val file: File,
) : Closeable {
    @Volatile
    private var closed = false

    val pageCount: Int get() = pageSizes.size

    /**
     * Renders page [index] (0-based) [widthPx] pixels wide. Returns null if
     * the document was closed meanwhile (a newer render replaced it).
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        if (closed) return@withContext null
        val (w, h) = pageSizes[index]
        val width = widthPx.coerceIn(1, MAX_BITMAP_SIDE)
        val height = (width * h / w).roundToInt().coerceIn(1, MAX_BITMAP_SIDE)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            val m = Matrix().apply { setScale(width / page.width.toFloat(), height / page.height.toFloat()) }
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        bitmap
    }

    override fun close() {
        if (closed) return
        closed = true
        // Queued behind any render in progress.
        executor.execute {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        private const val MAX_BITMAP_SIDE = 6000

        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "score-pdf").apply { isDaemon = true } }
        private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

        suspend fun open(pdf: File): ScoreDocument = withContext(dispatcher) {
            val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                val sizes = List(renderer.pageCount) { i ->
                    renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() }
                }
                ScoreDocument(fd, renderer, sizes, pdf)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
