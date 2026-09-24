package dev.repertosaurus.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.HandMark
import dev.repertosaurus.android.theme.InkFooter
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.Messages

/**
 * onboarding OB1-OB2, as amended by D66: the welcome, then the colour ramp, then who you are. The order
 * is the declaration order, and the pure rules for moving between steps live here so the JVM tests
 * them.
 */
internal enum class OnboardingStep {
    WELCOME,
    RAMP,
    PERFORMER,
    ;

    /**
     * The step after this one, or null when onboarding is finished. OB2: with no live performers the
     * performer step is skipped, so the ramp step finishes it.
     */
    fun next(hasPerformers: Boolean): OnboardingStep? = when (this) {
        WELCOME -> RAMP
        RAMP -> if (hasPerformers) PERFORMER else null
        PERFORMER -> null
    }

    /** Back walks one step back; from the welcome it leaves the app, as back does from the logger. */
    fun previous(): OnboardingStep? = when (this) {
        WELCOME -> null
        RAMP -> WELCOME
        PERFORMER -> RAMP
    }
}

/** Test tags for onboarding and the owner picker. */
internal object OnboardingTags {
    const val SCREEN: String = "onboarding"
    const val SKIP: String = "onboarding-skip"
    const val PRIMARY: String = "onboarding-primary"
    fun step(step: OnboardingStep): String = "onboarding-step-${step.name}"

    /** onboarding OB6. */
    const val OWNER_PICKER: String = "owner-performer-picker"
    const val OWNER_NONE: String = "owner-performer-none"
    fun owner(performerId: String): String = "owner-performer-$performerId"
}

/**
 * onboarding OB1-OB4, OB7. Every answer goes out through its callback **as it is chosen** (OB4), and
 * [onFinish] is called last, by "Done" or by "Skip setup" on any step (OB3). This screen writes nothing
 * itself: the caller's callbacks are `DeviceSettings`'.
 *
 * [performers] are the live ones. The ramp step reads the live ramp from `LocalColourRamp` (OB5), so the
 * caller composes this inside that provider.
 */
@Composable
internal fun OnboardingScreen(
    performers: List<RepertosaurusRepository.Performer>,
    ownerPerformerId: String?,
    onColourRamp: (ColourRamp) -> Unit,
    onOwnerPerformer: (String?) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val next = step.next(hasPerformers = performers.isNotEmpty())
    val previous = step.previous()
    BackHandler(enabled = previous != null) { previous?.let { step = it } }

    Column(modifier = modifier.fillMaxSize().background(Tokens.Ground).testTag(OnboardingTags.SCREEN)) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            // VI18: a spring in, and the leaving ease out, the way the step is going.
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                slideInHorizontally(Motion.spring()) { it * direction } togetherWith
                    slideOutHorizontally(Motion.leaving()) { -it * direction }
            },
            label = "onboarding step",
        ) { shown ->
            val stepModifier = Modifier.fillMaxSize().testTag(OnboardingTags.step(shown))
            when (shown) {
                OnboardingStep.WELCOME -> WelcomeStep(stepModifier)
                OnboardingStep.RAMP -> QuestionStep(Messages.ONBOARDING_RAMP_QUESTION, stepModifier) {
                    ColourRampChoices(onSelect = onColourRamp)
                }
                OnboardingStep.PERFORMER -> QuestionStep(Messages.ONBOARDING_WHO_QUESTION, stepModifier) {
                    PerformerChoices(
                        performers = performers,
                        ownerPerformerId = ownerPerformerId,
                        noneLabel = Messages.ONBOARDING_NONE_OF_THESE,
                        onChoose = onOwnerPerformer,
                    )
                }
            }
        }
        InkFooter {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // OB3: one tap ends it, keeping whatever was chosen so far.
                SecondaryButton(Messages.ONBOARDING_SKIP, onClick = onFinish, modifier = Modifier.testTag(OnboardingTags.SKIP))
                PrimaryButton(
                    text = primaryLabel(step, next),
                    onClick = { if (next == null) onFinish() else step = next },
                    modifier = Modifier.weight(1f).testTag(OnboardingTags.PRIMARY),
                )
            }
        }
    }
}

/** The one primary action (VI12): on to the colours, on to the next question, or done. */
private fun primaryLabel(step: OnboardingStep, next: OnboardingStep?): String = when {
    step == OnboardingStep.WELCOME -> Messages.ONBOARDING_PICK_COLOURS
    next == null -> Messages.ONBOARDING_DONE
    else -> Messages.ONBOARDING_NEXT
}

/**
 * The welcome (OB1 as amended, journal D66): the stencilled hands on the stone ground, the title in
 * `Indigo` over a `Madder` offset, and the *tradere* copy.
 */
@Composable
private fun WelcomeStep(modifier: Modifier) {
    val shift = with(LocalDensity.current) { Tokens.Misregistration.toPx() }
    val titleStyle = remember(shift) {
        DisplayType.ScreenTitle.copy(shadow = Shadow(Tokens.Madder, Offset(shift, shift), blurRadius = 0f))
    }
    val body = remember { italicised(Messages.ONBOARDING_WELCOME_BODY, Messages.ONBOARDING_WELCOME_LATIN) }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        HandCluster(modifier = Modifier.fillMaxWidth().height(ClusterHeight))
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DisplayText(Messages.ONBOARDING_WELCOME_TITLE, style = titleStyle, color = Tokens.Indigo)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
        }
    }
}

/** [text] with every occurrence of [word] set in italic. */
private fun italicised(text: String, word: String): AnnotatedString = buildAnnotatedString {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from)
        if (at < 0) break
        append(text.substring(from, at))
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(word) }
        from = at + word.length
    }
    append(text.substring(from))
}

/** One question (OB2) and OB7's plain line under it. */
@Composable
private fun QuestionStep(question: String, modifier: Modifier, choices: @Composable () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DisplayText(question, style = DisplayType.Heading, color = Tokens.Ink)
        choices()
        Text(Messages.ONBOARDING_CHANGE_LATER, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkMuted)
    }
}

/**
 * visual-identity VI11: a few stencilled hands, as on a cave wall. **The only rotated elements in the
 * app** (VI11's carve-out from VI3). Placed by fractions of the cluster's box, and clipped to it, so the
 * lower hands' wrists run off its foot.
 */
@Composable
private fun HandCluster(modifier: Modifier) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        for (hand in ClusterHands) {
            HandMark(
                modifier = Modifier.offset(x = maxWidth * hand.x, y = maxHeight * hand.y),
                colour = hand.colour,
                size = DpSize(hand.width, hand.width * HAND_ASPECT),
                rotation = hand.rotation,
            )
        }
    }
}

/** One hand of the cluster: its top-left as fractions of the box, its width, colour and angle. */
private class ClusterHand(val x: Float, val y: Float, val width: Dp, val colour: Color, val rotation: Float)

/** The stencil's height over its width (`HandStencil`'s design space). */
private const val HAND_ASPECT = 1.267f

private val ClusterHeight = 240.dp

private val ClusterHands = listOf(
    ClusterHand(x = 0.02f, y = 0.16f, width = 86.dp, colour = Tokens.Indigo, rotation = -22f),
    ClusterHand(x = 0.28f, y = 0.02f, width = 104.dp, colour = Tokens.Madder, rotation = 4f),
    ClusterHand(x = 0.60f, y = 0.12f, width = 90.dp, colour = Tokens.Ochre, rotation = 24f),
    ClusterHand(x = 0.14f, y = 0.52f, width = 76.dp, colour = Tokens.Ochre, rotation = -8f),
    ClusterHand(x = 0.52f, y = 0.50f, width = 82.dp, colour = Tokens.Madder, rotation = 14f),
)

/**
 * The performer list of OB2 step 2 and the drawer's "Who you are" (OB6): the live performers and one
 * [noneLabel] row, the current choice marked in `Ink` and by a tick, not by colour alone. A tap chooses;
 * [onChoose] takes the performer's id, or null for none.
 *
 * triage T10: an owner who is not among the live performers, removed since, resolves to none.
 */
@Composable
internal fun PerformerChoices(
    performers: List<RepertosaurusRepository.Performer>,
    ownerPerformerId: String?,
    noneLabel: String,
    onChoose: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = ownerPerformerId?.takeIf { id -> performers.any { it.id == id } }
    Column(modifier = modifier.fillMaxWidth()) {
        for (performer in performers) {
            ChoiceRow(
                label = performer.name,
                selected = current == performer.id,
                onClick = { onChoose(performer.id) },
                modifier = Modifier.testTag(OnboardingTags.owner(performer.id)),
            )
        }
        ChoiceRow(
            label = noneLabel,
            selected = current == null,
            onClick = { onChoose(null) },
            modifier = Modifier.testTag(OnboardingTags.OWNER_NONE),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    RuledItem {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(if (selected) Tokens.Ink else Color.Transparent)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .heightIn(min = Tokens.TouchMin)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) Tokens.Paper else Tokens.Ink,
                modifier = Modifier.weight(1f),
            )
            if (selected) Text(TICK, style = MaterialTheme.typography.titleMedium, color = Tokens.Ochre)
        }
    }
}

/** A glyph, not a sentence: the mark beside the current choice. */
private const val TICK = "✓"

/**
 * onboarding OB6: the drawer's "Who you are", the same list as OB2 step 2 in a sheet, as the colour ramp
 * picker is (RS16). One tap chooses and closes; [onChoose] persists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerPerformerPicker(
    performers: List<RepertosaurusRepository.Performer>,
    ownerPerformerId: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(OnboardingTags.OWNER_PICKER),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayText(Messages.WHO_YOU_ARE, style = DisplayType.Heading)
            if (performers.isEmpty()) {
                Text(Messages.WHO_YOU_ARE_NO_PERFORMERS, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkMuted)
            }
            PerformerChoices(
                performers = performers,
                ownerPerformerId = ownerPerformerId,
                noneLabel = Messages.WHO_YOU_ARE_NONE,
                onChoose = onChoose,
            )
        }
    }
}
