package moe.reimu.nekoassistant

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ToolApprovalGateTest {
    @Test
    fun onlyMatchingExplicitApprovalResumesRequest() = runBlocking {
        var shown: ToolApprovalRequest? = null
        val gate = ToolApprovalGate { shown = it }
        val waiting = async { gate.request(ToolApprovalDetails("Run?", "exact command")) }
        yield()
        assertEquals("exact command", shown!!.details.details)
        gate.respond("stale-id", true)
        assertFalse(waiting.isCompleted)
        gate.respond(shown!!.id, true)
        assertTrue(waiting.await().approved)
        assertNull(shown)
    }

    @Test
    fun denialAndCancellationNeverAuthorizeNextRequest() = runBlocking {
        var shown: ToolApprovalRequest? = null
        val gate = ToolApprovalGate { shown = it }
        val denied = async { gate.request(ToolApprovalDetails("Run?", "first")) }
        yield()
        gate.respond(shown!!.id, false)
        assertFalse(denied.await().approved)
        val cancelled = async { gate.request(ToolApprovalDetails("Run?", "second")) }
        yield()
        val staleId = shown!!.id
        cancelled.cancelAndJoin()
        assertNull(shown)
        val next = async { gate.request(ToolApprovalDetails("Run?", "third")) }
        yield()
        gate.respond(staleId, true)
        assertFalse(next.isCompleted)
        gate.respond(shown!!.id, false)
        assertFalse(next.await().approved)
    }

    @Test
    fun rejectionCarriesOptionalGuidanceButApprovalDoesNot() = runBlocking {
        var shown: ToolApprovalRequest? = null
        val gate = ToolApprovalGate { shown = it }
        val denied = async { gate.request(ToolApprovalDetails("Run?", "command")) }
        yield()
        gate.respond(shown!!.id, false, "  Use the downloaded file instead.  ")
        assertEquals(
            ToolApprovalResponse(false, "Use the downloaded file instead."),
            denied.await(),
        )

        val empty = async { gate.request(ToolApprovalDetails("Run?", "command")) }
        yield()
        gate.respond(shown!!.id, false, "   ")
        assertEquals(ToolApprovalResponse(false), empty.await())

        val approved = async { gate.request(ToolApprovalDetails("Run?", "command")) }
        yield()
        gate.respond(shown!!.id, true, "Unsent draft")
        assertEquals(ToolApprovalResponse(true), approved.await())
    }
}
