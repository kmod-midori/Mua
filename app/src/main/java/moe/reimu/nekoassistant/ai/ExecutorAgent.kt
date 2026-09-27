package moe.reimu.nekoassistant.ai

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import moe.reimu.nekoassistant.ai.tool.Action
import moe.reimu.nekoassistant.ai.tool.ToolRegistry
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.getSystemPrompt
import java.time.LocalDate

class ExecutorAgent(
    private val screenshotWidth: Int,
    private val screenshotHeight: Int,
    private val configuration: ActiveLlmConfiguration,
    private val client: OpenAI,
    private val isTermuxAvailable: () -> Boolean = { false },
    private val onStatus: (InferenceStatus) -> Unit = {},
    private val onStream: (String) -> Unit = {},
) {
    suspend fun execute(
        plan: String,
        screenshotDataUri: String?,
        currentAppName: String?
    ): AgentResponse? {
        val termuxAvailable = isTermuxAvailable()
        val textPrompt = buildString {
            if (!currentAppName.isNullOrBlank()) {
                appendLine("当前前台应用：$currentAppName")
            }
            if (screenshotDataUri != null) {
                appendLine("屏幕右下角坐标：(${screenshotWidth - 1},${screenshotHeight - 1})")
            }
            appendLine("## 任务计划")
            append(plan)
        }
        val messageContent = mutableListOf<ContentPart>(
            TextPart(textPrompt)
        )
        if (screenshotDataUri != null) {
            messageContent.add(ImagePart(screenshotDataUri))
        }

        val chatRequest = ChatCompletionRequest(
            model = ModelId(configuration.modelName),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.System,
                    content = getSystemPrompt(
                        LocalDate.now().toString(),
                        screenshotWidth - 1, screenshotHeight - 1, termuxAvailable
                    )
                ),
                ChatMessage(role = ChatRole.User, content = messageContent)
            ),
            tools = ToolRegistry.definitions(termuxAvailable),
        )

        val streamedText = StringBuilder()
        val message = client.streamChatMessage(
            request = chatRequest,
            onChunk = { chunk ->
                chunk.choices.forEach { choice ->
                    choice.delta?.content?.let { delta ->
                        Log.d(TAG, "Executor stream: $delta")
                        streamedText.append(delta)
                        onStream(streamedText.toString())
                    }
                    choice.delta?.toolCalls?.forEach { toolCall ->
                        toolCall.function?.argumentsOrNull?.let { Log.d(TAG, "Executor tool args: $it") }
                    }
                }
            },
            onStatus = onStatus,
        ) ?: return null

        val toolCall = message.toolCalls?.firstOrNull() as? ToolCall.Function ?: return null
        val action = toAction(toolCall.function, termuxAvailable && isTermuxAvailable()) ?: return null
        val think = (message.messageContent as? TextContent)?.content.orEmpty()
        return AgentResponse(think, action)
    }

    private fun toAction(call: FunctionCall, termuxAvailable: Boolean): Action? = try {
        ToolRegistry.decode(call.name, call.arguments, termuxAvailable)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode tool call ${call.name}", e)
        null
    }

    companion object {
        private const val TAG = "ExecutorAgent"
    }
}
