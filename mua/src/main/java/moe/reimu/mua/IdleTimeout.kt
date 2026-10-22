package moe.reimu.mua

/** Monotonic inactivity clock shared by transport threads and the service main thread. */
internal class IdleTimeout(private val now: () -> Long) {
    private var stopping = false
    private var manuallyStarted = false
    var automatic = false
        private set
    @Synchronized fun start(fromFcm: Boolean) {
        if (!fromFcm) manuallyStarted = true
        automatic = !manuallyStarted
        touch()
    }
    private var lastCall = now()
    private var activeCalls = 0
    @Synchronized fun touch() { lastCall = now() }
    @Synchronized fun beginCall() {
        check(!stopping) { "Mua is stopping" }
        activeCalls++
        touch()
    }
    @Synchronized fun endCall() { activeCalls--; touch() }
    @Synchronized fun remainingMillis(): Long =
        if (activeCalls > 0) TIMEOUT_MILLIS else (TIMEOUT_MILLIS - (now() - lastCall)).coerceAtLeast(0)
    @Synchronized fun expired() = automatic && remainingMillis() == 0L

    // Atomically retire the session so a transport cannot enqueue work between
    // checking the deadline and stopping the Android service.
    @Synchronized fun claimExpiration(): Boolean {
        if (!expired()) return false
        stopping = true
        return true
    }

    companion object { const val TIMEOUT_MILLIS = 10 * 60 * 1000L }
}
