package dev.repertosaurus.android

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import dev.repertosaurus.android.theme.ChoiceHeader
import dev.repertosaurus.android.theme.InkSheet
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.Messages

/** Test tags for the owner performer list (triage T10). */
internal object OwnerPerformerTags {
    /** onboarding OB6. */
    const val OWNER_PICKER: String = "owner-performer-picker"
    const val OWNER_NONE: String = "owner-performer-none"
    fun owner(performerId: String): String = "owner-performer-$performerId"
}

/**
 * triage T10's "who is me": the live performers and one [noneLabel] row, the current choice marked. A tap
 * chooses; [onChoose] takes the performer's id, or null for none. Shown by onboarding's performer step
 * (OB2) and by the drawer's "Who you are" (OB6).
 *
 * T10: an owner who is not among the live performers, removed since, resolves to none.
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
                modifier = Modifier.testTag(OwnerPerformerTags.owner(performer.id)),
            )
        }
        ChoiceRow(
            label = noneLabel,
            selected = current == null,
            onClick = { onChoose(null) },
            modifier = Modifier.testTag(OwnerPerformerTags.OWNER_NONE),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    RuledItem {
        ChoiceHeader(
            label = label,
            selected = selected,
            modifier = modifier
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .heightIn(min = Tokens.TouchMin),
        )
    }
}

/** onboarding OB6. [onChoose] persists; the caller closes the sheet. */
@Composable
internal fun OwnerPerformerPicker(
    performers: List<RepertosaurusRepository.Performer>,
    ownerPerformerId: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    InkSheet(title = Messages.WHO_YOU_ARE, onDismiss = onDismiss, modifier = Modifier.testTag(OwnerPerformerTags.OWNER_PICKER)) {
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
