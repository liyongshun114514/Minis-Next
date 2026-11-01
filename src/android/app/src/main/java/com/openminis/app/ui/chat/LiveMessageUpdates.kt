package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Retry a targeted immutable-list patch against the latest list, so an IO
 * completion cannot replace a simultaneous Main-thread user-message append.
 * [transform] must be pure: StateFlow may retry it after a failed CAS.
 */
internal fun <T> MutableStateFlow<List<T>>.updateItems(transform: (T) -> T) {
    update { current -> current.map(transform) }
}
