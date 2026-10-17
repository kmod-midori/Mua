package moe.reimu.mua

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var listenAddress by mutableStateOf("127.0.0.1")
    private var portText by mutableStateOf("8765")
    private var networkAddresses by mutableStateOf(emptyList<String>())
    private val validPort: Int? get() = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
    private fun saveListenerIfValid() {
        validPort?.let { saveListenerConfig(ListenerConfig(listenAddress, it)) }
    }
    private var running by mutableStateOf(false)
    private var displayPowerStatus by mutableStateOf(DisplayPowerStatus())
    private var status by mutableStateOf("Stopped")
    private var batteryExempt by mutableStateOf(false)
    private var shizukuAvailable by mutableStateOf(false)
    private var shizukuGranted by mutableStateOf(false)
    private var previews by mutableStateOf(emptyList<DisplayPreview>())
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
            networkAddresses = deviceNetworkAddresses()
            shizukuAvailable = Shizuku.pingBinder()
            // The binder can disappear between checking availability and permission.
            shizukuGranted = shizukuAvailable && runCatching {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            running = McpService.running
            displayPowerStatus = McpService.displayPowerStatus
            status = if (running) McpService.status else
                McpService.status.takeIf { it.startsWith("Server failed:") } ?: "Stopped"
            batteryExempt = getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listener = loadListenerConfig()
        listenAddress = listener.address
        portText = listener.port.toString()
        enableEdgeToEdge()
        setContent {
            // Same Material 3 dynamic palette, typography and default shapes as
            // NekoAssistant. Both apps require Android 12+, where dynamic color exists.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme())
                dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                MainPage()
            }
        }
    }

    private fun perform(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainPage() {
        Scaffold(topBar = { TopAppBar(title = { Text("mua") }) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Mobile-Using Agent", style = MaterialTheme.typography.headlineSmall)
                        Text("Control a Shizuku background display from an MCP client.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!shizukuAvailable || !shizukuGranted) item {
                    Card(
                        onClick = { perform {
                            check(Shizuku.pingBinder()) { "Start Shizuku first" }
                            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                Shizuku.requestPermission(1)
                        } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (shizukuAvailable) "Shizuku not granted" else "Shizuku unavailable",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Shizuku permission required for virtual displays")
                        }
                    }
                }
                if (previews.isNotEmpty()) item {
                    SectionCard("Virtual displays") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(previews, key = { it.displayId }) { preview ->
                                Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Display ${preview.displayId}", style = MaterialTheme.typography.titleMedium)
                                    Box(Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height)
                                        .background(Color.Black), contentAlignment = Alignment.Center) {
                                        val bitmap = preview.bitmap
                                        if (bitmap == null) Text("Waiting for frame", color = Color.White,
                                            style = MaterialTheme.typography.bodySmall)
                                        else Image(bitmap.asImageBitmap(), "Live preview of display ${preview.displayId}",
                                            modifier = Modifier.fillMaxSize())
                                    }
                                    Text("${preview.width} × ${preview.height}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard("MCP server") {
                        Text(if (running) "Listening on $listenAddress:$portText" else "Server stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text(displayPowerStatus.displayLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(displayPowerStatus.cpuLabel, style = MaterialTheme.typography.bodyMedium,
                            color = if (displayPowerStatus.cpuKeptAwake) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Listening address", style = MaterialTheme.typography.labelLarge)
                        ListenerConfig.ADDRESSES.forEach { address ->
                            Row(
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = listenAddress == address,
                                    enabled = !running,
                                    role = Role.RadioButton,
                                    onClick = { listenAddress = address; saveListenerIfValid() },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = listenAddress == address, onClick = null,
                                    enabled = !running, modifier = Modifier.padding(12.dp))
                                Text(if (address == "127.0.0.1") "$address · This device" else "$address · All interfaces")
                            }
                        }
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it; saveListenerIfValid() },
                            label = { Text("Port") },
                            supportingText = { Text(if (running) "Stop mua to change the address or port" else "1024–65535") },
                            isError = validPort == null,
                            enabled = !running,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (listenAddress == "0.0.0.0") {
                            Text("Accepts connections on the device’s network interfaces. Clients need the bearer token; HTTP traffic is unencrypted.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            enabled = running || validPort != null,
                            onClick = { perform {
                                if (running) stopService(Intent(this@MainActivity, McpService::class.java))
                                else {
                                    saveListenerIfValid()
                                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                                    startForegroundService(Intent(this@MainActivity, McpService::class.java))
                                }
                            } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (running) "Stop mua" else "Start mua") }
                    }
                }
                item {
                    SectionCard("Client connection") {
                        val endpointHosts = if (listenAddress == "0.0.0.0") networkAddresses else listOf("127.0.0.1")
                        val port = validPort
                        if (port == null) {
                            Text("Enter a valid port to configure the endpoint", style = MaterialTheme.typography.bodyLarge)
                        } else if (endpointHosts.isEmpty()) {
                            Text("No network IPv4 address available. Connect to a network to get an endpoint URL.",
                                style = MaterialTheme.typography.bodyLarge)
                        } else endpointHosts.forEach { host ->
                            val endpoint = "http://$host:$port/mcp"
                            CopyRow(endpoint, "Copy endpoint URL: $endpoint") { perform {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("mua endpoint", endpoint))
                                Toast.makeText(this@MainActivity, "Endpoint URL copied", Toast.LENGTH_SHORT).show()
                            } }
                        }
                        Text(if (listenAddress == "0.0.0.0")
                            "Streamable HTTP · Use an address reachable from your client. Restart mua after changing networks."
                            else "Streamable HTTP · On-device clients only", style = MaterialTheme.typography.bodyMedium)
                        var authorization by remember { mutableStateOf("Bearer ${McpService.accessToken(this@MainActivity)}") }
                        Text("Authorization", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(authorization, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            IconButton(onClick = { perform {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("mua authorization", authorization).apply {
                                        if (Build.VERSION.SDK_INT >= 33) description.extras = android.os.PersistableBundle().apply {
                                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                                        }
                                    })
                                Toast.makeText(this@MainActivity, "Authorization header copied", Toast.LENGTH_SHORT).show()
                            } }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy Authorization header") }
                            IconButton(onClick = { perform {
                                authorization = "Bearer ${McpService.resetAccessToken(this@MainActivity)}"
                                Toast.makeText(this@MainActivity, "Authorization reset. Update your clients with the new header.", Toast.LENGTH_LONG).show()
                            } }) { Icon(Icons.Default.Refresh, contentDescription = "Reset Authorization header") }
                        }
                        Text("Reset replaces the token immediately. Update connected clients with the new header.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("The token grants control of all managed virtual displays. Select a display by its ID.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!batteryExempt) item {
                    SectionCard("Screen-off operation") {
                        Text("Battery optimization enabled",
                            style = MaterialTheme.typography.titleMedium)
                        Text("mua keeps the CPU awake only while any virtual display is active, without turning on the screen. Stop the display when finished to save battery.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("For long screen-off sessions, show all apps in battery settings and allow mua to run without optimization.",
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { perform {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } }) { Text("Battery optimization settings") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.run()
        previewVisible = true
        refreshPreviews.run()
    }
    override fun onPause() {
        previewVisible = false
        previewGeneration++
        handler.removeCallbacks(refreshPreviews)
        previews = emptyList()
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun CopyRow(label: String, copyDescription: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = copyDescription)
        }
    }
}
