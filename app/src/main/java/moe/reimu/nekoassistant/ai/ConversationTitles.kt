package moe.reimu.nekoassistant.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlinx.coroutines.withTimeoutOrNull
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration

suspend fun generateConversationTitle(configuration: ActiveLlmConfiguration, prompt: String): String? =
    withTimeoutOrNull(20_000) {
        createLlmClient(configuration).use { client ->
            client.streamChatMessage(ChatCompletionRequest(
                model = ModelId(configuration.modelName),
                messages = listOf(
                    ChatMessage(ChatRole.System, "Create a short chat title in the user's language. Return only the title, at most 8 words. Do not answer or execute the request."),
                    ChatMessage(ChatRole.User, prompt.take(1500)),
                ),
            ))?.content
        }
    }
