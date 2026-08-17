package dev.repertaurus.data

/**
 * What the app booted into (schema-compatibility decision S9).
 *
 * S8 is the rule this type exists to keep: **the app must not hard-crash on any database state
 * — corrupt, stale, future, absent or unreadable, in alpha as much as in release.** A crash on
 * the boot path is uniquely bad because it removes the only route to recovery: the import UI
 * lives inside the app, so a database the app cannot read makes the app unfixable from the
 * device. Start-up therefore resolves to one of these two rather than throwing.
 */
public sealed interface DatabaseState {

    /** Open, on the current schema, every table and column this build needs present. */
    public data object Ready : DatabaseState

    /**
     * The database could not be opened, or could be opened and is not one this build can use.
     *
     * S11: this is never silent. It carries [reason] so the recovery screen can say what is
     * wrong in plain language, and the recovery screen carries actions — a bare
     * `runCatching {}.getOrDefault(emptyList())` that turned a schema error into an empty list
     * would be a worse outcome than the crash, because it presents an intact repertoire as
     * empty and invites the user to "fix" it by adding songs.
     *
     * @param verdict the compatibility verdict, or null when the file could not be read far
     *   enough to reach one — a corrupt file, a locked file, an I/O failure.
     * @param reason one sentence, addressed to the user, naming what is wrong and what to do.
     * @param file the absolute path of the file this is about, so a support conversation has
     *   something concrete in it.
     */
    public data class Unloadable(
        val verdict: SchemaVerdict?,
        val reason: String,
        val file: String,
    ) : DatabaseState {

        public companion object {

            /**
             * The one phrasing of "something threw while we were opening this".
             *
             * Every layer that can catch such a failure builds its state here, so the user is not
             * told the file "could not be opened" or "could not be read" depending on which layer
             * happened to catch it — the same discipline `SchemaCompatibility.explain` applies to
             * verdicts (S6).
             */
            public fun from(file: String, failure: Throwable): Unloadable = Unloadable(
                verdict = null,
                reason = "That database could not be opened: " +
                    (failure.message?.takeIf { it.isNotBlank() } ?: describe(failure)),
                file = file,
            )

            /** Some SQLite failures arrive with a null message; the type is better than nothing. */
            private fun describe(failure: Throwable): String =
                failure::class.simpleName ?: "unknown error"
        }
    }
}

/**
 * Thrown by the accessors that cannot express "unloadable" in their return type, so a caller
 * that skipped [DatabaseState] still fails with the reason attached rather than with
 * `no such table`.
 *
 * Every start-up path catches this and renders the recovery screen; it is not a control-flow
 * shortcut and nothing may swallow it (S11).
 */
public class DatabaseUnloadable(
    public val state: DatabaseState.Unloadable,
) : Exception(state.reason)
