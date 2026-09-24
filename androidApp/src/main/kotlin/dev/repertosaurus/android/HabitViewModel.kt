package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.session.Messages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The scorecards route's state (scorecards SC13-SC15). It reads and never writes. Loads run one at a
 * time, and **only the latest one requested lands**: an earlier load's card or failure is dropped, so
 * a quick scope flip never flashes the old scope's card.
 */
public class HabitViewModel internal constructor(
    private val read: (instrumentId: String?) -> HabitCard,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    public constructor(holder: DatabaseHolder, io: CoroutineDispatcher = Dispatchers.IO) :
        this({ instrumentId -> holder.repository.habit.card(instrumentId) }, io)

    private val _state = MutableStateFlow(HabitState())
    public val state: StateFlow<HabitState> = _state.asStateFlow()

    private val reads = Mutex()
    private var latest = 0L

    /** SC4: null is every instrument. */
    public fun load(instrumentId: String?) {
        val ticket = ++latest
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            serialised(reads, io, work = { read(instrumentId) }) { outcome ->
                if (ticket != latest) return@serialised
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { state.copy(card = it, loading = false) },
                        onFailure = { state.copy(loading = false, error = Messages.habitReadFailed(it)) },
                    )
                }
            }
        }
    }

    public companion object {
        public fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { HabitViewModel(graph.holder) }
        }
    }
}

/** The scorecards route's state. [card] is the last one that landed, for whichever scope it was. */
public data class HabitState(
    val card: HabitCard? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Safety review F23 N1: the card only if it was built for [instrumentId]; never another scope's. */
    public fun cardFor(instrumentId: String?): HabitCard? = card?.takeIf { it.instrumentId == instrumentId }
}
