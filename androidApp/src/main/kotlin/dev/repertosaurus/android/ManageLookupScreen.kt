package dev.repertosaurus.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DialogText
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.RouteHeader
import dev.repertosaurus.android.theme.RowRule
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.android.theme.StackedActions
import dev.repertosaurus.android.theme.TextAction
import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.session.LookupItem
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupSuggestions
import dev.repertosaurus.session.Messages

/**
 * Manage one lookup table. **All seven kinds reach it** (E18), `performer` included — E13 is
 * explicit that the roster is this screen and not a bespoke one, because a second screen doing
 * the same job in a different way is how two implementations drift. Nothing in this file knows
 * which kind it is showing beyond what [LookupKind] declares.
 *
 * The whole screen is decisions 15 and 16 made concrete: the table is user-extensible, so
 * adding a row is typing a name and pressing enter, with near-matches surfaced while you
 * type. There is deliberately no admin flow, no save-and-return, and no second screen.
 *
 * Removing is a soft delete (decision 9) and says how much history it will hide. Renaming
 * keeps the id (decision 5, E21), which is why a typo is a rename and not a delete-and-re-add.
 *
 * Two things the kind supplies rather than this screen deciding:
 * - **E14 — [LookupItem.subtitle]**, a second line the store fills in. For `PERFORMER` it is
 *   the instruments the person is recorded on, derived from `song_performer` and never stored
 *   (E15); for the others it is null and the line is absent.
 * - **E16 — [LookupKind.hasNotes]**, which decides whether the Notes control appears at all.
 *   It is false for `venue` (E16a), whose table has no such column, and the store refuses the
 *   write there — so the flag is the guard, not a decoration.
 */
@Composable
public fun ManageLookupScreen(
    viewModel: SessionViewModel,
    kind: LookupKind,
    onBack: () -> Unit,
) {
    val state by viewModel.lookups.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Each of these is a detail *inside* the route (E24): it has the dialog's own back
    // handling and closes first, leaving the route itself one further press from the logger.
    var typed by remember(kind) { mutableStateOf("") }
    var renaming by remember(kind) { mutableStateOf<LookupItem?>(null) }
    var removing by remember(kind) { mutableStateOf<LookupItem?>(null) }
    var noting by remember(kind) { mutableStateOf<LookupItem?>(null) }

    LaunchedEffect(kind) { viewModel.loadLookups(kind) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearLookupMessage()
        }
    }

    // One state holder serves seven kinds now (E18), and `loadLookups` is asynchronous, so
    // between navigating here and the query landing the flow still carries the previous
    // kind's rows. Showing them would offer Remove on an instrument from the tag screen.
    val items = if (state.kind == kind) state.items else emptyList()

    // Near-matches over the rows already on screen: no query per keystroke, and the one lookup
    // matcher (F22 B2), so "Ukelele" surfaces "Ukulele" before a second row can be committed.
    // This screen's cap of four is kept as it was.
    val suggestions = remember(typed, items) { LookupSuggestions.search(typed, items, limit = 4) }
    val exact = remember(typed, suggestions) { NearMatches.exact(typed, suggestions) { it.name } }
    val commit = {
        if (typed.isNotBlank()) {
            viewModel.addLookup(kind, typed)
            typed = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { RouteHeader(title = kind.plural, onDone = onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            InkTextField(
                value = typed,
                onValueChange = { typed = it },
                // E46: the article is English grammar and the wording below is seven branches
                // of per-kind phrasing. Both are rules, both are JVM-tested in the shared core,
                // and the desktop UI needs all seven of them verbatim.
                label = "Add ${kind.withArticle}",
                supportingText = when {
                    exact != null -> "${exact.name} is already here."
                    suggestions.isNotEmpty() -> "Did you mean one of these?"
                    else -> "Type a name and press enter."
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (match in suggestions) {
                        InkChip(label = match.name, onClick = { typed = match.name })
                    }
                }
            }

            PrimaryButton(
                text = if (exact != null) "Keep the existing one" else "Add",
                onClick = commit,
                enabled = typed.isNotBlank(),
                modifier = Modifier.padding(16.dp),
            )

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            RowRule()

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    RuledItem {
                        LookupRowItem(
                            item = item,
                            kind = kind,
                            onRename = { renaming = item },
                            onRemove = { removing = item },
                            onNotes = { noting = item },
                        )
                    }
                }

                if (items.isEmpty() && !state.busy) emptyListLine(query = "", whenEmpty = "Nothing here yet.")
            }
        }
    }

    renaming?.let { item ->
        RenameDialog(
            item = item,
            kind = kind,
            onDismiss = { renaming = null },
            onRename = { name ->
                viewModel.renameLookup(kind, item.id, name)
                renaming = null
            },
        )
    }

    removing?.let { item ->
        InkDialog(onDismiss = { removing = null }, title = "Remove ${item.name}?") {
            // E22: the screen states how many rows removal will hide *before* it happens,
            // and E17 makes what is counted the kind's own business — practice events for
            // an instrument, capability rows for a performer, tagged songs for a tag.
            DialogText(
                if (item.usageCount == 0L) {
                    "Nothing points at it. It stops being offered; nothing else changes."
                } else {
                    "It has ${kind.usagePhrase(item.usageCount)}. Those rows are kept " +
                        "and still count — the ${kind.singular} just stops being offered."
                },
            )
            StackedActions(
                primary = Messages.REMOVE,
                onPrimary = {
                    viewModel.removeLookup(kind, item.id, item.name)
                    removing = null
                },
                onSecondary = { removing = null },
            )
        }
    }

    noting?.let { item ->
        NotesDialog(
            item = item,
            kind = kind,
            onDismiss = { noting = null },
            onSave = { notes ->
                viewModel.setLookupNotes(kind, item.id, notes)
                noting = null
            },
        )
    }
}

/**
 * One managed row: its name, E14's subtitle where the kind supplies one, what removing it
 * would hide (E17), and the notes where the kind has a column for them (E16).
 *
 * Nothing here is a tap target for the row itself. The three actions are explicit buttons,
 * because a mis-tap on a roster of 19 performers should do nothing at all.
 */
@Composable
private fun LookupRowItem(
    item: LookupItem,
    kind: LookupKind,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)

                // E14, E15: for a performer this is the instruments they are recorded on,
                // derived from `song_performer` and never stored. E15's accepted gap shows
                // here as an absent line: a performer with no capability rows yet has no
                // subtitle at all, and the roster still lists them.
                item.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                MutedLine(if (item.usageCount == 0L) "nothing points at it" else kind.usagePhrase(item.usageCount))
            }
            if (kind.hasNotes) {
                TextAction("Notes", onClick = onNotes)
            }
            TextAction(Messages.RENAME, onClick = onRename)
            TextAction(Messages.REMOVE, onClick = onRemove)
        }

        if (kind.hasNotes) {
            item.notes?.let { notes -> MutedLine(notes) }
        }
    }
}

/**
 * E16 and E16a: the notes field, offered only where [LookupKind.hasNotes] is true.
 *
 * That is `performer` and `band` and nothing else. `venue` looks like it should have one and
 * does not — its table is `id`, `name` and the standard three, and giving it notes is a schema
 * change with a version bump and a migration behind it. The kind's flag and the table's real
 * shape are pinned together by a test in the shared core, because a mismatch here is a crash
 * on a screen rather than a compile error.
 */
@Composable
private fun NotesDialog(
    item: LookupItem,
    kind: LookupKind,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var notes by remember(item.id) { mutableStateOf(item.notes.orEmpty()) }
    InkDialog(onDismiss = onDismiss, title = "Notes on ${item.name}") {
        InkTextField(
            value = notes,
            onValueChange = { notes = it },
            label = "Notes",
            singleLine = false,
            minLines = 3,
        )
        MutedLine("Yours, on this ${kind.singular}. Clearing the field removes them.")
        StackedActions(primary = Messages.SAVE, onPrimary = { onSave(notes) }, onSecondary = onDismiss)
    }
}

/** Renaming keeps the id (decision 5), so every practice event stays attached. */
@Composable
private fun RenameDialog(
    item: LookupItem,
    kind: LookupKind,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    InkDialog(onDismiss = onDismiss, title = "Rename ${item.name}") {
        InkTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onRename(name) }),
        )
        // E21: an id is opaque and immutable once written, and every referencing
        // row points at it. Renaming never re-derives one.
        MutedLine("Everything already recorded against this ${kind.singular} stays attached to it.")
        StackedActions(primary = Messages.RENAME, onPrimary = { onRename(name) }, onSecondary = onDismiss, primaryEnabled = name.isNotBlank())
    }
}
