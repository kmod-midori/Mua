package moe.reimu.nekoassistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.reimu.nekoassistant.ai.AgentMessage
import moe.reimu.nekoassistant.ai.InferenceStatus
import org.junit.Assert.*
import org.junit.Test

class AgentStateStoreTest {
    private val app = AvailableApp("example.app", "Example")

    @Test
    fun lateSubscriberReceivesErrorAndHandoffTogether() = runBlocking {
        val store = AgentStateStore()
        store.startTask()
        store.update { it.copy(hasDisplay = true, errorMessage = "Network failed") }
        store.finishTask(app)

        val restored = store.state.first()
        assertFalse(restored.isAgentRunning)
        assertTrue(restored.hasDisplay)
        assertEquals("Network failed", restored.errorMessage)
        assertEquals(app, restored.availableApp)
    }

    @Test
    fun startingNextTaskClearsPreviousRunStateWithoutLosingDisplayOrHistory() {
        val store = AgentStateStore()
        store.addUserMessage("Previous task")
        store.update {
            it.copy(hasDisplay = true, availableApp = app, errorMessage = "Old error",
                takeOverMessage = "Old request",
                toolApproval = ToolApprovalRequest("old", ToolApprovalDetails("Run?", "old command")),
                inferenceStatuses = mapOf("Planner" to InferenceStatus.ERROR))
        }
        store.startTask()

        val state = store.state.value
        assertTrue(state.isAgentRunning)
        assertTrue(state.hasDisplay)
        assertEquals(1, state.messages.size)
        assertNull(state.errorMessage)
        assertNull(state.takeOverMessage)
        assertNull(state.toolApproval)
        assertNull(state.availableApp)
        assertTrue(state.inferenceStatuses.isEmpty())
    }

    @Test
    fun stoppingDuringTakeoverClearsRequestAndKeepsAvailableApp() {
        val store = AgentStateStore()
        store.startTask()
        store.update { it.copy(hasDisplay = true, takeOverMessage = "Please sign in") }
        store.finishTask(app)

        assertFalse(store.state.value.isAgentRunning)
        assertNull(store.state.value.takeOverMessage)
        assertEquals(app, store.state.value.availableApp)
        assertTrue(store.state.value.hasDisplay)
    }

    @Test
    fun streamedReplyReplacesOnlyTheLatestTurnAndPreservesItsTimestamp() {
        val store = AgentStateStore()
        store.addUserMessage("Question")
        store.updateAgentMessage(AgentMessage("Planner", "First chunk"))
        val first = store.state.value.messages.last()
        store.updateAgentMessage(AgentMessage("Planner", "Full reply"))
        assertEquals(2, store.state.value.messages.size)
        assertEquals(first.timestamp, store.state.value.messages.last().timestamp)
        assertEquals("Full reply", store.state.value.messages.last().text)
        store.updateAgentMessage(AgentMessage("Executor", "Action"))
        assertEquals(3, store.state.value.messages.size)
        assertEquals("Full reply", store.state.value.messages[1].text)
    }

    @Test
    fun concurrentTranscriptAndStatusUpdatesDoNotOverwriteEachOther() {
        val store = AgentStateStore()
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..2).map { worker ->
                executor.submit {
                    start.await()
                    repeat(100) { index ->
                        when (worker) {
                            0 -> store.addUserMessage("Message $index")
                            1 -> store.updateInferenceStatus("Agent $index", InferenceStatus.ERROR)
                            else -> store.update { it.copy(errorMessage = "Error $index") }
                        }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(100, store.state.value.messages.size)
            assertEquals(100, store.state.value.inferenceStatuses.size)
            assertEquals("Error 99", store.state.value.errorMessage)
        } finally {
            executor.shutdownNow()
        }
    }
}
