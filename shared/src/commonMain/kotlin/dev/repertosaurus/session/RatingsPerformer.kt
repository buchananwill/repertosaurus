package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository

/** triage T9: the part a View's ratings belong to. [name] is the performer's, for the editor's title. */
public data class ResolvedPart(val performerId: String, val name: String, val instrumentId: String)

/** triage T9's answer (F20 N2): not yet known, a part, or nobody. */
public sealed interface PartResolution {
    /** The performers have not been read yet, so nothing can be said. */
    public data object Pending : PartResolution

    public data class Resolved(val part: ResolvedPart) : PartResolution

    /** Nobody resolves: the triage entry points are disabled with [Messages.RATE_NEEDS_PERFORMER]. */
    public data object None : PartResolution
}

/**
 * **triage T9: whose part the View's ratings are**, in T9's order: the filter performer, then the owner
 * (T10), then none. Nothing is guessed.
 *
 * A performer not in [performers] (the live ones) resolves to none (T10). A filter naming a removed
 * performer resolves to none too, not to the owner (journal session 11, D59 #5).
 */
public object RatingsPerformer {

    /** [performers] is null until they have been read (F20 N2). [instrumentId] is the View's practice instrument. */
    public fun resolve(
        filterPerformerId: String?,
        ownerPerformerId: String?,
        instrumentId: String,
        performers: List<RepertosaurusRepository.Performer>?,
    ): PartResolution {
        if (performers == null) return PartResolution.Pending
        val wanted = filterPerformerId ?: ownerPerformerId ?: return PartResolution.None
        val performer = performers.firstOrNull { it.id == wanted } ?: return PartResolution.None
        return PartResolution.Resolved(ResolvedPart(performer.id, performer.name, instrumentId))
    }
}
