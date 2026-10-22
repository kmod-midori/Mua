package moe.reimu.mua

import org.junit.Assert.*
import org.junit.Test

class IdleTimeoutTest {
    private var now = 0L
    private val idle = IdleTimeout { now }.apply { start(fromFcm = true) }

    @Test fun manualStartNeverExpiresEvenAfterPush() {
        idle.start(fromFcm = false)
        idle.start(fromFcm = true)
        now += IdleTimeout.TIMEOUT_MILLIS * 2
        assertFalse(idle.automatic)
        assertFalse(idle.expired())
    }

    @Test fun shutdownRejectsCallsArrivingAfterExpirationWasClaimed() {
        now = IdleTimeout.TIMEOUT_MILLIS
        assertTrue(idle.claimExpiration())
        assertThrows(IllegalStateException::class.java) { idle.beginCall() }
    }

    @Test fun callArrivingBeforeShutdownPreventsExpiration() {
        now = IdleTimeout.TIMEOUT_MILLIS
        idle.beginCall()
        assertFalse(idle.claimExpiration())
        idle.endCall()
        assertFalse(idle.claimExpiration())
    }

    @Test fun stopsAfterTenMinutesWithoutCalls() {
        now = IdleTimeout.TIMEOUT_MILLIS - 1
        assertFalse(idle.expired())
        now++
        assertTrue(idle.expired())
    }

    @Test fun receivedCallRestartsDeadline() {
        now = IdleTimeout.TIMEOUT_MILLIS - 1
        idle.touch()
        now += IdleTimeout.TIMEOUT_MILLIS - 1
        assertFalse(idle.expired())
        now++
        assertTrue(idle.expired())
    }

    @Test fun inFlightAndQueuedCallsFinishBeforeIdleShutdown() {
        idle.beginCall()
        idle.beginCall()
        now += IdleTimeout.TIMEOUT_MILLIS * 2
        assertFalse(idle.expired())
        idle.endCall()
        now += IdleTimeout.TIMEOUT_MILLIS * 2
        assertFalse(idle.expired())
        idle.endCall()
        assertEquals(IdleTimeout.TIMEOUT_MILLIS, idle.remainingMillis())
        now += IdleTimeout.TIMEOUT_MILLIS
        assertTrue(idle.expired())
    }
}
