package dev.repertosaurus.android.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * visual-identity VI11: the stencilled hand, upright, [width] wide at the stencil's own proportions. The
 * session header's mark by default. Decorative, so it has no semantics.
 *
 * **[width] is a fixed size, never animated or measured:** every distinct size is rendered and cached,
 * and the cache has no eviction.
 */
@Composable
internal fun HandMark(modifier: Modifier = Modifier, colour: Color = Tokens.Madder, width: Dp = HeaderHandWidth) {
    StencilledHand(modifier = modifier, colour = colour, width = width, rotation = 0f)
}

/** VI11: the session header's mark, about 30 × 38 dp. */
private val HeaderHandWidth = 30.dp

/**
 * VI11: the onboarding welcome's cluster of hands, as on a cave wall, clipped to its box so the lower
 * wrists run off its foot. **Rotation is private to this file**: these are the only turned elements in
 * the app (VI11's carve-out from VI3). Its cache entries are released when it leaves composition.
 */
@Composable
internal fun HandCluster(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    DisposableEffect(density) {
        onDispose { for (hand in ClusterHands) HandStencil.release(pixelSize(hand.width, density), hand.colour) }
    }
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        for (hand in ClusterHands) {
            StencilledHand(
                modifier = Modifier.offset(x = maxWidth * hand.x, y = maxHeight * hand.y),
                colour = hand.colour,
                width = hand.width,
                rotation = hand.rotation,
            )
        }
    }
}

/** One hand of the cluster: its top-left as fractions of the box, its width, colour and angle. */
private class ClusterHand(val x: Float, val y: Float, val width: Dp, val colour: Color, val rotation: Float)

private val ClusterHands = listOf(
    ClusterHand(x = 0.02f, y = 0.16f, width = 86.dp, colour = Tokens.Indigo, rotation = -22f),
    ClusterHand(x = 0.28f, y = 0.02f, width = 104.dp, colour = Tokens.Madder, rotation = 4f),
    ClusterHand(x = 0.60f, y = 0.12f, width = 90.dp, colour = Tokens.Ochre, rotation = 24f),
    ClusterHand(x = 0.14f, y = 0.52f, width = 76.dp, colour = Tokens.Ochre, rotation = -8f),
    ClusterHand(x = 0.52f, y = 0.50f, width = 82.dp, colour = Tokens.Madder, rotation = 14f),
)

/**
 * One hand. The bitmap is sprayed on `Dispatchers.Default` and nothing is drawn until it is ready; it is
 * cached only once the render returns, so a hand disposed mid-render leaves no entry behind. [rotation]
 * turns the cached bitmap as it is drawn, so the cache stays keyed on size and colour.
 */
@Composable
private fun StencilledHand(modifier: Modifier, colour: Color, width: Dp, rotation: Float) {
    val density = LocalDensity.current
    val pixels = pixelSize(width, density)
    val bitmap by produceState(HandStencil.cached(pixels, colour), pixels, colour) {
        if (value == null) {
            val sprayed = withContext(Dispatchers.Default) { HandStencil.spray(pixels, colour) }
            value = HandStencil.store(pixels, colour, sprayed)
        }
    }
    Spacer(
        modifier = modifier
            .size(width, width * HandStencil.ASPECT)
            .then(if (rotation != 0f) Modifier.rotate(rotation) else Modifier)
            .drawBehind { bitmap?.let { drawImage(it) } },
    )
}

private fun pixelSize(width: Dp, density: Density): IntSize =
    with(density) { IntSize(width.roundToPx(), (width * HandStencil.ASPECT).roundToPx()) }

/**
 * VI11's stencil: seeded-random dots sprayed around a masked hand, dense at its slightly wobbling edge
 * and thinning outward. Cached per pixel size and colour; the cache is touched only on the main thread.
 *
 * The silhouette is a union of capsules and a rounded palm in a 100-wide design space, so the distance
 * from any point to its edge is exact and cheap.
 */
internal object HandStencil {
    private const val DESIGN_WIDTH = 100f
    private const val DESIGN_HEIGHT = 126.7f
    private const val SEED = 0x4A4E44L

    /** The stencil's height over its width. */
    const val ASPECT: Float = DESIGN_HEIGHT / DESIGN_WIDTH

    /** How far outside the edge the spray has thinned to 1/e, in design units. */
    private const val FALLOFF = 7f

    /** Dots per square pixel at the edge itself, where every sample lands. */
    private const val EDGE_DENSITY = 2.2f

    private data class Key(val width: Int, val height: Int, val argb: Int)

    private val cache = HashMap<Key, ImageBitmap>()

    private fun key(size: IntSize, colour: Color) = Key(size.width, size.height, colour.toArgb())

    fun cached(size: IntSize, colour: Color): ImageBitmap? = cache[key(size, colour)]

    /** Keeps [bitmap] unless another render got there first, and returns whichever is kept. */
    fun store(size: IntSize, colour: Color, bitmap: ImageBitmap): ImageBitmap =
        cache.getOrPut(key(size, colour)) { bitmap }

    fun release(size: IntSize, colour: Color) {
        cache.remove(key(size, colour))
    }

    /** Pure, and safe off the main thread. */
    fun spray(size: IntSize, colour: Color): ImageBitmap =
        spray(max(size.width, 1), max(size.height, 1), colour.toArgb())

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
