package moe.reimu.nekoassistant

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.nekoassistant.data.LlmProviderWithModels

data class UiState(
    val isServiceConnected: Boolean = false,
    val agent: AgentState = AgentState(),
    val agentPrompt: String = "",
    val llmProviders: List<LlmProviderWithModels> = emptyList(),
) {
    val hasActiveLlmConfiguration: Boolean
        get() = llmProviders.any { provider -> provider.models.any { it.isSelected } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val service = MutableStateFlow<AgentService?>(null)
    private val prompt = MutableStateFlow("")
    private val repository = LlmConfigurationRepository(application)
    private var bindingRegistered = false

    // Switching service instances cancels the old subscription and replays the new snapshot.
    private val connectedState = service.flatMapLatest { connected ->
        connected?.state?.map { true to it } ?: flowOf(false to AgentState())
    }

    val uiState = combine(connectedState, prompt, repository.observeProviders()) { connected, text, providers ->
        UiState(connected.first, connected.second, text, providers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // Keep this cold: only a visible preview should subscribe to frame production.
    val previewFrames: Flow<Bitmap?> = service.flatMapLatest { connected ->
        connected?.previewFrames ?: flowOf(null)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.value = (binder as AgentService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service.value = null
        }

        override fun onBindingDied(name: ComponentName) {
            releaseBinding()
            bindService()
        }

        override fun onNullBinding(name: ComponentName) {
            releaseBinding()
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        bindingRegistered = application.bindService(
            Intent(application, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE,
        )
    }

    private fun releaseBinding() {
        service.value = null
        // Unbind even if the initial connection never arrived or subsequently disconnected.
        if (bindingRegistered) {
            application.unbindService(serviceConnection)
            bindingRegistered = false
        }
    }

    fun updateAgentPrompt(value: String) {
        prompt.value = value
    }

    fun startAgent() {
        val text = prompt.value.trim()
        if (text.isEmpty() || service.value == null || service.value?.state?.value?.isAgentRunning == true) return
        application.startService(Intent(application, AgentService::class.java).apply {
            putExtra(AgentService.EXTRA_AGENT_PROMPT, text)
        })
        prompt.value = ""
    }

    fun stopAgent() { service.value?.stopAgent() }
    fun resumeAgent() { service.value?.resumeAgent() }
    fun bringAppToFront(context: Context) { service.value?.bringAppToFront(context) }
    fun closeRetainedDisplay() { service.value?.closeRetainedDisplay() }
    fun newChat() { service.value?.newChat() }
    fun requestShizukuPermission() { service.value?.requestShizukuPermission() }

    override fun onCleared() {
        releaseBinding()
        super.onCleared()
    }
}
