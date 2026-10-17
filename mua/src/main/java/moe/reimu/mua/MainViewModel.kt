package moe.reimu.mua

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import rikka.shizuku.Shizuku

/** Screen state survives activity recreation; only the application context is retained. */
internal class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val handler = Handler(Looper.getMainLooper())
    var localEnabled by mutableStateOf(true)
        private set
    var localStatus by mutableStateOf(UiText(R.string.stopped))
        private set
    var listenAddress by mutableStateOf("127.0.0.1")
        private set
    var portText by mutableStateOf("8765")
        private set
    var networkAddresses by mutableStateOf(emptyList<String>())
        private set
    val validPort: Int? get() = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
    private fun saveListenerIfValid() {
        validPort?.let { app.saveListenerConfig(ListenerConfig(listenAddress, it, localEnabled)) }
    }
    var remoteConfig by mutableStateOf(RemoteConfig())
        private set
    var remoteStatus by mutableStateOf(UiText(R.string.stopped))
        private set
    val remoteError: UiText? get() = remoteConfig.validationError()?.let { UiText(it.resource) }
    fun updateRemote(config: RemoteConfig) {
        if (running || McpService.running) return
        remoteConfig = config
        app.saveRemoteConfig(config)
    }
    var running by mutableStateOf(false)
        private set
    var displayPowerStatus by mutableStateOf(DisplayPowerStatus())
        private set
    var status by mutableStateOf(UiText(R.string.stopped))
        private set
    var batteryExempt by mutableStateOf(false)
        private set
    var shizukuAvailable by mutableStateOf(false)
        private set
    var shizukuGranted by mutableStateOf(false)
        private set
    var previews by mutableStateOf(emptyList<DisplayPreview>())
        private set
    private var previewGeneration = 0
    private var previewVisible = false
    private val refreshPreviews = object : Runnable {
        override fun run() {
            if (!previewVisible) return
            val generation = previewGeneration
            McpService.requestPreviews { frames ->
                if (previewVisible && generation == previewGeneration) {
                    previews = frames
                    // At most one request in flight; never queue behind slow MCP operations.
                    handler.postDelayed(this, 200)
                }
            }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (!previewVisible) return
            networkAddresses = deviceNetworkAddresses()
            shizukuAvailable = Shizuku.pingBinder()
            // The binder can disappear between checking availability and permission.
            shizukuGranted = shizukuAvailable && runCatching {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            running = McpService.running
            localStatus = McpService.localStatus
            remoteStatus = McpService.remoteStatus
            displayPowerStatus = McpService.displayPowerStatus
            status = if (running) McpService.status else
                McpService.status.takeIf { it.resource == R.string.server_failed } ?: UiText(R.string.stopped)
            batteryExempt = app.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(app.packageName)
            handler.postDelayed(this, 1000)
        }
    }

    var authorization by mutableStateOf("Bearer ${McpService.accessToken(app)}")
        private set

    init {
        remoteConfig = app.loadRemoteConfig()
        val listener = app.loadListenerConfig()
        localEnabled = listener.enabled
        listenAddress = listener.address
        portText = listener.port.toString()
    }

    fun updateAddress(address: String) {
        if (running || McpService.running) return
        listenAddress = address
        saveListenerIfValid()
    }

    fun updatePort(value: String) {
        if (running || McpService.running) return
        portText = value
        saveListenerIfValid()
    }

    fun updateLocalEnabled(enabled: Boolean) {
        if (running || McpService.running) return
        localEnabled = enabled
        // Save the switch even while a port edit is incomplete.
        app.saveListenerConfig(app.loadListenerConfig().copy(enabled = enabled))
        saveListenerIfValid()
    }

    fun updateRemoteEnabled(enabled: Boolean) {
        updateRemote(remoteConfig.copy(enabled = enabled))
    }

    fun start() {
        saveListenerIfValid()
        app.startForegroundService(Intent(app, McpService::class.java))
        running = true
    }

    fun stop() {
        app.stopService(Intent(app, McpService::class.java))
    }

    fun resetAuthorization() {
        authorization = "Bearer ${McpService.resetAccessToken(app)}"
    }

    fun startObserving() {
        if (previewVisible) return
        previewVisible = true
        refresh.run()
        refreshPreviews.run()
    }

    fun stopObserving() {
        previewVisible = false
        previewGeneration++
        handler.removeCallbacks(refreshPreviews)
        handler.removeCallbacks(refresh)
        previews = emptyList()
    }

    override fun onCleared() {
        stopObserving()
    }
}
