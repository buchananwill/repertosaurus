package dev.repertosaurus.android.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * visual-identity VI11: the stencilled hand. By default it is the session header's mark: `Madder`, about
 * 30 × 38 dp, upright. Decorative, so it has no semantics.
 *
 * - [colour] is `Madder`, `Indigo` or `Ochre` (VI11).
 * - [size] is the hand's box; the stencil keeps its proportions inside it.
 * - [rotation], in degrees, is **the onboarding cluster's alone** (VI11's carve-out from VI3). It turns
 *   the cached bitmap as it is drawn, so the cache stays keyed on size and colour only.
 */
@Composable
internal fun HandMark(
    modifier: Modifier = Modifier,
    colour: Color = Tokens.Madder,
    size: DpSize = HeaderHandSize,
    rotation: Float = 0f,
) {
    Spacer(
        modifier = modifier
            .size(size)
            .then(if (rotation != 0f) Modifier.rotate(rotation) else Modifier)
            .drawWithCache {
                val bitmap = HandStencil.render(this.size.width.toInt(), this.size.height.toInt(), colour)
                onDrawBehind { drawImage(bitmap) }
            },
    )
}

/** VI11: the session header's mark. */
private val HeaderHandSize = DpSize(30.dp, 38.dp)

/**
 * VI11's stencil: seeded-random dots sprayed around a masked hand, dense at its slightly wobbling edge
 * and thinning outward. Rendered once per size and colour and cached for the process.
 *
 * The silhouette is a union of capsules and a rounded palm in a 100-wide design space, so the distance
 * from any point to its edge is exact and cheap.
 */
internal object HandStencil {
    private const val DESIGN_WIDTH = 100f
    private const val DESIGN_HEIGHT = 126.7f
    private const val SEED = 0x4A4E44L

    /** How far outside the edge the spray has thinned to 1/e, in design units. */
    private const val FALLOFF = 7f

    /** Dots per square pixel at the edge itself, where every sample lands. */
    private const val EDGE_DENSITY = 2.2f

    private data class Key(val width: Int, val height: Int, val argb: Int)

    private val cache = HashMap<Key, ImageBitmap>()

    fun render(width: Int, height: Int, colour: Color): ImageBitmap =
        cache.getOrPut(Key(width, height, colour.toArgb())) { spray(max(width, 1), max(height, 1), colour.toArgb()) }

    private fun spray(width: Int, height: Int, argb: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
        val scale = min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        val originX = (width - DESIGN_WIDTH * scale) / 2f
        val originY = (height - DESIGN_HEIGHT * scale) / 2f
        val random = Random(SEED)

        // Uniform samples, each kept with a probability that falls off with the distance outside
        // the edge: a fixed count, so the spray is identical at every launch.
        repeat((width * height * EDGE_DENSITY).toInt()) {
            val x = random.nextFloat() * DESIGN_WIDTH
            val y = random.nextFloat() * DESIGN_HEIGHT
            val chance = random.nextFloat()
            val alpha = 140 + random.nextInt(116)
            val radius = (1.0f + random.nextFloat() * 1.4f) * scale
            val outside = distance(x, y) + wobble(x, y)
            if (outside >= 0f && chance <= exp(-outside / FALLOFF)) {
                paint.alpha = alpha
                canvas.drawCircle(originX + x * scale, originY + y * scale, radius, paint)
            }
        }
        return bitmap.asImageBitmap()
    }

    /** A slow, seeded wobble of the edge, so the silhouette is not a clean vector outline. */
    private fun wobble(x: Float, y: Float): Float =
        1.1f * sin(x * 0.37f + 1.3f) * sin(y * 0.29f + 0.4f) + 0.6f * sin(x * 0.91f + y * 0.73f + 2.1f)

    /** Signed distance to the hand's edge: negative inside the silhouette. */
    private fun distance(x: Float, y: Float): Float {
        var d = roundedBox(x, y, cx = 50f, cy = 84f, halfW = 17f, halfH = 16f, radius = 8f)
        for (c in CAPSULES) d = min(d, capsule(x, y, c))
        return d
    }

    private class Capsule(val ax: Float, val ay: Float, val bx: Float, val by: Float, val r: Float)

    private val CAPSULES = listOf(
        Capsule(38f, 72f, 36f, 34f, 5.4f), // index
        Capsule(48f, 70f, 48f, 27f, 5.7f), // middle
        Capsule(58f, 72f, 60f, 34f, 5.4f), // ring
        Capsule(65f, 78f, 71f, 47f, 4.7f), // little
        Capsule(35f, 90f, 21f, 65f, 6.0f), // thumb
        Capsule(50f, 96f, 50f, 126.7f, 12.5f), // wrist, running off the bottom edge
    )

    private fun capsule(x: Float, y: Float, c: Capsule): Float {
        val px = x - c.ax
        val py = y - c.ay
        val bx = c.bx - c.ax
        val by = c.by - c.ay
        val t = ((px * bx + py * by) / (bx * bx + by * by)).coerceIn(0f, 1f)
        return hypot(px - bx * t, py - by * t) - c.r
    }

    private fun roundedBox(x: Float, y: Float, cx: Float, cy: Float, halfW: Float, halfH: Float, radius: Float): Float {
        val qx = abs(x - cx) - (halfW - radius)
        val qy = abs(y - cy) - (halfH - radius)
        val outside = hypot(max(qx, 0f), max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        return outside + inside - radius
    }
}
