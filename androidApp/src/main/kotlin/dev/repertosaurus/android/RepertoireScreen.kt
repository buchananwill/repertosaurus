package dev.repertosaurus.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.RuledItem
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.PerformerRoles
import dev.repertosaurus.session.RatingsSource
import dev.repertosaurus.session.RatingsTarget
import dev.repertosaurus.session.ToggleList

/** Stable handles for the instrumented tests. */
internal object RepertoireTags {
    const val PERFORMERS: String = "repertoire-performers"
    const val TOGGLE_LIST: String = "repertoire-toggle-list"
    const val HELD_COUNT: String = "repertoire-held-count"

    /** Triage T1: the role's "Ratings" entry. */
    const val RATINGS: String = "repertoire-ratings"

    /** Triage T5a: the paging controls' prefix — `repertoire-search`, `repertoire-letter-D`. */
    const val PAGING: String = "repertoire"
    fun row(songId: String): String = "repertoire-row-$songId"
    fun addRole(performerId: String): String = "repertoire-add-role-$performerId"
}

/**
 * The Repertoire route — repertoire-editing R1-R10: performer → role → toggle songs.
 *
 * **R1: performer and role are picked on one screen**, so the toggle list is one level below
 * the route and E24's "logger within two presses" holds: back from the list closes it, back from
 * the route returns to the logger. The open pair is screen state holding **ids only**, saved
 * across rotation (R27).
 *
 * **Triage T1: a role's two panes** ([RolePane]): its toggle list, and the ratings editor on the songs
 * it is enabled on. The editor is the role seen a second way, not a level below it, so back from either
 * closes the role and the logger stays two presses away (E24, journal session 11 D59 #1).
 */
@Composable
public fun RepertoireScreen(viewModel: RepertoireViewModel, ratings: RatingsEditorViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var performerId by rememberSaveable { mutableStateOf<String?>(null) }
    var instrumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pane by rememberSaveable { mutableStateOf(RolePane.SONGS) }
    // Saved across rotation but not across leaving the route, so it tells the two apart.
    var entered by rememberSaveable { mutableStateOf(false) }

    // F18 N8: entering the route clears the last visit's messages and drops its list, so the same
    // pair opened again is read again. A rotation is not an entry: the open list, with any
    // optimistic rows, survives it.
    LaunchedEffect(Unit) {
        if (!entered) {
            entered = true
            viewModel.enter()
            // The last visit's editor goes too, so the same role's "Ratings" reads its songs again.
            ratings.close()
        }
    }

    val openPerformer = performerId
    val openInstrument = instrumentId
    LaunchedEffect(openPerformer, openInstrument) {
        if (openPerformer != null && openInstrument != null) {
            viewModel.openList(openPerformer, openInstrument)
        }
    }

    // Triage T1: the part the Ratings pane rates, named as the toggle list's heading names it.
    val performer = openPerformer?.let(state::performer)
    val instrumentLabel = openInstrument?.let { performer?.instrument(it)?.label }.orEmpty()
    val ratingsTarget = if (openPerformer != null && openInstrument != null) {
        RatingsTarget(openPerformer, openInstrument, performer?.performerName.orEmpty(), instrumentLabel, RatingsSource.EnabledParts)
    } else {
        null
    }
    // After the toggle queue drains (F18 B1), so a song just switched on is in the editor's list. Keyed
    // on the target, so after process death the pane re-derives its editor (F20 N3).
    LaunchedEffect(ratingsTarget, pane) {
        if (pane == RolePane.RATINGS && ratingsTarget != null) ratings.open(ratingsTarget, after = viewModel::awaitIdle)
    }

    val close = {
        performerId = null
        instrumentId = null
        pane = RolePane.SONGS
        ratings.close()
        viewModel.closeList()
    }

    // E24: the drill-down's own back handler, composed after the route's and so consulted first.
    BackHandler(enabled = openPerformer != null) { close() }

    val list = state.list
    when {
        ratingsTarget != null && pane == RolePane.RATINGS -> RatingsEditorScreen(
            viewModel = ratings,
            onDone = close,
            onSongs = {
                pane = RolePane.SONGS
                ratings.close()
            },
        )
        ratingsTarget != null && list != null -> ToggleListScreen(
            performerName = performer?.performerName.orEmpty(),
            instrumentLabel = instrumentLabel,
            list = list,
            message = state.message,
            error = state.error,
            onQuery = viewModel::setQuery,
            onLetter = viewModel::setLetter,
            onFilter = viewModel::setFilter,
            onToggle = viewModel::toggle,
            onRatings = { pane = RolePane.RATINGS },
            onClose = close,
        )
        else -> PerformerListScreen(
            performers = state.performers,
            loading = state.loadingPerformers,
            message = state.message,
            error = state.error,
            onOpen = { performer, instrument ->
                viewModel.clearMessages()
                performerId = performer
                instrumentId = instrument
            },
            onBack = onBack,
        )
    }
}

/** R1, R2: every live performer, a chip per held role, and "+ role". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerformerListScreen(
    performers: List<PerformerRoles>,
    loading: Boolean,
    message: String?,
    error: String?,
    onOpen: (performerId: String, instrumentId: String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repertoire") },
                actions = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Pick a performer's role to choose the songs they hold on it. Nothing here logs " +
                    "practice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            StatusLines(message = message, error = error)
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag(RepertoireTags.PERFORMERS),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(performers, key = { it.performerId }) { performer ->
                    PerformerRow(performer = performer, onOpen = onOpen)
                    HorizontalDivider()
                }
                if (performers.isEmpty() && !loading) {
                    item(key = "empty") {
                        Text(
                            "No performers yet. Add one from Performers in the menu.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformerRow(
    performer: PerformerRoles,
    onOpen: (performerId: String, instrumentId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember(performer.performerId) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(performer.performerName, style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (role in performer.roles) {
                AssistChip(
                    onClick = { onOpen(performer.performerId, role.id) },
                    label = { Text(role.label) },
                    modifier = Modifier.height(44.dp),
                )
            }
            // R2: the only way to give a performer their first song on an instrument, so it is
            // always on the row, never behind a further menu.
            if (performer.unheldInstruments.isNotEmpty()) {
                Box {
                    AssistChip(
                        onClick = { picking = true },
                        label = { Text("+ role") },
                        modifier = Modifier
                            .height(44.dp)
                            .testTag(RepertoireTags.addRole(performer.performerId)),
                    )
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        for (instrument in performer.unheldInstruments) {
                            DropdownMenuItem(
                                text = { Text(instrument.label) },
                                onClick = {
                                    picking = false
                                    onOpen(performer.performerId, instrument.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * R3-R8: every live song, a search, a held count, and one toggle per row.
 *
 * **R5 / E1 / E11: a row tap toggles and nothing else on the row is a tap target.** The switch
 * is drawn with no click handler of its own; the whole row is one `toggleable`.
 *
 * Triage T5a: the ratings editor's search, filter and letter strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleListScreen(
    performerName: String,
    instrumentLabel: String,
    list: ToggleList,
    message: String?,
    error: String?,
    onQuery: (String) -> Unit,
    onLetter: (Char) -> Unit,
    onFilter: (PageFilter) -> Unit,
    onToggle: (String) -> Unit,
    onRatings: () -> Unit,
    onClose: () -> Unit,
) {
    val paging = list.page.paging
    val visible = remember(list.rows, list.page.order) { list.visible() }
    // T3's greyed letters follow the flags, which only matter under a filter (F22 N1).
    // Under All only the song set matters, which a toggle leaves alone.
    val rowsKey = if (paging.filter == PageFilter.ALL) list.ticket to list.rows.size else list.rows
    val letters = remember(rowsKey, paging.filter) { paging.letters(list.paged()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            performerName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            instrumentLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRatings, modifier = Modifier.testTag(RepertoireTags.RATINGS)) {
                        Text("Ratings")
                    }
                    TextButton(onClick = onClose) { Text("Done") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).testTag(RepertoireTags.TOGGLE_LIST)) {
            Text(
                Messages.shownOf(list.heldCount, Messages.songCount(list.rows.size.toLong())) + " held",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(RepertoireTags.HELD_COUNT),
            )
            StatusLines(message = message, error = error)
            if (list.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                pagingControls(
                    paging = paging,
                    letters = letters,
                    filters = TOGGLE_FILTERS,
                    onQuery = onQuery,
                    onLetter = onLetter,
                    onFilter = onFilter,
                    tagPrefix = RepertoireTags.PAGING,
                )
                items(visible, key = { it.songId }) { row ->
                    ToggleRow(
                        songId = row.songId,
                        title = row.title,
                        artistName = row.artistName,
                        held = row.held,
                        enabled = row.songId !in list.inFlight,
                        onToggle = onToggle,
                    )
                }
                if (visible.isEmpty() && !list.loading) {
                    emptyListLine(
                        paging.query,
                        whenEmpty = if (list.rows.isEmpty()) Messages.NO_SONGS_YET else Messages.NOTHING_UNDER_THE_FILTER,
                    )
                }
            }
        }
    }
}

/** Triage T5a: the toggle list's filter words, in strip order. */
private val TOGGLE_FILTERS = listOf(PageFilter.ALL to "All", PageFilter.SET to "On", PageFilter.UNSET to "Off")

/**
 * One song: its label and whether this pair holds it. The whole row is the one control. Primitives
 * only, so a keystroke or another row's toggle skips it (Compose review F22 B1).
 */
@Composable
private fun ToggleRow(
    songId: String,
    title: String,
    artistName: String?,
    held: Boolean,
    enabled: Boolean,
    onToggle: (songId: String) -> Unit,
) {
    RuledItem {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = held,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onToggle(songId) },
                )
                .testTag(RepertoireTags.row(songId))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // E46: the one song label, from the core.
            SongLabelText(title = title, artistName = artistName, modifier = Modifier.weight(1f))
            // No handler: the row is the tap target (R5), and a second one would be a second path.
            Switch(checked = held, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** Triage T1: a role's two panes (journal session 11, D59 #1). */
private enum class RolePane { SONGS, RATINGS }
