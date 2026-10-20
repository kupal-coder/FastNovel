package com.lagradost.quicknovel.util

/**
 * A tiny event bus. Observers can subscribe and unsubscribe from any thread, and events may be
 * raised from background coroutines (the library sync does), so the observer set is guarded and
 * always iterated over a snapshot.
 */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke(value: T) {
        val snapshot = synchronized(observers) { observers.toList() }
        for (observer in snapshot)
            observer(value)
    }
}
