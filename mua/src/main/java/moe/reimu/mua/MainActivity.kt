package moe.reimu.mua

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    private fun MainPage() = with(viewModel) {
        Scaffold(topBar = { TopAppBar(title = { Text(getString(R.string.app_name)) }) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!shizukuAvailable || !shizukuGranted) item {
                    Card(
                        onClick = { perform {
                            check(Shizuku.pingBinder()) { getString(R.string.start_shizuku) }
                            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                Shizuku.requestPermission(1)
                        } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (shizukuAvailable) getString(R.string.shizuku_not_granted) else getString(R.string.shizuku_unavailable),
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (shizukuAvailable) getString(R.string.grant_access) else getString(R.string.start_shizuku))
                        }
                    }
                }
                if (previews.isNotEmpty()) item {
                    SectionCard(getString(R.string.virtual_displays)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(previews, key = { it.displayId }) { preview ->
                                Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(getString(R.string.display_title, preview.displayId), style = MaterialTheme.typography.titleMedium)
                                    Box(Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height)
                                        .background(Color.Black), contentAlignment = Alignment.Center) {
                                        val bitmap = preview.bitmap
                                        if (bitmap == null) Text(getString(R.string.waiting_frame), color = Color.White,
                                            style = MaterialTheme.typography.bodySmall)
                                        else Image(bitmap.asImageBitmap(), getString(R.string.display_preview, preview.displayId),
                                            modifier = Modifier.fillMaxSize())
                                    }
                                    Text("${preview.width} × ${preview.height}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard(getString(R.string.app_name)) {
                        Text(status.resolve(this@MainActivity), style = MaterialTheme.typography.bodyMedium)
                        Text(displayPowerStatus.summary(this@MainActivity), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            enabled = running || ((!localEnabled || validPort != null) && remoteError == null),
                            onClick = { perform {
                                if (running) stop()
                                else {
                                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
                                    start()
                                }
                            } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (running) getString(R.string.stop_mua) else getString(R.string.start_mua)) }
                    }
                }
                item {
                    SectionCard(getString(R.string.mcp_server), trailing = {
                        Switch(checked = localEnabled, enabled = !running,
                            modifier = Modifier.semantics { contentDescription = getString(R.string.local_mcp_server) }, onCheckedChange = { enabled -> perform {
                            updateLocalEnabled(enabled)
                        } })
                    }) {
                        Text(if (running) localStatus.resolve(this@MainActivity) else if (localEnabled) getString(R.string.ready_http) else getString(R.string.disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top) {
                                var addressExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(expanded = addressExpanded,
                                    onExpandedChange = { if (!running) addressExpanded = it },
                                    modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(value = listenAddress, onValueChange = {}, readOnly = true,
                                        label = { Text(getString(R.string.address)) }, singleLine = true, enabled = !running,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(addressExpanded) },
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth())
                                    ExposedDropdownMenu(expanded = addressExpanded,
                                        onDismissRequest = { addressExpanded = false }) {
                                        ListenerConfig.ADDRESSES.forEach { address ->
                                            DropdownMenuItem(text = { Text(address) }, onClick = {
                                                updateAddress(address)
                                                addressExpanded = false
                                            })
                                        }
                                    }
                                }
                                OutlinedTextField(value = portText,
                                    onValueChange = { updatePort(it) },
                                    label = { Text(getString(R.string.port)) }, isError = validPort == null,
                                    enabled = !running, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(104.dp))
                            }
                            if (validPort == null) Text(getString(R.string.invalid_port),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            val endpointHosts = if (listenAddress == "0.0.0.0") networkAddresses else listOf("127.0.0.1")
                            val port = validPort
                            if (port != null && endpointHosts.isEmpty()) {
                                Text(getString(R.string.no_network_address), style = MaterialTheme.typography.bodySmall)
                            } else if (port != null) endpointHosts.forEach { host ->
                                val endpoint = "http://$host:$port/mcp"
                                CopyRow(endpoint, getString(R.string.copy_endpoint, endpoint)) { perform {
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                        ClipData.newPlainText(getString(R.string.endpoint_clip), endpoint))
                                    Toast.makeText(this@MainActivity, getString(R.string.endpoint_copied), Toast.LENGTH_SHORT).show()
                                } }
                            }
                            Text(getString(R.string.authorization), style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(authorization, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                IconButton(onClick = { perform {
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                        ClipData.newPlainText(getString(R.string.authorization_clip), authorization).apply {
                                            if (Build.VERSION.SDK_INT >= 33) description.extras = android.os.PersistableBundle().apply {
                                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                                            }
                                        })
                                    Toast.makeText(this@MainActivity, getString(R.string.authorization_copied), Toast.LENGTH_SHORT).show()
                                } }) { Icon(Icons.Default.ContentCopy, contentDescription = getString(R.string.copy_authorization)) }
                                IconButton(onClick = { perform {
                                    resetAuthorization()
                                    Toast.makeText(this@MainActivity, getString(R.string.token_reset), Toast.LENGTH_LONG).show()
                                } }) { Icon(Icons.Default.Refresh, contentDescription = getString(R.string.reset_authorization)) }
                            }
                            Text(if (listenAddress == "0.0.0.0") getString(R.string.network_access)
                                else getString(R.string.local_access), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SectionCard(getString(R.string.central_server), trailing = {
                        Switch(checked = remoteConfig.enabled,
                            modifier = Modifier.semantics { contentDescription = getString(R.string.central_connection) },
                            enabled = !running, onCheckedChange = { enabled -> perform {
                            updateRemoteEnabled(enabled)
                        } })
                    }) {
                        Text(if (running) remoteStatus.resolve(this@MainActivity) else if (remoteConfig.enabled) getString(R.string.ready) else getString(R.string.disabled),
                            style = MaterialTheme.typography.bodyMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = remoteConfig.serverUrl,
                                onValueChange = { updateRemote(remoteConfig.copy(serverUrl = it)) },
                                label = { Text(getString(R.string.server_url)) }, placeholder = { Text("wss://mua.example.com") },
                                enabled = !running, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = remoteConfig.deviceName,
                                onValueChange = { updateRemote(remoteConfig.copy(deviceName = it)) },
                                label = { Text(getString(R.string.device_name)) }, enabled = !running, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = remoteConfig.deviceId,
                                onValueChange = { updateRemote(remoteConfig.copy(deviceId = it)) },
                                label = { Text(getString(R.string.device_id)) }, enabled = !running, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), trailingIcon = {
                                    IconButton(onClick = { perform {
                                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                            ClipData.newPlainText(getString(R.string.device_id_clip), remoteConfig.deviceId))
                                    } }) { Icon(Icons.Default.ContentCopy, getString(R.string.copy_device_id)) }
                                })
                            OutlinedTextField(value = remoteConfig.token,
                                onValueChange = { updateRemote(remoteConfig.copy(token = it)) },
                                label = { Text(getString(R.string.device_token)) }, enabled = !running, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth())
                            remoteError?.let { Text(it.resolve(this@MainActivity), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (!batteryExempt) item {
                    SectionCard(getString(R.string.screen_off)) {
                        Text(getString(R.string.battery_guidance),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { perform {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } }) { Text(getString(R.string.battery_settings)) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startObserving()
    }

    override fun onPause() {
        viewModel.stopObserving()
        super.onPause()
    }

}

@Composable
private fun SectionCard(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun CopyRow(label: String, copyDescription: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = copyDescription)
        }
    }
}
