package dev.repertosaurus.android

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.lockOf
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a zero radius sits, so five handles at zero do not stack on one point. */
private val RadarHub = 24.dp

/** The outer ring's largest radius: a small instrument panel (vision), not a full-bleed chart. */
private val RadarMaxOuter = 130.dp

/** The least the hub-to-rim span shrinks to however large the labels are (F26 N4: always positive). */
private val RadarMinSpan = 40.dp

/** From a spoke's tip to its label: clear of a handle at full radius and its shadow. */
private val LabelGap = 14.dp

/** A label's widest, as a share of the radar's width. */
private const val LABEL_WIDTH_SHARE = 0.4f

/** How far from a handle's centre a press still takes it: half the touch target. */
private val HandleReach = Tokens.TouchMin / 2

private val HandleSize = 18.dp

/** The inner rings and the tuned polygon's fill. */
private const val RING_ALPHA = 0.25f
private const val FILL_ALPHA = 0.22f

/** A handle in the finger: its spoke and its unsnapped radius. */
private class Held(val spoke: SuggestSpoke, val radius: Float)

/**
 * suggest SG11: the radar. Five spokes, coldness opposite hotness (SG10).
 *
 * - **During a drag the handle and polygon follow the finger 1:1** (F31 N1); on release the radius
 *   snaps to [SuggestTuning.STEP], springs there and is persisted. An external change (Shuffle)
 *   springs too.
 * - A press takes the nearest handle **as drawn** (F26 N5), locked ones included, and a locked one
 *   takes nothing. A press away from every handle is left to the sheet.
 * - The labels are measured first and the rings sized to leave them room (F31 B1), so a large font
 *   shrinks the radar rather than pushing a label off it.
 * - [tuning] is the stored one, which a drag writes to; what is drawn is its [SuggestTuning.effective]
 *   for [part], each locked spoke at zero with [lockOf]'s reason (SG11, SG12, SG14).
 */
@Composable
internal fun SuggestRadar(tuning: SuggestTuning, part: PartResolution, onTune: (SuggestTuning) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val shown = tuning.effective(part)
    val reasons = SuggestSpoke.entries.associateWith { lockOf(it, tuning.countSkips, part) }
    val settled = remember { SuggestSpoke.entries.associateWith { Animatable(shown.radius(it).toFloat()) } }
    var held by remember { mutableStateOf<Held?>(null) }
    var geometry by remember { mutableStateOf<RadarGeometry?>(null) }
    val tune by rememberUpdatedState(onTune)
    val persisted by rememberUpdatedState(tuning)
    val locks by rememberUpdatedState(reasons)
    val locked = { spoke: SuggestSpoke -> locks[spoke] != null }
    val drawn = { spoke: SuggestSpoke -> held?.takeIf { it.spoke == spoke }?.radius ?: settled.getValue(spoke).value }

    LaunchedEffect(shown) {
        for ((spoke, radius) in settled) {
            if (held?.spoke != spoke) launch { radius.animateTo(shown.radius(spoke).toFloat(), Motion.spring()) }
        }
    }

    Layout(
        contents = listOf(
            { for (spoke in SuggestSpoke.entries) SpokeLabel(spoke, reasons[spoke]) },
            {
                for (spoke in SuggestSpoke.entries) {
                    SpokeHandle(spoke, shown.radius(spoke), reasons[spoke], onSet = { tune(persisted.withRadius(spoke, it)) })
                }
            },
        ),
        modifier = modifier
            .testTag(SuggestTags.RADAR)
            .drawBehind { geometry?.let { drawRadar(it, drawn, locked) } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val at = geometry ?: return@awaitEachGesture
                    val spoke = at.nearestHandle(down.position, drawn, HandleReach.toPx(), locked) ?: return@awaitEachGesture
                    val start = drawn(spoke)
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    val follow = { position: Offset -> Held(spoke, (start + at.along(spoke, position - down.position)).coerceIn(0f, 1f)) }
                    held = follow(slop.position)
                    val completed = drag(slop.id) { change ->
                        change.consume()
                        held = follow(change.position)
                    }
                    val released = held?.radius ?: start
                    val target = if (completed) SuggestTuning.snap(released.toDouble()) else persisted.radius(spoke)
                    scope.launch {
                        settled.getValue(spoke).snapTo(released)
                        held = null
                        settled.getValue(spoke).animateTo(target.toFloat(), Motion.spring())
                    }
                    if (completed) tune(persisted.withRadius(spoke, target))
                }
            },
    ) { (labels, handles), constraints ->
        val width = constraints.maxWidth
        val labelWidth = Constraints(maxWidth = (width * LABEL_WIDTH_SHARE).roundToInt())
        val labelPlaceables = labels.map { it.measure(labelWidth) }
        val handlePlaceables = handles.map { it.measure(Constraints()) }
        val fitted = RadarGeometry.fit(this, width, SuggestSpoke.entries.zip(labelPlaceables) { s, p -> s to IntSize(p.width, p.height) })
        geometry = fitted
        layout(width, fitted.height) {
            for ((i, spoke) in SuggestSpoke.entries.withIndex()) {
                val label = labelPlaceables[i]
                val (ax, ay) = spoke.anchor()
                val tip = fitted.point(spoke, 1f, beyond = LabelGap.toPx())
                label.place((tip.x - ax * label.width).roundToInt(), (tip.y - ay * label.height).roundToInt())
                val handle = handlePlaceables[i]
                val centre = fitted.point(spoke, drawn(spoke))
                handle.place((centre.x - handle.width / 2f).roundToInt(), (centre.y - handle.height / 2f).roundToInt())
            }
        }
    }
}

/** A spoke's name and, locked, its [reason] (SG11), greyed. */
@Composable
private fun SpokeLabel(spoke: SuggestSpoke, reason: String?) {
    Column(modifier = Modifier.testTag(SuggestTags.label(spoke)), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            spoke.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (reason != null) Tokens.InkMuted else Tokens.Ink,
            textAlign = TextAlign.Center,
        )
        reason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.InkMuted, textAlign = TextAlign.Center) }
    }
}

/**
 * One handle: a square with a hard shadow, and the spoke's accessible slider, since the drag is the
 * radar's. A locked handle is disabled and has no action.
 */
@Composable
private fun SpokeHandle(spoke: SuggestSpoke, radius: Double, reason: String?, onSet: (Double) -> Unit) {
    Box(
        modifier = Modifier
            .size(Tokens.TouchMin)
            .testTag(SuggestTags.spoke(spoke))
            .semantics {
                contentDescription = spoke.label
                stateDescription = reason ?: "${(radius * 100).roundToInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(radius.toFloat(), 0f..1f, steps = SuggestTuning.STEPS - 1)
                if (reason != null) {
                    disabled()
                } else {
                    setProgress { onSet(it.toDouble()); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // The shadow pads the end and bottom; half of it back keeps the square on the vertex.
                .offset(x = Tokens.ShadowSmall / 2, y = Tokens.ShadowSmall / 2)
                .then(if (reason != null) Modifier.alpha(Tokens.DisabledAlpha) else Modifier.hardShadow(Tokens.ShadowSmall))
                .background(if (reason != null) Tokens.Ground else Tokens.Paper)
                .inkBorder(Tokens.StrokeRule)
                .size(HandleSize),
        )
    }
}

/** [SuggestSpoke.direction] as a screen offset. */
private fun SuggestSpoke.unit(): Offset = direction().let { (x, y) -> Offset(x.toFloat(), y.toFloat()) }

/** Which fraction of a label's width and height sits before its point, so it reads outward. */
private fun SuggestSpoke.anchor(): Pair<Float, Float> = unit().let { (1f - it.x) / 2f to (1f - it.y) / 2f }

/** The radar's measurements in pixels. [height] is the layout's, labels included. */
private data class RadarGeometry(val centre: Offset, val hub: Float, val outer: Float, val height: Int) {

    /** [spoke] at [radius], plus [beyond] further out. */
    fun point(spoke: SuggestSpoke, radius: Float, beyond: Float = 0f): Offset =
        centre + spoke.unit() * (hub + radius * (outer - hub) + beyond)

    /** A drag of [delta] as a change of radius along [spoke]. The span is never zero ([fit]). */
    fun along(spoke: SuggestSpoke, delta: Offset): Float {
        val d = spoke.unit()
        return (delta.x * d.x + delta.y * d.y) / (outer - hub)
    }

    /** The nearest handle as drawn within [reach] of [at], or null; a [locked] one takes nothing. */
    fun nearestHandle(at: Offset, drawn: (SuggestSpoke) -> Float, reach: Float, locked: (SuggestSpoke) -> Boolean): SuggestSpoke? =
        SuggestSpoke.entries
            .map { it to (point(it, drawn(it)) - at).getDistance() }
            .filter { (_, distance) -> distance <= reach }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.takeIf { !locked(it) }

    companion object {
        /**
         * The largest rim that keeps every label inside [width], capped at [RadarMaxOuter] and never
         * closer to the hub than [RadarMinSpan]; the height is whatever the rings and labels then need.
         */
        fun fit(density: Density, width: Int, labels: List<Pair<SuggestSpoke, IntSize>>): RadarGeometry = with(density) {
            val half = width / 2f
            val hub = RadarHub.toPx()
            val gap = LabelGap.toPx()
            val handleHalf = Tokens.TouchMin.toPx() / 2f
            var outer = min(RadarMaxOuter.toPx(), half - handleHalf)
            for ((spoke, size) in labels) {
                val dx = abs(spoke.unit().x)
                if (dx > 1e-3f) outer = min(outer, (half - (1f + dx) / 2f * size.width) / dx - gap)
            }
            outer = max(outer, hub + RadarMinSpan.toPx())
            var above = outer + handleHalf
            var below = outer + handleHalf
            for ((spoke, size) in labels) {
                val top = spoke.unit().y * (outer + gap) - spoke.anchor().second * size.height
                above = max(above, -top)
                below = max(below, top + size.height)
            }
            RadarGeometry(Offset(half, above), hub, outer, ceil(above + below).toInt())
        }
    }
}

/** The rings, the spokes and the tuned polygon: square joins, `Ink` lines, a translucent `Indigo` fill. */
private fun DrawScope.drawRadar(geometry: RadarGeometry, drawn: (SuggestSpoke) -> Float, locked: (SuggestSpoke) -> Boolean) {
    val rule = Tokens.StrokeRule.toPx()
    for (level in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
        val rim = level == 1f
        drawPath(
            polygon(SuggestSpoke.entries.map { geometry.point(it, level) }),
            Tokens.Ink.copy(alpha = if (rim) 1f else RING_ALPHA),
            style = Stroke(width = if (rim) rule else Tokens.StrokeHairline.toPx(), join = StrokeJoin.Miter),
        )
    }
    for (spoke in SuggestSpoke.entries) {
        drawLine(
            color = if (locked(spoke)) Tokens.Ink.copy(alpha = RING_ALPHA) else Tokens.Ink,
            start = geometry.point(spoke, 0f),
            end = geometry.point(spoke, 1f),
            strokeWidth = rule,
            cap = StrokeCap.Butt,
        )
    }
    val tuned = polygon(SuggestSpoke.entries.map { geometry.point(it, drawn(it)) })
    drawPath(tuned, Tokens.Indigo.copy(alpha = FILL_ALPHA))
    drawPath(tuned, Tokens.Indigo, style = Stroke(width = Tokens.StrokeHeavy.toPx(), join = StrokeJoin.Miter))
}

private fun polygon(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}
