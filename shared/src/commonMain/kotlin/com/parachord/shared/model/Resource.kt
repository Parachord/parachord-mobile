package com.parachord.shared.model

import kotlinx.coroutines.flow.Flow

/**
 * Generic wrapper for async data loading states.
 */
sealed class Resource<out T> {
    object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
}

/**
 * What a screen shows when [next] arrives while it shows [current].
 *
 * The repositories emit their cached list first, then an error if the refresh
 * fails. A failed refresh must not replace a list the user is already looking
 * at with the error screen, so a list stays and the error shows only when
 * there's nothing to show. Both platforms apply this: Android's view models,
 * and iOS through `IosContainer` ([settle] and its flows).
 *
 * When no service has anything to give, a repository may emit an empty list
 * and then the reason. The empty list replaces what was shown (it may be
 * stale), so the reason that follows it is shown, not swallowed.
 */
fun <T> keepDataOnError(current: Resource<T>, next: Resource<T>): Resource<T> =
    if (next is Resource.Error && current is Resource.Success && !current.isEmptyList()) current else next

private fun <T> Resource.Success<T>.isEmptyList(): Boolean = (data as? Collection<*>)?.isEmpty() == true

/**
 * Collects a repository flow to what a screen ends up showing: the last list,
 * or the error when there was nothing to show ([keepDataOnError]). [Resource.Loading]
 * is skipped; a flow that emits nothing settles to [Resource.Loading].
 */
suspend fun <T> Flow<Resource<T>>.settle(): Resource<T> {
    var shown: Resource<T> = Resource.Loading
    collect { next -> if (next !is Resource.Loading) shown = keepDataOnError(shown, next) }
    return shown
}
