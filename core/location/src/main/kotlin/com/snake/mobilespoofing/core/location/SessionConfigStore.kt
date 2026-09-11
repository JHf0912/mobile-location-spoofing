package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.geo.MovementSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the spec the next session should run.
 *
 * Exists because a route is a list of coordinates and an `Intent` extra is the wrong shape for
 * that: it would mean `Parcelable` (and therefore the Android plugin in a pure-Kotlin module),
 * `Serializable`, or a hand-rolled JSON blob. The service and the UI already share a process, so a
 * singleton is simpler and keeps validation in the UI where a wrong value can be explained to the
 * user rather than failing inside a service.
 *
 * It also gives the null-intent restart case a defined answer: the store is empty after a process
 * restart, so the service shuts down instead of resuming something it cannot reconstruct.
 */
@Singleton
class SessionConfigStore @Inject constructor() {

    private val _pending = MutableStateFlow<MovementSpec?>(null)
    val pending: StateFlow<MovementSpec?> = _pending.asStateFlow()

    fun set(spec: MovementSpec) {
        _pending.value = spec
    }

    fun clear() {
        _pending.value = null
    }
}
