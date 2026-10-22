package moe.reimu.mua

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException

@Composable internal fun PushSettings() {
    val context = LocalContext.current
    val prefs = remember { context.pushPreferences() }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var failed by remember { mutableStateOf(false) }
    val initialized = remember {
        FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
    }
    LaunchedEffect(enabled) {
        PushRegistration.schedule(context)
        if (initialized) {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = enabled
            failed = false
            if (enabled) {
                try {
                    token = refreshFcmToken(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                }
            }
        }
    }
    val title = stringResource(R.string.fcm_wake)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            enabled = initialized || enabled,
            modifier = Modifier.semantics { contentDescription = title },
            onCheckedChange = {
                enabled = it
                prefs.edit { putBoolean("enabled", it) }
            },
        )
    }
    val error = when {
        !initialized -> R.string.fcm_initialization_failed
        failed -> R.string.fcm_token_failed
        else -> null
    }
    Text(
        stringResource(error ?: R.string.fcm_description),
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (enabled && token.isNotEmpty()) TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("FCM", token))
    }) { Text(stringResource(R.string.fcm_copy_token)) }
}
