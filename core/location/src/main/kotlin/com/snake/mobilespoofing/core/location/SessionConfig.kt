package com.snake.mobilespoofing.core.location

data class SessionConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    init {
        // Roughly 1 Hz is what real GPS delivers and is where the ecosystem has settled. Faster
        // than this and Google's fused client starts dropping pushes and failing its Task, which
        // is a per-push warning rather than an error but produces an uneven track.
        require(intervalMs >= MIN_INTERVAL_MS) {
            "intervalMs must be at least $MIN_INTERVAL_MS, got $intervalMs"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        const val MIN_INTERVAL_MS = 250L
    }
}
