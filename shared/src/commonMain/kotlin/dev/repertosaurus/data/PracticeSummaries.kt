package dev.repertosaurus.data

import dev.repertosaurus.data.RepertosaurusRepository.PracticeEntry
import dev.repertosaurus.data.RepertosaurusRepository.PracticeSummary

/**
 * repertoire-editing R20: times practised and last practised, per instrument [history] has live events
 * on. Ordered by instrument name, then id; decision 18's chip order is the screen's.
 */
public fun summarise(history: List<PracticeEntry>): List<PracticeSummary> =
    history
        .groupBy { it.instrumentId }
        .map { (instrumentId, events) ->
            PracticeSummary(
                instrumentId = instrumentId,
                instrumentName = events.first().instrumentName,
                timesPractised = events.size.toLong(),
                lastPractised = events.maxOf { it.loggedOn },
            )
        }
        .sortedWith(compareBy({ it.instrumentName.orEmpty() }, { it.instrumentId }))
