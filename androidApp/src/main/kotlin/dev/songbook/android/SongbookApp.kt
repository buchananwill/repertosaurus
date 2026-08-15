package dev.songbook.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.songbook.session.LookupKind
import kotlinx.coroutines.launch

/**
 * The app's one navigation host. Two destinations, so this is a `when` over an enum and not
 * a navigation library — there is no back stack worth the dependency, and the Session
 * screen is where the user always is.
 */
private enum class Route { SESSION, INSTRUMENTS }

/**
 * The drawer and the routes around the Session screen.
 *
 * **Export stays in the top bar as well as being the first drawer item.** It is the user's
 * only backup until phase 2 sync exists, and putting the only route to it behind a swipe or
 * a hamburger is a real risk to their data — two entry points cost one line. Import moves
 * into the drawer alone: it is rare, destructive, and being slightly out of the way suits
 * it.
 *
 * Drawer **swipe-to-open is deliberately disabled** (`gesturesEnabled` only while open).
 * The Session screen's chip row scrolls horizontally across the full width, and an edge
 * drag that the drawer might claim is the same gesture the user makes to reach `Keys`.
 * Losing the chip row would be much worse than losing a swipe: the button opens the drawer,
 * and a swipe still closes it.
 */
@Composable
public fun SongbookApp(viewModel: SessionViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var route by remember { mutableStateOf(Route.SESSION) }

    val close = { scope.launch { drawerState.close() } }

    // The system save and open sheets. They live here because both the top bar and the
    // drawer reach them, and an ActivityResultLauncher must be remembered above both.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) viewModel.export { context.contentResolver.openOutputStream(uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        // Anything: a `.db` file has no registered MIME type on most providers, and
        // filtering by one is the fastest way to make the user's own backup unpickable.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.stageImport { context.contentResolver.openInputStream(uri) }
    }
    val export = { exportLauncher.launch(viewModel.exportFileName()) }
    val import = { importLauncher.launch(arrayOf("*/*")) }

    BackHandler(enabled = drawerState.isOpen) { close() }
    BackHandler(enabled = !drawerState.isOpen && route != Route.SESSION) { route = Route.SESSION }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Songbook", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Offline. This phone holds the only copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // First item, and still in the top bar: until sync exists, the exported
                // file is the only backup that exists anywhere.
                NavigationDrawerItem(
                    label = { Text("Export database") },
                    selected = false,
                    onClick = {
                        close()
                        export()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Text(
                    "Advanced",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp),
                )

                NavigationDrawerItem(
                    label = { Text("Instruments") },
                    selected = route == Route.INSTRUMENTS,
                    onClick = {
                        close()
                        route = Route.INSTRUMENTS
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                NavigationDrawerItem(
                    label = { Text("Replace database from file") },
                    selected = false,
                    onClick = {
                        close()
                        import()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        when (route) {
            Route.SESSION -> SessionScreen(
                viewModel = viewModel,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onExport = { export() },
            )
            Route.INSTRUMENTS -> ManageLookupScreen(
                viewModel = viewModel,
                kind = LookupKind.INSTRUMENT,
                onBack = { route = Route.SESSION },
            )
        }
    }
}
