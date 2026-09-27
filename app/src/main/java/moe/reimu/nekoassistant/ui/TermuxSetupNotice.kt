package moe.reimu.nekoassistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import moe.reimu.nekoassistant.TermuxAvailability
import moe.reimu.nekoassistant.TermuxCommandRunner
import com.termux.shared.termux.TermuxConstants

/** Setup is independent of chats and never authorizes a tool invocation. */
@Composable
fun TermuxSetupNotice() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences = remember(context) {
        context.getSharedPreferences("termux_setup", Context.MODE_PRIVATE)
    }
    var availability by remember { mutableStateOf<TermuxAvailability?>(null) }
    var needsSettings by rememberSaveable { mutableStateOf(false) }
    var showMissingNotice by rememberSaveable { mutableStateOf(false) }
    var requesting by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        availability = TermuxCommandRunner.availability(context)
        if (availability != TermuxAvailability.PERMISSION_REQUIRED) needsSettings = false
        if (availability != TermuxAvailability.NOT_INSTALLED) {
            showMissingNotice = false
        } else if (!preferences.getBoolean("missing_notice_shown", false)) {
            showMissingNotice = true
            preferences.edit().putBoolean("missing_notice_shown", true).apply()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requesting = false
        refresh()
        needsSettings = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, TermuxConstants.PERMISSION_RUN_COMMAND)
    }

    LifecycleResumeEffect(context) {
        refresh()
        onPauseOrDispose {}
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == TermuxConstants.TERMUX_PACKAGE_NAME &&
                    !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (showMissingNotice) {
        AlertDialog(
            onDismissRequest = { showMissingNotice = false },
            title = { Text("Termux is not installed") },
            text = { Text("Install and open Termux to let the assistant run shell commands. You can continue using other tools without it.") },
            confirmButton = {
                TextButton(onClick = { showMissingNotice = false }) { Text("Got it") }
            },
        )
    }

    if (availability == TermuxAvailability.PERMISSION_REQUIRED) {
        Surface(
            onClick = {
                if (!requesting) {
                    if (needsSettings) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        })
                    } else {
                        requesting = true
                        permissionLauncher.launch(TermuxConstants.PERMISSION_RUN_COMMAND)
                    }
                }
            },
            enabled = !requesting,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (needsSettings) "Allow Termux access · Tap to open app settings"
                else "Allow Termux access · Tap to grant permission",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
