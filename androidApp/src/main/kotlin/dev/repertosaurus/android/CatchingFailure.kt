package dev.repertosaurus.android

import kotlinx.coroutines.CancellationException

/**
 * [block]'s result, or its failure. Not `runCatching`: **a cancelled read must stay cancelled**, never land
 * as a failure that is then reported or applied.
 */
internal suspend inline fun <T> catchingFailure(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
