package dev.averyn.domain

/** Tracking lifecycle. Transitions are explicit and enumerated; see TDD-0001 §5.2. */
enum class ActivityState {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    STOPPING,
    COMPLETED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED

    fun canTransitionTo(next: ActivityState): Boolean = next in allowed.getValue(this)

    /** Returns [next] if the transition is legal, otherwise throws [IllegalStateException]. */
    fun transitionTo(next: ActivityState): ActivityState {
        check(canTransitionTo(next)) { "Illegal activity transition: $this -> $next" }
        return next
    }

    private companion object {
        val allowed: Map<ActivityState, Set<ActivityState>> =
            mapOf(
                IDLE to setOf(PREPARING),
                PREPARING to setOf(RECORDING, IDLE, FAILED),
                RECORDING to setOf(PAUSED, STOPPING, FAILED),
                PAUSED to setOf(RECORDING, STOPPING, FAILED),
                STOPPING to setOf(COMPLETED, FAILED),
                COMPLETED to emptySet(),
                FAILED to emptySet(),
            )
    }
}
