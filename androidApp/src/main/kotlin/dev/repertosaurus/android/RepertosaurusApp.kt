package dev.repertosaurus.android

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.LookupKind
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
 * **Repertoire-editing R27: three more siblings, and E25's revisit is declined.** Repertoire,
 * Songs and Artists are first-class routes; their drill-downs (the toggle list, the song detail)
 * are each screen's own state holding ids only. No route takes arguments from outside itself and
 * none is a third level.
 *
 * [lookup] is set for the seven lookup routes and null for the five that are screens of their
 * own; the dispatch below is an exhaustive `when` over the enum, so a new route that nobody
 * dispatches is a compile error.
 *
 * **The drawer label is the route's** (style review F17 N4): the drawer iterates [entries], so a
 * route added here is in the drawer with no second list to keep in step.
 */
internal enum class Route(private val title: String? = null, val lookup: LookupKind? = null) {
    /** The root, reached by back rather than from the drawer — so it has no label. */
    SESSION,

    // Repertoire-editing R27, in the drawer above the lookup kinds.
    REPERTOIRE("Repertoire"),
    SONGS("Songs"),
    ARTISTS("Artists"),

    /**
     * Triage T1: the ratings editor, from the View menu. No label, so not in the drawer; what it
     * opens on is its ViewModel's state, set before the route is entered, never a route argument.
     */
    RATINGS,

    // Declared in `LookupKind`'s own order, which is the drawer's order (E26).
    INSTRUMENTS(lookup = LookupKind.INSTRUMENT),
    PERFORMERS(lookup = LookupKind.PERFORMER),
    TAGS(lookup = LookupKind.TAG),
    GROOVES(lookup = LookupKind.GROOVE),
    VENUES(lookup = LookupKind.VENUE),
    BANDS(lookup = LookupKind.BAND),
    PRACTICE_CONTEXTS(lookup = LookupKind.PRACTICE_CONTEXT),
    ;

    /** The drawer label: a lookup route is named by its kind, the rest by their own title. Null for the root. */
    val label: String? get() = lookup?.plural ?: title

    companion object {
        /** R27: the editing routes, in declaration order — the drawer's upper block. */
        val EDITING: List<Route> = entries.filter { it.lookup == null && it.label != null }

        /** E26: the lookup routes, in declaration order (`LookupKind`'s) — the "Advanced" block. */
        val LOOKUPS: List<Route> = entries.filter { it.lookup != null }

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

/** Test tags for the drawer's own controls. */
internal object DrawerTags {
    /** Repertoire-editing R40-R42: the note spelling toggle. */
    const val NOTE_SPELLING: String = "drawer-note-spelling"
    const val NOTE_SPELLING_SWITCH: String = "drawer-note-spelling-switch"

    /** Rating-scale RS16: opens the colour ramp picker. */
    const val COLOUR_RAMP: String = "drawer-colour-ramp"
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
public fun RepertosaurusApp(
    viewModel: SessionViewModel,
    repertoire: RepertoireViewModel,
    songs: SongsViewModel,
    artists: ArtistsViewModel,
    settings: DeviceSettings,
    ratings: RatingsEditorViewModel,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var route by remember { mutableStateOf(Route.SESSION) }
    val databaseState by viewModel.databaseState.collectAsState()
    val noteSpelling by settings.noteSpelling.collectAsState()
    val colourRamp by settings.colourRamp.collectAsState()
    val ownerPerformerId by settings.ownerPerformerId.collectAsState()
    var pickingRamp by remember { mutableStateOf(false) }

    val close = { scope.launch { drawerState.close() } }

    // R26: **returning to the logger reloads it**, from every route. A toggle on the Repertoire
    // route changes which songs a View shows, a song edit changes a row's title, and the logger
    // must not keep the old list until the app restarts. `reload` re-reads the rows, the
    // instrument chips and the Views, and then the performers.
    //
    // F18 B1: **the reload waits for the route's write queue to drain.** A toggle tapped just
    // before back may still be queued; a reload that read first would miss it and keep the old
    // list. The lookup routes write through the logger's own ViewModel and reload it themselves.
    val toLogger = {
        val leaving = route
        if (leaving != Route.SESSION) {
            route = Route.SESSION
            when (leaving) {
                Route.REPERTOIRE -> viewModel.reloadAfter(repertoire::awaitIdle)
                Route.SONGS -> viewModel.reloadAfter(songs::awaitIdle)
                Route.ARTISTS -> viewModel.reloadAfter(artists::awaitIdle)
                Route.RATINGS -> {
                    ratings.close()
                    viewModel.reloadAfter(ratings::awaitIdle)
                }
                else -> viewModel.reload()
            }
        }
    }

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
    BackHandler(enabled = !drawerState.isOpen && route != Route.SESSION) { toLogger() }

    CompositionLocalProvider(LocalColourRamp provides colourRamp) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                AppDrawerContent(
                    route = route,
                    noteSpelling = noteSpelling,
                    onNavigate = { target ->
                        close()
                        route = target
                    },
                    onExport = {
                        close()
                        export()
                    },
                    onImport = {
                        close()
                        import()
                    },
                    onNoteSpelling = settings::setNoteSpelling,
                    onColourRamp = {
                        close()
                        pickingRamp = true
                    },
                )
            },
        ) {
            // E24: every `onBack` and the back handler above land on `Route.SESSION`, never on
            // another route, and R26 reloads the logger on the way. A drill-down inside a route has
            // its own back handler, composed below this one's and so consulted first.
            when (route) {
                Route.SESSION -> SessionScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onExport = { export() },
                    ownerPerformerId = ownerPerformerId,
                    onRateSongs = { target ->
                        ratings.open(target)
                        route = Route.RATINGS
                    },
                )
                Route.REPERTOIRE -> RepertoireScreen(viewModel = repertoire, ratings = ratings, onBack = toLogger)
                Route.RATINGS -> RatingsEditorScreen(viewModel = ratings, onDone = toLogger)
                Route.SONGS -> SongsScreen(
                    viewModel = songs,
                    session = viewModel,
                    settings = settings,
                    onBack = toLogger,
                )
                Route.ARTISTS -> ArtistsScreen(viewModel = artists, onBack = toLogger)
                // One screen serves every lookup kind (E13, E18); the route only says which.
                Route.INSTRUMENTS,
                Route.PERFORMERS,
                Route.TAGS,
                Route.GROOVES,
                Route.VENUES,
                Route.BANDS,
                Route.PRACTICE_CONTEXTS,
                -> ManageLookupScreen(
                    viewModel = viewModel,
                    kind = checkNotNull(route.lookup) { "$route is a lookup route with no kind" },
                    onBack = toLogger,
                )
            }
        }

        // RS16.
        if (pickingRamp) {
            ColourRampPicker(
                onSelect = { ramp ->
                    settings.setColourRamp(ramp)
                    pickingRamp = false
                },
                onDismiss = { pickingRamp = false },
            )
        }
    }
}

/**
 * The drawer's items (style review F9 B7). Every callback that leaves the drawer closes it first;
 * that is the caller's, so this only lays the items out.
 *
 * Seven lookup entries plus export and import overflow a short phone, and the drawer's own scroll
 * is vertical while the gesture E28 disables is horizontal — the two do not compete.
 *
 * **The scroll goes on the content, not on the sheet.** `ModalDrawerSheet` applies
 * `fillMaxHeight()` *after* the caller's modifier, so a `verticalScroll` out there measures its
 * child under an infinite height constraint and the fill has nothing left to fill: the panel
 * shrink-wraps its content instead of covering the screen, and the scroll itself never engages
 * because the viewport is as tall as the content. The symptom is screen-size and font-scale
 * dependent, which is exactly how it survived being looked at.
 */
@Composable
private fun AppDrawerContent(
    route: Route,
    noteSpelling: NoteSpelling,
    onNavigate: (Route) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onNoteSpelling: (NoteSpelling) -> Unit,
    onColourRamp: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Repertosaurus", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Offline. This phone holds the only copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // First item, and still in the top bar: until sync exists, the exported file is the
            // only backup that exists anywhere.
            DrawerItem("Export database", onClick = onExport)

            // Repertoire-editing R27: the three routes that reach the database outside a logger
            // View. "Better too many routes than anything inaccessible" — which of them is
            // foregrounded is decided later, from use.
            for (target in Route.EDITING) {
                DrawerItem(target.label.orEmpty(), onClick = { onNavigate(target) }, selected = route == target)
            }

            // Repertoire-editing R40-R42: a toggle in the drawer itself, not a route — one switch
            // needs no screen. It leaves the drawer open so the flip is seen.
            val simplified = noteSpelling == NoteSpelling.SIMPLIFIED
            val flipSpelling = {
                onNoteSpelling(if (simplified) NoteSpelling.AS_WRITTEN else NoteSpelling.SIMPLIFIED)
            }
            DrawerItem(
                // One line: the drawer item is a fixed 56dp, and a second line is clipped.
                "Simplify F♯♯ to G",
                onClick = flipSpelling,
                modifier = Modifier.testTag(DrawerTags.NOTE_SPELLING),
                badge = {
                    Switch(
                        checked = simplified,
                        onCheckedChange = { flipSpelling() },
                        modifier = Modifier.testTag(DrawerTags.NOTE_SPELLING_SWITCH),
                    )
                },
            )

            // Rating-scale RS16. Three ramps with swatches need more room than a switch, so this
            // opens a sheet.
            DrawerItem("Colour ramp", onClick = onColourRamp, modifier = Modifier.testTag(DrawerTags.COLOUR_RAMP))

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Text(
                "Advanced",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp),
            )

            // E26: **the drawer is the only menu of routes, and every manageable kind is in it**,
            // so "where do I edit X" has one answer rather than a scavenger hunt through
            // long-presses. Driven off the `Route` entries (F17 N4), and `Route.of` is exhaustive
            // over `LookupKind`, so adding a kind without a route — and so without a drawer
            // entry — is a compile error.
            //
            // E19 is worth remembering at the Practice contexts entry: it manages the *vocabulary*
            // and nothing else. The app still cannot attach a context to a logged event — that is
            // a change on the logging path, and the drawer entry is not the feature.
            for (target in Route.LOOKUPS) {
                DrawerItem(target.label.orEmpty(), onClick = { onNavigate(target) }, selected = route == target)
            }

            DrawerItem("Replace database from file", onClick = onImport)
        }
    }
}

/**
 * One drawer row, inset as every row is. Square (visual-identity VI3): the item's indicator is a
 * full-radius pill that ignores the theme's shapes.
 */
@Composable
private fun DrawerItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        badge = badge,
        shape = RectangleShape,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}
