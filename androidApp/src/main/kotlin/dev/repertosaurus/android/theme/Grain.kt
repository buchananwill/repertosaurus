package dev.repertosaurus.android.theme

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.random.Random

/**
 * visual-identity VI10: a fine paper grain over everything the modifier's node draws, **multiplied**.
 *
 * **It cannot intercept a touch:** it is a draw modifier and nothing else, with no pointer input and
 * no node of its own in the layout. **It costs no frame:** the tile is built once per process and the
 * brush once per size (in `drawWithCache`). The content below it is drawn in a layer of its own, so a
 * scroll or a recomposition re-records that layer and never this one; the grain's single `drawRect`
 * is replayed by the render thread. It sits above content and below dialogs, because a dialog or a
 * modal sheet is a window of its own.
 */
public fun Modifier.paperGrain(): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(GrainTile.bitmap, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.Multiply)
    }
}.graphicsLayer()

/**
 * The one tile, generated rather than bundled: 256 px of seeded noise costs well under a millisecond
 * to build and nothing in the APK, and a fixed seed makes it the same tile on every launch.
 *
 * **Opaque on purpose.** Multiplying by an opaque pixel is `destination × source` whether the
 * platform blends with `BlendMode` (API 29+) or `PorterDuff.MULTIPLY` (26-28), so the grain looks
 * the same on every supported release. White multiplies to no change; the darkest grain pixel is
 * [STRENGTH] below white. A uniform draw puts the mean darkening at half of that, which is what a
 * noise layer at 20% opacity, multiplied, gives on the design canvas.
 */
internal object GrainTile {
    private const val SIZE = 256
    private const val SEED = 0x6E0A1L
    private const val STRENGTH = 0.20f

    val bitmap: ImageBitmap by lazy {
        val random = Random(SEED)
        val pixels = IntArray(SIZE * SIZE) {
            val level = (255 * (1f - STRENGTH * random.nextFloat())).toInt()
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
