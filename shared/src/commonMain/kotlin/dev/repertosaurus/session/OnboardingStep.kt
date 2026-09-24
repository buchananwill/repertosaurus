package dev.repertosaurus.session

/**
 * onboarding OB2, as amended by D66: the welcome, then the colour ramp, then who you are, in declaration
 * order. The rules for moving between steps are here so that every UI shares OB2's skip contract.
 *
 * `hasPerformers` is null until the performers have been read (journal F30): an empty list before that
 * is not "no performers".
 */
public enum class OnboardingStep {
    WELCOME,
    RAMP,
    PERFORMER,
    ;

    /**
     * The step after this one, or null when onboarding is finished. OB2: with no live performers the
     * ramp step finishes it; while that is not yet known, it goes on to the performer step, which
     * [skipsItself] then resolves.
     */
    public fun next(hasPerformers: Boolean?): OnboardingStep? = when (this) {
        WELCOME -> RAMP
        RAMP -> if (hasPerformers == false) null else PERFORMER
        PERFORMER -> null
    }

    /** Back walks one step back; from the welcome it leaves the app, as back does from the logger. */
    public fun previous(): OnboardingStep? = when (this) {
        WELCOME -> null
        RAMP -> WELCOME
        PERFORMER -> RAMP
    }

    /**
     * OB2, OB4 (journal F30 N1): the performer step, reached or restored onto before the performers were
     * read, finishes onboarding once they turn out to be none.
     */
    public fun skipsItself(hasPerformers: Boolean?): Boolean = this == PERFORMER && hasPerformers == false
}
