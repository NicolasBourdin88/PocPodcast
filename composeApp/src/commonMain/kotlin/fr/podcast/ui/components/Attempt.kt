package fr.podcast.ui.components

import kotlinx.coroutines.CancellationException

/** Runs [block] for the UI: failures become a [Result] to display, cancellations still propagate. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }
