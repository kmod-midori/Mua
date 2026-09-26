package moe.reimu.nekoassistant

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.data.ConversationEntity
import moe.reimu.nekoassistant.data.ConversationRepository
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.nekoassistant.data.LlmProviderWithModels

data class UiState(
    val isServiceConnected: Boolean = false,
    val agent: AgentState = AgentState(),
    val agentPrompt: String = "",
    val llmProviders: List<LlmProviderWithModels> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
    val selectedConversationId: String? = null,
    val activeConversationId: String? = null,
    val isChatOperationPending: Boolean = false,
    val localError: String? = null,
) {
    val hasActiveLlmConfiguration: Boolean
        get() = llmProviders.any { provider -> provider.models.any { it.isSelected } }
    val isReadOnly: Boolean get() = activeConversationId != null && activeConversationId != selectedConversationId
    val title: String get() = conversations.find { it.id == selectedConversationId }?.title ?: "New chat"
}

private data class SelectedChat(val id: String?, val messages: List<ChatMessage> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val service = MutableStateFlow<AgentService?>(null)
    private val prompt = MutableStateFlow("")
    private val chatOperationPending = MutableStateFlow(false)
    private val localError = MutableStateFlow<String?>(null)
    private val repository = LlmConfigurationRepository(application)
    private val conversations = ConversationRepository(application)
    // null means first launch, empty means an explicitly selected new-chat draft.
    private val selectedId = savedState.getStateFlow<String?>("conversationId", null)
    private var bindingRegistered = false
    private var cleared = false
    private var chatVisible = false

    private val connectedState = service.flatMapLatest { connected ->
        connected?.state?.map { true to it } ?: flowOf(false to AgentState())
    }
    private val selectedChat = selectedId.flatMapLatest { id ->
        if (id.isNullOrEmpty()) flowOf(SelectedChat(null)) else
            conversations.observeMessages(id).map { rows -> SelectedChat(id, rows.map { it.toMessage() }) }
    }
    private val localState = combine(prompt, chatOperationPending, localError) { text, pending, error -> Triple(text, pending, error) }

    val uiState = combine(connectedState, selectedChat, localState,
        repository.observeProviders(), conversations.observeConversations()) { connected, selected, local, providers, chats ->
        val live = selected.id != null && selected.id == connected.second.conversationId
        val persisted = chats.find { it.id == selected.id }
        val agent = if (live) connected.second else connected.second.copy(
            conversationId = selected.id, isAgentRunning = false, hasDisplay = false, messages = selected.messages,
            inferenceStatuses = emptyMap(),
            errorMessage = if (selected.id == null && connected.second.conversationId == null)
                connected.second.errorMessage else persisted?.errorMessage,
            takeOverMessage = null, availableApp = null,
        )
        UiState(connected.first, agent, local.first, providers, chats, selected.id,
            connected.second.conversationId.takeIf { connected.second.isAgentRunning }
                ?: chats.firstOrNull { it.status == "running" || it.status == "paused" }?.id,
            local.second, local.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    val previewFrames: Flow<Bitmap?> = service.flatMapLatest { it?.previewFrames ?: flowOf(null) }

    private fun syncVisibility() {
        val connected = service.value ?: return
        connected.setChatVisible(chatVisible && selectedId.value == connected.state.value.conversationId)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (cleared) return
            service.value?.setChatVisible(false)
            service.value = (binder as AgentService.LocalBinder).getService()
            syncVisibility()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service.value?.setChatVisible(false)
            service.value = null
        }
        override fun onBindingDied(name: ComponentName) { releaseBinding(); bindService() }
        override fun onNullBinding(name: ComponentName) { releaseBinding() }
    }

    init {
        bindService()
        viewModelScope.launch {
            if (selectedId.value == null) {
                val latest = conversations.latest()
                if (selectedId.value == null) savedState["conversationId"] = latest?.id ?: ""
            }
        }
        viewModelScope.launch {
            combine(connectedState.map { it.second.conversationId }.distinctUntilChanged(), selectedId) { _, _ -> Unit }
                .collect { syncVisibility() }
        }
    }

    private fun bindService() {
        if (cleared || bindingRegistered) return
        bindingRegistered = application.bindService(
            Intent(application, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE,
        )
    }
    private fun releaseBinding() {
        service.value?.setChatVisible(false)
        service.value = null
        if (bindingRegistered) {
            bindingRegistered = false
            application.unbindService(serviceConnection)
        }
    }
    fun setChatVisible(visible: Boolean) { chatVisible = visible; syncVisibility() }
    fun updateAgentPrompt(value: String) { if (!uiState.value.isReadOnly) prompt.value = value }

    fun selectConversation(id: String) {
        if (chatOperationPending.value) return
        savedState["conversationId"] = id
        prompt.value = ""
        localError.value = null
        syncVisibility()
    }
    fun deleteConversation(id: String) {
        if (chatOperationPending.value) return
        val connected = service.value
        if (connected?.state?.value?.let { it.conversationId == id && it.isAgentRunning } == true) return
        chatOperationPending.value = true
        viewModelScope.launch {
            try {
                conversations.delete(id)
                connected?.forgetConversation(id)
                if (selectedId.value == id) {
                    savedState["conversationId"] = conversations.latest()?.id ?: ""
                    prompt.value = ""
                    syncVisibility()
                }
                localError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError.value = e.message ?: "Could not delete chat"
            } finally {
                chatOperationPending.value = false
            }
        }
    }

    fun newChat() {
        if (service.value?.state?.value?.isAgentRunning == true || chatOperationPending.value) return
        selectConversation("")
    }
    fun startAgent() {
        val text = prompt.value.trim()
        val connected = service.value ?: return
        if (text.isEmpty() || chatOperationPending.value || connected.state.value.isAgentRunning) return
        chatOperationPending.value = true
        viewModelScope.launch {
            try {
                val id = selectedId.value?.takeIf { it.isNotEmpty() } ?: conversations.create(text)
                savedState["conversationId"] = id
                application.startService(Intent(application, AgentService::class.java).apply {
                    putExtra(AgentService.EXTRA_AGENT_PROMPT, text)
                    putExtra(AgentService.EXTRA_CONVERSATION_ID, id)
                })
                prompt.value = ""
                localError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError.value = e.message ?: "Could not start conversation"
            } finally {
                chatOperationPending.value = false
            }
        }
    }

    private fun selectedService(): AgentService? = service.value?.takeIf {
        it.state.value.conversationId == selectedId.value
    }
    fun stopAgent() { selectedService()?.stopAgent() }
    fun resumeAgent() { selectedService()?.resumeAgent() }
    fun bringAppToFront(context: Context) { selectedService()?.bringAppToFront(context) }
    fun closeRetainedDisplay() { selectedService()?.closeRetainedDisplay() }
    fun requestShizukuPermission() { service.value?.requestShizukuPermission() }

    override fun onCleared() { cleared = true; releaseBinding(); super.onCleared() }
}
