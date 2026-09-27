package moe.reimu.nekoassistant.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.reimu.nekoassistant.ToolApprovalRequest

@Composable
fun ToolApprovalDialog(request: ToolApprovalRequest, onResponse: (String, Boolean, String?) -> Unit) {
    val context = LocalContext.current
    var rejectionMessage by rememberSaveable(request.id) { mutableStateOf("") }
    var permissionRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionRequestId?.let { id ->
            // A stale permission result must never authorize a newer tool request.
            if (id == request.id) {
                onResponse(id, request.details.permissions.all {
                    result[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }, null)
            }
        }
        permissionRequestId = null
    }
    AlertDialog(
        onDismissRequest = { onResponse(request.id, false, rejectionMessage) },
        title = { Text(request.details.title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(request.details.details, fontFamily = FontFamily.Monospace) }
                OutlinedTextField(
                    value = rejectionMessage,
                    onValueChange = { rejectionMessage = it },
                    label = { Text("What should the agent do instead? (optional)") },
                    supportingText = { Text("Sent to the agent only when you reject this tool.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = permissionRequestId == null, onClick = {
                val missing = request.details.permissions.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) onResponse(request.id, true, null)
                else {
                    permissionRequestId = request.id
                    permissions.launch(missing.toTypedArray())
                }
            }) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(request.id, false, rejectionMessage) }) { Text("Reject") }
        },
    )
}
