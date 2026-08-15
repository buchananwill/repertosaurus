package dev.songbook.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.songbook.core.NearMatches
import dev.songbook.core.normalise
import dev.songbook.session.LookupItem
import dev.songbook.session.LookupKind

/**
 * Manage one lookup table. Today the drawer only reaches it with
 * [LookupKind.INSTRUMENT]; nothing in this file knows that.
 *
 * The whole screen is decisions 15 and 16 made concrete: the table is user-extensible, so
 * adding a row is typing a name and pressing enter, with near-matches surfaced while you
 * type. There is deliberately no admin flow, no save-and-return, and no second screen.
 *
 * Removing is a soft delete (decision 9) and says how much history it will hide. Renaming
 * keeps the id (decision 5), which is why a typo is a rename and not a delete-and-re-add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ManageLookupScreen(
    viewModel: SessionViewModel,
    kind: LookupKind,
    onBack: () -> Unit,
) {
    val state by viewModel.lookups.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var typed by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<LookupItem?>(null) }
    var removing by remember { mutableStateOf<LookupItem?>(null) }

    LaunchedEffect(kind) { viewModel.loadLookups(kind) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearLookupMessage()
        }
    }

    // Near-matches over the rows already on screen: no query per keystroke, and the same
    // matcher the artist type-ahead uses, so "Ukelele" surfaces "Ukulele" before a second
    // row can be committed.
    val suggestions = remember(typed, state.items) {
        NearMatches.search(typed, state.items, limit = 4) { it.name }
    }
    val exact = remember(typed, suggestions) {
        val needle = normalise(typed)
        suggestions.firstOrNull { needle.isNotEmpty() && normalise(it.name) == needle }
    }
    val commit = {
        if (typed.isNotBlank()) {
            viewModel.addLookup(kind, typed)
            typed = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(kind.plural) },
                actions = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Add ${article(kind.singular)} ${kind.singular}") },
                supportingText = {
                    Text(
                        when {
                            exact != null -> "${exact.name} is already here."
                            suggestions.isNotEmpty() -> "Did you mean one of these?"
                            else -> "Type a name and press enter."
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                        FilterChip(
                            selected = false,
                            onClick = { typed = match.name },
                            label = { Text(match.name) },
                            modifier = Modifier.height(44.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = commit, enabled = typed.isNotBlank()) {
                    Text(if (exact != null) "Keep the existing one" else "Add")
                }
            }

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when (item.usageCount) {
                                    0L -> "no practice logged"
                                    1L -> "1 practice logged"
                                    else -> "${item.usageCount} practices logged"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { renaming = item }) { Text("Rename") }
                        TextButton(onClick = { removing = item }) { Text("Remove") }
                    }
                    HorizontalDivider()
                }

                if (state.items.isEmpty() && !state.busy) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
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
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${item.name}?") },
            text = {
                Text(
                    when (item.usageCount) {
                        0L ->
                            "It has no practice logged against it. It disappears from the " +
                                "chip row; nothing else changes."
                        1L ->
                            "It has 1 practice logged against it. That event is kept and " +
                                "still counts — the ${kind.singular} just stops appearing " +
                                "in the chip row."
                        else ->
                            "It has ${item.usageCount} practices logged against it. Those " +
                                "events are kept and still count — the ${kind.singular} " +
                                "just stops appearing in the chip row."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeLookup(kind, item.id, item.name)
                        removing = null
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                )
                Text(
                    "Practice already logged against this ${kind.singular} keeps counting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun article(word: String): String =
    if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
