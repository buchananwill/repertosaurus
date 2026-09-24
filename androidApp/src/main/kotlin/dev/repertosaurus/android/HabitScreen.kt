package dev.repertosaurus.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkHeader
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentLabel
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.core.DateLabels
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitScope
import dev.repertosaurus.habit.PeriodTally
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.Messages
import kotlinx.datetime.LocalDate

/** Stable handles for the instrumented tests. */
internal object HabitTags {
    const val SCREEN: String = "habit-screen"
    const val DONE: String = "habit-done"
    const val GRID: String = "habit-grid"
    const val LINE: String = "habit-line"
    const val SCOPE_ALL: String = "habit-scope-all"
    const val SCOPE_INSTRUMENT: String = "habit-scope-instrument"
}

/**
 * **The scorecards route** (scorecards SC1-SC15). It reads only. No streaks (SC12), no comparison
 * arrows (SC11), and the weakest weekday is shown but never named (SC9). [practiceInstrument] is the
 * current View's, or null with no View.
 */
@Composable
public fun HabitScreen(
    viewModel: HabitViewModel,
    scope: HabitScope,
    onScope: (HabitScope) -> Unit,
    practiceInstrument: InstrumentChip?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val instrumentId = scope.instrumentId(practiceInstrument?.id)
    val card = state.cardFor(instrumentId)
    var selected by rememberSaveable(instrumentId) { mutableStateOf<String?>(null) }

    // F23 N2: load on entry, on a scope change, and on every resume, so a screen left open over
    // midnight catches up. An observer added to a resumed lifecycle is sent ON_RESUME at once.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, instrumentId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load(instrumentId)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().testTag(HabitTags.SCREEN)) {
        InkHeader(
            navigation = { DisplayText(Messages.HABIT_KICKER, style = DisplayType.Subline) },
            actions = { SecondaryButton(text = "Done", onClick = onBack, modifier = Modifier.testTag(HabitTags.DONE)) },
            title = { DisplayText(Messages.HABIT_TITLE, style = DisplayType.ScreenTitle, maxLines = 2) },
        )
        StatusLines(message = null, error = state.error)
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScopeStrip(instrumentScoped = instrumentId != null, practiceInstrument = practiceInstrument, onScope = onScope)
            if (card != null) {
                HabitGrid(card = card, selected = selected, onSelect = { selected = it })
                DayLine(card = card, selected = selected)
                if (!card.empty) Summaries(card)
            }
        }
    }
}

/** SC4. The second segment is disabled with no View. */
@Composable
private fun ScopeStrip(instrumentScoped: Boolean, practiceInstrument: InstrumentChip?, onScope: (HabitScope) -> Unit) {
    SegmentStrip(modifier = Modifier.fillMaxWidth()) {
        Segment(
            selected = !instrumentScoped,
            onClick = { onScope(HabitScope.ALL_INSTRUMENTS) },
            modifier = Modifier.testTag(HabitTags.SCOPE_ALL),
        ) {
            SegmentLabel(Messages.HABIT_ALL_INSTRUMENTS)
        }
        Segment(
            selected = instrumentScoped,
            onClick = { onScope(HabitScope.PRACTICE_INSTRUMENT) },
            enabled = practiceInstrument != null,
            modifier = Modifier.testTag(HabitTags.SCOPE_INSTRUMENT),
        ) {
            SegmentLabel(practiceInstrument?.label ?: Messages.HABIT_NO_VIEW_INSTRUMENT)
        }
    }
}

/** SC7's line for the tapped day, or SC14's when there is nothing yet. Its height is reserved. */
@Composable
private fun DayLine(card: HabitCard, selected: String?) {
    val day = selected?.let(card::day)
    val text = when {
        day != null -> Messages.habitDay(day)
        card.empty -> Messages.HABIT_EMPTY
        else -> ""
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag(HabitTags.LINE))
}

/** SC9-SC11; not shown in the empty state (SC14). */
@Composable
private fun Summaries(card: HabitCard) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(Messages.habitThisWeek(card.thisWeek), style = MaterialTheme.typography.bodyLarge)
            Text(Messages.habitThisMonth(card.thisMonth), style = MaterialTheme.typography.bodyLarge)
        }
        Section(Messages.habitRecentWeeks) {
            TallyRow(card, months = false)
        }
        Section(Messages.habitRecentMonths) {
            TallyRow(card, months = true)
        }
        Section(Messages.HABIT_WHICH_DAYS) {
            card.strongestIsoDay?.let { Text(Messages.habitStrongest(it), style = MaterialTheme.typography.bodyLarge) }
            for (weekday in card.reliability) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = Messages.habitReliability(weekday) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(DateLabels.weekdayShort(weekday.isoDay), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
                    ShareBar(share = weekday.share, height = 16.dp, modifier = Modifier.weight(1f))
                    Text(Messages.habitFraction(weekday), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(heading: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DisplayText(heading, style = DisplayType.Badge, color = Tokens.Ink)
        content()
    }
}

/** SC11: the recent weeks or months as plain numbers over small accent bars, **and no comparison arrows**. */
@Composable
private fun TallyRow(card: HabitCard, months: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tally in if (months) card.recentMonths else card.recentWeeks) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                DisplayText(tally.days.toString(), style = DisplayType.Number, color = Tokens.Ink)
                ShareBar(share = tally.share, height = 8.dp, modifier = Modifier.fillMaxWidth())
                if (months) Text(monthOf(tally), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun monthOf(tally: PeriodTally): String = DateLabels.monthShort(LocalDate.parse(tally.start).monthNumber)

/** SC9, SC11: a bordered `Paper` track filled to [share] in the accent colour. Never red. */
@Composable
private fun ShareBar(share: Float, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(height).background(Tokens.Paper).inkBorder(Tokens.StrokeRule)) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(share).background(Tokens.Indigo))
    }
}
