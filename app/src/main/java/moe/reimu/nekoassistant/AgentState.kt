package moe.reimu.nekoassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.reimu.nekoassistant.ai.AgentMessage
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.InferenceStatus

data class ShizukuStatus(val granted: Boolean = false, val available: Boolean = false)
data class AvailableApp(val packageName: String, val label: String)

/** Durable UI state owned by AgentService, including while no screen is observing it. */
data class AgentState(
    val shizukuStatus: ShizukuStatus = ShizukuStatus(),
    val isUserServiceConnected: Boolean = false,
    val isAgentRunning: Boolean = false,
    val hasDisplay: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val inferenceStatuses: Map<String, InferenceStatus> = emptyMap(),
    val errorMessage: String? = null,
    val takeOverMessage: String? = null,
    val availableApp: AvailableApp? = null,
)

/** Atomic transitions keep worker updates from overwriting each other or UI commands. */
internal class AgentStateStore {
    private val mutableState = MutableStateFlow(AgentState())
    val state = mutableState.asStateFlow()
    val hasSubscribers: Boolean get() = mutableState.subscriptionCount.value > 0

    fun update(transform: (AgentState) -> AgentState) = mutableState.update(transform)

    fun startTask() = update {
        it.copy(
            isAgentRunning = true,
            errorMessage = null,
            inferenceStatuses = emptyMap(),
            takeOverMessage = null,
            availableApp = null,
        )
    }

    fun finishTask(app: AvailableApp?) = update {
        it.copy(isAgentRunning = false, takeOverMessage = null, availableApp = app)
    }

    fun newChat() = update {
        it.copy(messages = emptyList(), errorMessage = null)
    }

    fun addUserMessage(prompt: String) {
        val message = ChatMessage("You", prompt, System.currentTimeMillis(), fromUser = true)
        update { it.copy(messages = it.messages + message) }
    }

    fun updateAgentMessage(message: AgentMessage) {
        val timestamp = System.currentTimeMillis()
        update { state ->
            val last = state.messages.lastOrNull()
            val streaming = last != null && !last.fromUser && last.sender == message.agent
            val next = ChatMessage(message.agent, message.content, if (streaming) last.timestamp else timestamp)
            state.copy(messages = if (streaming) state.messages.dropLast(1) + next else state.messages + next)
        }
    }

    fun updateInferenceStatus(agent: String, status: InferenceStatus) = update {
        it.copy(inferenceStatuses = it.inferenceStatuses + (agent to status))
    }
}
