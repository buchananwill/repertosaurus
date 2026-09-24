package dev.repertosaurus.android.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * visual-identity VI1 as Material roles, so VI16's inheritance does the right thing on screens P13b
 * does not restyle. **Light only** (VI17): the system dark setting is ignored until P13 designs a
 * night version.
 *
 * - Surfaces: the screen is `Ground`; sheets, dialogs and the drawer are `Paper`.
 * - `primaryContainer` is `Ochre`, because a stock container in that role (a FAB) is the one primary
 *   action on its screen (VI1).
 * - `secondaryContainer` is `Ink` over `Paper`: a selected chip or drawer item looks like a selected
 *   segment (VI13).
 * - `surfaceTint` is transparent, so tonal elevation never tints a surface towards Indigo.
 * - `inversePrimary` is `Ochre`, which the snackbar's action wears on its `Ink` ground.
 */
private val RepertosaurusColours: ColorScheme = lightColorScheme(
    primary = Tokens.Indigo,
    onPrimary = Tokens.Paper,
    primaryContainer = Tokens.Ochre,
    onPrimaryContainer = Tokens.Ink,
    inversePrimary = Tokens.Ochre,
    secondary = Tokens.Ink,
    onSecondary = Tokens.Paper,
    secondaryContainer = Tokens.Ink,
    onSecondaryContainer = Tokens.Paper,
    tertiary = Tokens.Madder,
    onTertiary = Tokens.Ink,
    tertiaryContainer = Tokens.Paper,
    onTertiaryContainer = Tokens.Ink,
    background = Tokens.Ground,
    onBackground = Tokens.Ink,
    surface = Tokens.Ground,
    onSurface = Tokens.Ink,
    surfaceVariant = Tokens.Paper,
    onSurfaceVariant = Tokens.InkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Tokens.Ink,
    inverseOnSurface = Tokens.Paper,
    outline = Tokens.Ink,
    outlineVariant = Tokens.Ink,
    surfaceBright = Tokens.Paper,
    surfaceDim = Tokens.Ground,
    surfaceContainerLowest = Tokens.Field,
    surfaceContainerLow = Tokens.Paper,
    surfaceContainer = Tokens.Paper,
    surfaceContainerHigh = Tokens.Paper,
    surfaceContainerHighest = Tokens.Paper,
    // D97: the error mark, never an error text colour. No stock Material red remains.
    error = Tokens.Madder,
    onError = Tokens.Paper,
)

/** VI3: every theme shape is square. A zero-radius corner shape is `RectangleShape` in every way that draws. */
private val Square: CornerBasedShape = RoundedCornerShape(CornerSize(0))

private val RepertosaurusShapes: Shapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

/**
 * visual-identity §1: the app's `MaterialTheme`. The grain is not part of it, so a test can compose one
 * component in the theme without a full-screen overlay around it; [RepertosaurusWindow] adds it.
 */
@Composable
public fun RepertosaurusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RepertosaurusColours,
        shapes = RepertosaurusShapes,
        typography = RepertosaurusTypography,
        content = content,
    )
}

/** The whole window as the app draws it: the theme, the `Ground` surface and the grain over all of it (VI10). */
@Composable
public fun RepertosaurusWindow(content: @Composable () -> Unit) {
    RepertosaurusTheme {
        Surface(modifier = Modifier.fillMaxSize().paperGrain(), content = content)
    }
}
