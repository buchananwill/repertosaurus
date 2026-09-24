package dev.repertosaurus.habit

/** scorecards SC6: a day's shade by its live events: 0, 1, 2-3, 4-7 and 8+. Provisional; tuned in P13a. */
public enum class HabitBucket {
    NONE,
    ONE,
    FEW,
    SEVERAL,
    MANY,
    ;

    public companion object {
        public fun of(count: Long): HabitBucket = when {
            count <= 0L -> NONE
            count == 1L -> ONE
            count <= 3L -> FEW
            count <= 7L -> SEVERAL
            else -> MANY
        }
    }
}
