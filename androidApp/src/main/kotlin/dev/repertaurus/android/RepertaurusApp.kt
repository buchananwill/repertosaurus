package dev.repertaurus.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.repertaurus.data.DatabaseState
import dev.repertaurus.session.LookupKind
import kotlinx.coroutines.launch

/**
 * The app's routes: the logger, and one per manageable lookup kind (E18).
 *
 * **E25 — a flat enum, no back stack, no navigation library.** The model is one root plus a
 * set of siblings, which an enum plus a back handler expresses completely; a navigation
 * dependency would buy argument passing and a stack this design deliberately does not have.
 * Revisit when a route genuinely needs arguments or a third level — not before.
 *
 * **E29 — this stays in the Android module.** It is presentation, not a rule, and the desktop
 * UI will have a different shape; what belongs in the shared core is the *state* each screen
 * reads, not the enum naming them.
 *
 * [lookup] is null for exactly one entry, [SESSION], and that is what makes the branch below
 * exhaustive without a second map to keep in step.
 */
private enum class Route(val lookup: LookupKind?) {
    SESSION(null),

    // Declared in `LookupKind`'s own order, which is the drawer's order (E26).
    INSTRUMENTS(LookupKind.INSTRUMENT),
    PERFORMERS(LookupKind.PERFORMER),
    TAGS(LookupKind.TAG),
    GROOVES(LookupKind.GROOVE),
    VENUES(LookupKind.VENUE),
    BANDS(LookupKind.BAND),
    PRACTICE_CONTEXTS(LookupKind.PRACTICE_CONTEXT),
    ;

    companion object {
        /**
         * The kind's route. Exhaustive on purpose: adding a [LookupKind] without giving it a
         * route is a **compile error here**, not a kind that silently never appears in the
         * drawer — which is precisely what E26 exists to prevent ("where do I edit X" has one
         * answer rather than a scavenger hunt).
         */
        fun of(kind: LookupKind): Route = when (kind) {
            LookupKind.INSTRUMENT -> INSTRUMENTS
            LookupKind.PERFORMER -> PERFORMERS
            LookupKind.TAG -> TAGS
            LookupKind.GROOVE -> GROOVES
            LookupKind.VENUE -> VENUES
            LookupKind.BAND -> BANDS
            LookupKind.PRACTICE_CONTEXT -> PRACTICE_CONTEXTS
        }
    }
}

/**
 * The drawer and the routes around the Session screen.
 *
 * **Export stays in the top bar as well as being the first drawer item.** It is the user's
 * only backup until phase 2 sync exists, and putting the only route to it behind a swipe or
 * a hamburger is a real risk to their data — two entry points cost one line. Import moves
 * into the drawer alone: it is rare, destructive, and being slightly out of the way suits
 * it.
 *
 * **E28 — drawer swipe-to-open stays disabled** (`gesturesEnabled` only while open).
 * The Session screen's chip row scrolls horizontally across the full width, and an edge
 * drag that the drawer might claim is the same gesture the user makes to reach `Keys`.
 * Losing the chip row would be much worse than losing a swipe: the button opens the drawer,
 * and a swipe still closes it. **A new screen must not re-enable it.**
 *
 * **E23, E24 — the logger is the root and back reaches it in at most two presses.** A route
 * returns to it in one, through the back handler below; a detail *inside* a route — one
 * performer, one capability — is a dialog with its own dismissal and closes first. There is
 * no deep stack to unwind, and no route from which the logger is three presses away.
 *
 * **The database gate is here, above everything** (schema-compatibility S9). When the database
 * will not open, [RecoveryScreen] replaces the whole tree — the drawer included — rather than
 * appearing inside it. The Session screen is the thing that cannot load, so routing through it
 * to reach recovery would be routing through the fault; the launchers stay hoisted above the
 * branch so the import picker is reachable from either side of it.
 */
@Composable
public fun RepertaurusApp(viewModel: SessionViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var route by remember { mutableStateOf(Route.SESSION) }
    val databaseState by viewModel.databaseState.collectAsState()

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

    // An exhaustive `when` over the sealed interface rather than an `as?` cast: a third state —
    // whatever sync or a long migration needs — must be a compile error here, not a silent fall
    // through into the Session screen, which is the exact failure shape this gate exists to
    // delete.
    when (val state = databaseState) {
        is DatabaseState.Unloadable -> {
            RecoveryScreen(state = state, viewModel = viewModel, onImport = { import() })
            return
        }
        DatabaseState.Ready -> Unit
    }

    BackHandler(enabled = drawerState.isOpen) { close() }
    BackHandler(enabled = !drawerState.isOpen && route != Route.SESSION) { route = Route.SESSION }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            // Seven lookup entries plus export and import overflow a short phone, and the
            // drawer's own scroll is vertical while the gesture E28 disables is horizontal —
            // the two do not compete.
            //
            // **The scroll goes on the content, not on the sheet.** `ModalDrawerSheet` applies
            // `fillMaxHeight()` *after* the caller's modifier, so a `verticalScroll` out here
            // measures its child under an infinite height constraint and the fill has nothing
            // left to fill: the panel shrink-wraps its content instead of covering the screen,
            // and the scroll itself never engages because the viewport is as tall as the
            // content. The symptom is screen-size and font-scale dependent, which is exactly
            // how it survived being looked at.
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Repertaurus", style = MaterialTheme.typography.headlineSmall)
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

                    // E26: **the drawer is the only menu of routes, and every manageable kind
                    // is in it**, so "where do I edit X" has one answer rather than a scavenger
                    // hunt through long-presses. Driven off `LookupKind.entries` so adding a
                    // kind adds a drawer entry with no edit here; its declaration order is this
                    // order.
                    //
                    // E19 is worth remembering at the Practice contexts entry: it manages the
                    // *vocabulary* and nothing else. The app still cannot attach a context to a
                    // logged event — that is a change on the logging path, and the drawer entry
                    // is not the feature.
                    for (kind in LookupKind.entries) {
                        val target = Route.of(kind)
                        NavigationDrawerItem(
                            label = { Text(kind.plural) },
                            selected = route == target,
                            onClick = {
                                close()
                                route = target
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }

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
            }
        },
    ) {
        // One screen serves every lookup kind (E13, E18); the route only says which. E24: both
        // `onBack` and the back handler above land on `Route.SESSION`, never on another route.
        when (val lookup = route.lookup) {
            null -> SessionScreen(
                viewModel = viewModel,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onExport = { export() },
            )
            else -> ManageLookupScreen(
                viewModel = viewModel,
                kind = lookup,
                onBack = { route = Route.SESSION },
            )
        }
    }
}
