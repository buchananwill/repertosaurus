package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.data.DatabaseState

/** Test tags, so the instrumented test asserting S10 names the same things the screen shows. */
public object RecoveryTags {
    public const val SCREEN: String = "recovery-screen"
    public const val REASON: String = "recovery-reason"
    public const val IMPORT: String = "recovery-import"
    public const val START_FRESH: String = "recovery-start-fresh"
    public const val RETRY: String = "recovery-retry"
}

/**
 * What the app shows when the database will not open (schema-compatibility S9).
 *
 * The whole point is that it is **reachable**. A crash on the boot path removed the only route
 * to recovery, because the import UI that fixes a bad database lives inside the app: the app
 * became unfixable from the device and survived a full uninstall–reinstall–reimport, since the
 * import validator accepted the same obsolete file every time. So this screen is deliberately
 * standalone — no drawer, no Session screen, nothing that touches the database it is here
 * because of.
 *
 * S11: it states the problem and offers an action. It does not present an unreadable database
 * as an empty one.
 *
 * **Import comes first and is the recommended action** (S4). Starting fresh is the fallback for
 * a user who has no file, and it asks before it deletes.
 */
@Composable
public fun RecoveryScreen(
    state: DatabaseState.Unloadable,
    viewModel: SessionViewModel,
    onImport: () -> Unit,
) {
    val transfer by viewModel.transfer.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingFresh by remember { mutableStateOf(false) }

    LaunchedEffect(transfer.message) {
        transfer.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearTransferMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .testTag(RecoveryTags.SCREEN),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Repertosaurus can't open your database",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                state.reason,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(RecoveryTags.REASON),
            )

            // Deliberately *not* "nothing has been deleted". Two of the paths that reach this
            // screen have already written to the file — the version stamp is brought forward
            // before the driver opens, and an upgrade that then fails verification has already
            // run its migration. Promising the user their file is untouched when it may not be
            // is the same class of defect as the silent empty list S11 forbids: a statement about
            // their data that they cannot check and that is sometimes false.
            Text(
                "Your database is still on this phone, at ${state.file}. Importing replaces " +
                    "it; starting fresh deletes it. Nothing else here changes it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )

            if (transfer.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            Button(
                onClick = onImport,
                enabled = !transfer.busy,
                modifier = Modifier.fillMaxWidth().testTag(RecoveryTags.IMPORT),
            ) {
                Text("Import a database")
            }
            Text(
                "Pick a Repertosaurus export. This is the recommended fix, and the one that " +
                    "keeps your repertoire.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { viewModel.retryDatabase() },
                enabled = !transfer.busy,
                modifier = Modifier.fillMaxWidth().testTag(RecoveryTags.RETRY),
            ) {
                Text("Try again")
            }

            OutlinedButton(
                onClick = { confirmingFresh = true },
                enabled = !transfer.busy,
                modifier = Modifier.fillMaxWidth().testTag(RecoveryTags.START_FRESH),
            ) {
                Text("Start fresh")
            }
            Text(
                "Deletes the file above and creates an empty database. Only do this if you " +
                    "have no export to import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmingFresh) {
        AlertDialog(
            onDismissRequest = { confirmingFresh = false },
            title = { Text("Delete this database?") },
            text = {
                Text(
                    "Everything in ${state.file} is removed and an empty database takes its " +
                        "place. This cannot be undone, and there is no other copy on this " +
                        "phone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingFresh = false
                        viewModel.startFresh()
                    },
                ) {
                    Text("Delete and start fresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingFresh = false }) { Text("Cancel") }
            },
        )
    }

    // The same two-step import the Session screen runs, because it is the same import: staging
    // describes the file, and only the confirmation overwrites anything.
    transfer.pending?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Use this database?") },
            text = {
                Text(
                    "The file you picked holds ${preview.songs} songs and " +
                        "${preview.practiceEvents} practice events.\n\n" +
                        "It replaces the database this phone cannot read.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text("Cancel") }
            },
        )
    }
}
