package dev.repertosaurus.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.HandCluster
import dev.repertosaurus.android.theme.InkFooter
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.misregistered
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.OnboardingStep

/** Test tags for onboarding. */
internal object OnboardingTags {
    const val SCREEN: String = "onboarding"
    const val SKIP: String = "onboarding-skip"
    const val PRIMARY: String = "onboarding-primary"
    fun step(step: OnboardingStep): String = "onboarding-step-${step.name}"
}

/**
 * onboarding OB1-OB4, OB7. Each answer goes out as it is chosen (OB4); [onFinish] ends it (OB3). Composed
 * inside `LocalColourRamp` (OB5).
 *
 * Journal F30: until [performersLoaded], an empty [performers] is not "no performers", so the performer
 * step waits for the read before it shows its list or skips itself.
 */
@Composable
internal fun OnboardingScreen(
    performers: List<RepertosaurusRepository.Performer>,
    performersLoaded: Boolean,
    ownerPerformerId: String?,
    onColourRamp: (ColourRamp) -> Unit,
    onOwnerPerformer: (String?) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val hasPerformers = if (performersLoaded) performers.isNotEmpty() else null
    val next = step.next(hasPerformers)
    val previous = step.previous()
    BackHandler(enabled = previous != null) { if (previous != null) step = previous }
    LaunchedEffect(step, hasPerformers) { if (step.skipsItself(hasPerformers)) onFinish() }

    val content: @Composable (OnboardingStep) -> Unit = { shown ->
        val stepModifier = Modifier.fillMaxSize().testTag(OnboardingTags.step(shown))
        when (shown) {
            OnboardingStep.WELCOME -> WelcomeStep(stepModifier)
            OnboardingStep.RAMP -> QuestionStep(Messages.ONBOARDING_RAMP_QUESTION, stepModifier) {
                ColourRampChoices(onSelect = onColourRamp)
            }
            OnboardingStep.PERFORMER -> QuestionStep(Messages.ONBOARDING_WHO_QUESTION, stepModifier) {
                if (hasPerformers == true) {
                    PerformerChoices(
                        performers = performers,
                        ownerPerformerId = ownerPerformerId,
                        noneLabel = Messages.ONBOARDING_NONE_OF_THESE,
                        onChoose = onOwnerPerformer,
                    )
                }
            }
        }
    }
    // VI22: with animations off, AnimatedContent would still hold both steps in place for a frame.
    val motionOff = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f

    Column(modifier = modifier.fillMaxSize().background(Tokens.Ground).testTag(OnboardingTags.SCREEN)) {
        if (motionOff) {
            Box(modifier = Modifier.weight(1f)) { content(step) }
        } else {
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                // VI18.
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    slideInHorizontally(Motion.spring()) { it * direction } togetherWith
                        slideOutHorizontally(Motion.leaving()) { -it * direction }
                },
                label = "onboarding step",
            ) { shown -> content(shown) }
        }
        InkFooter {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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

private fun primaryLabel(step: OnboardingStep, next: OnboardingStep?): String = when {
    step == OnboardingStep.WELCOME -> Messages.ONBOARDING_PICK_COLOURS
    next == null -> Messages.DONE
    else -> Messages.ONBOARDING_NEXT
}

/** OB1 as amended by D66: the hand cluster, the misregistered title, and the *tradere* copy. */
@Composable
private fun WelcomeStep(modifier: Modifier = Modifier) {
    val body = remember { italicised(Messages.ONBOARDING_WELCOME_BODY, Messages.ONBOARDING_WELCOME_LATIN) }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        HandCluster(modifier = Modifier.fillMaxWidth().height(240.dp))
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DisplayText(
                Messages.ONBOARDING_WELCOME_TITLE,
                style = misregistered(DisplayType.ScreenTitle),
                color = Tokens.Indigo,
            )
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink)
        }
    }
}

/** [text] with every occurrence of [word] in italic. */
private fun italicised(text: String, word: String): AnnotatedString = buildAnnotatedString {
    text.split(word).forEachIndexed { i, part ->
        if (i > 0) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(word) }
        append(part)
    }
}

/** One question (OB2) and OB7's line under it. */
@Composable
private fun QuestionStep(question: String, modifier: Modifier = Modifier, choices: @Composable () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DisplayText(question, style = DisplayType.Heading, color = Tokens.Ink)
        choices()
        Text(Messages.ONBOARDING_CHANGE_LATER, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkMuted)
    }
}
