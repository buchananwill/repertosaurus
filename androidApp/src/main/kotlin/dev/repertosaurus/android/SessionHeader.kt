package dev.repertosaurus.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.repertosaurus.android.theme.DieGlyph
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.HandMark
import dev.repertosaurus.android.theme.InkHeader
import dev.repertosaurus.android.theme.InkIconButton
import dev.repertosaurus.android.theme.MenuGlyph
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.ViewFilter

/**
 * visual-identity VI15: the Session screen's header — the menu and the hand mark, the active View as
 * the title, and "practising on … · N songs".
 *
 * - **The title switches Views:** two taps from anywhere to any other View.
 * - **Export stays up here:** it is the only backup until phase 2 sync exists.
 * - A chip tap forks a saved View into an unsaved one that **keeps the filter** (V13a), so that reads
 *   "Not saved" rather than a false "All songs". That wording is an assumption, not a decision.
 * - The subline names the practice instrument; a filtered View states its filter (V11) on a line of its
 *   own, because the two are separate (V13).
 */
@Composable
internal fun SessionHeader(
    view: SessionView?,
    instruments: List<InstrumentChip>,
    performers: List<RepertosaurusRepository.Performer>,
    songCount: Int,
    onOpenDrawer: () -> Unit,
    onExport: () -> Unit,
    onSwitchView: () -> Unit,
    onSuggest: () -> Unit,
    suggestEnabled: Boolean,
) {
    val filter = view?.filter ?: ViewFilter.NONE
    InkHeader(
        navigation = {
            InkIconButton(onClick = onOpenDrawer, contentDescription = "Menu") { MenuGlyph() }
            HandMark()
        },
        actions = {
            // VI15's Suggest slot, suggest SG1. Disabled while the rows load (F26 B1).
            InkIconButton(
                onClick = onSuggest,
                contentDescription = Messages.SUGGEST,
                containerColour = Tokens.Ochre,
                enabled = suggestEnabled,
            ) {
                DieGlyph()
            }
            SecondaryButton(text = "Export", onClick = onExport)
        },
        title = {
            DisplayText(
                text = when {
                    view == null -> "All songs"
                    view.saved -> view.name
                    filter.unfiltered -> "All songs"
                    else -> "Not saved"
                } + " ▾",
                style = DisplayType.ScreenTitle,
                maxLines = 2,
            )
        },
        subline = {
            DisplayText(
                text = "Practising on ${instrumentLabel(view?.practiceInstrumentId, instruments)}" +
                    " · $songCount ${if (songCount == 1) "song" else "songs"}",
                style = DisplayType.Subline,
            )
            if (!filter.unfiltered) {
                Text(text = filterSummary(filter, instruments, performers), style = MaterialTheme.typography.bodyMedium)
            }
        },
        onTitleClick = onSwitchView,
    )
}
