package dev.repertosaurus.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.repertosaurus.session.SessionRow

/**
 * visual-identity VI20: a row just logged out of the pending list, held where it was while its exit
 * runs, so the rows below stay put until it has gone and then spring up into the gap. The state holds
 * nothing of this: there, the row has already moved to the logged section.
 *
 * Where it was is [before], the song shown directly beneath it, live or itself leaving; [index] is the
 * fallback for when that one has gone too. Null [before]: it was the last row.
 */
@Immutable
internal class Leaving(val row: SessionRow, val before: String?, val index: Int)

/** One item of the pending block: a live row, or one on its way out. */
@Immutable
internal sealed interface ListEntry {
    val songId: String
    val key: String

    class Pending(val row: SessionRow) : ListEntry {
        override val songId: String get() = row.songId
        override val key: String get() = "pending:$songId"
    }

    class Departing(val leaving: Leaving) : ListEntry {
        override val songId: String get() = leaving.row.songId
        override val key: String get() = "leaving:$songId"
    }
}

/**
 * VI20: the rows on their way out of the Session list. **The log always runs first and is never held
 * back**; a departure is only drawn around it. Input is never blocked: another tap departs another row,
 * concurrently.
 */
@Stable
internal class Departures(private val latestPending: () -> List<SessionRow>) {
    private val leaving = mutableStateMapOf<String, Leaving>()

    /** [pending] with every leaving row put back where it was. */
    fun entries(pending: List<SessionRow>): List<ListEntry> = withLeaving(pending, leaving)

    /**
     * Run [log] for [row], then start its exit — only if the log really took it out of the pending
     * list, which `log` settles before it returns. Its place is noted from the list as drawn over
     * [shownPending], the pending rows last composed.
     */
    fun logAndDepart(row: SessionRow, shownPending: List<SessionRow>, log: () -> Unit) {
        val shown = entries(shownPending)
        val at = shown.indexOfFirst { it is ListEntry.Pending && it.songId == row.songId }
        log()
        if (at >= 0 && latestPending().none { it.songId == row.songId }) {
            leaving[row.songId] = Leaving(row, before = shown.getOrNull(at + 1)?.songId, index = at)
        }
    }

    /** A row's exit has run. */
    fun left(songId: String) {
        leaving.remove(songId)
    }

    /**
     * A write that fails puts its row back in the pending list. Its departure must not outlive that, or
     * a later search that hides the row would play an exit for a log that never happened.
     */
    fun forgetLive(pending: List<SessionRow>) {
        val live = pending.mapTo(HashSet()) { it.songId }
        leaving.keys.removeAll(live)
    }
}

/**
 * The Session list's [Departures]. [latestPending] reads the ViewModel's state as it is now, not as it
 * was last composed: that is how a departure knows its log took effect.
 */
@Composable
internal fun rememberDepartures(pending: List<SessionRow>, latestPending: () -> List<SessionRow>): Departures {
    val latest by rememberUpdatedState(latestPending)
    val departures = remember { Departures { latest() } }
    LaunchedEffect(pending) { departures.forgetLive(pending) }
    return departures
}

/**
 * [pending] with each leaving row put back directly above the song that was beneath it, which may
 * itself be leaving, so the placing repeats until nothing more can be placed. One whose neighbour has
 * gone altogether falls back to its old position. A row that is pending again is its live self.
 */
private fun withLeaving(pending: List<SessionRow>, leaving: Map<String, Leaving>): List<ListEntry> {
    val entries: MutableList<ListEntry> = pending.mapTo(ArrayList(pending.size + leaving.size)) { ListEntry.Pending(it) }
    if (leaving.isEmpty()) return entries
    val live = pending.mapTo(HashSet()) { it.songId }
    val unplaced = leaving.values.filterTo(ArrayList()) { it.row.songId !in live }
    var placedAny = true
    while (unplaced.isNotEmpty() && placedAny) {
        placedAny = unplaced.removeAll { ghost ->
            val at = if (ghost.before == null) entries.size else entries.indexOfFirst { it.songId == ghost.before }
            if (at >= 0) entries.add(at, ListEntry.Departing(ghost))
            at >= 0
        }
    }
    for (ghost in unplaced.sortedBy { it.index }) {
        entries.add(ghost.index.coerceAtMost(entries.size), ListEntry.Departing(ghost))
    }
    return entries
}
