package moe.reimu.nekoassistant

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.reimu.nekoassistant.ai.ActionResult

/** Explicit, one-shot callback capabilities correlate results without exporting a receiver. */
object TermuxCommandRunner {
    private const val MAX_OUTPUT_CHARACTERS = 1_000_000
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ActionResult>>()

    suspend fun run(context: Context, command: String, workingDirectory: String): ActionResult =
        withContext(Dispatchers.Main.immediate) {
            if (command.isBlank() || command.contains('\u0000') ||
                workingDirectory.isBlank() || workingDirectory.contains('\u0000')) {
                return@withContext ActionResult.retry("Termux command and working directory must be nonempty and contain no NUL characters.")
            }
            if (command.toByteArray().size > 64 * 1024 || workingDirectory.length > 4096) {
                return@withContext ActionResult.retry("Termux command or working directory is too long.")
            }
            if (workingDirectory != "~" && !workingDirectory.startsWith("~/") &&
                !workingDirectory.startsWith("/")) {
                return@withContext ActionResult.retry("Termux working directory must be an absolute path, ~, or ~/path.")
            }
            try {
                context.packageManager.getPackageInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext ActionResult.retry("Install and open Termux to initialize its environment first.")
            }
            if (context.checkSelfPermission(TermuxConstants.PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
                return@withContext ActionResult.retry("Grant NekoAssistant the Run commands in Termux environment permission in Android app settings.")
            }

            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<ActionResult>()
            val callback = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TermuxResultReceiver::class.java).apply {
                    data = Uri.Builder().scheme("neko-termux").authority("result").appendPath(id).build()
                },
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            pending[id] = result
            try {
                currentCoroutineContext().ensureActive()
                val intent = Intent(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND).apply {
                    setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME)
                    putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash")
                    putExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, arrayOf("-c", command))
                    putExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR, workingDirectory)
                    putExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, true)
                    putExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, callback)
                }
                if (context.startService(intent) == null) {
                    return@withContext ActionResult.retry("Termux command service is unavailable.")
                }
                withTimeoutOrNull(120_000) { result.await() }
                    ?: ActionResult.fail("No Termux result within 120 seconds. The command may still be running; do not automatically retry. Check Termux and allow-external-apps=true in ~/.termux/termux.properties.")
            } catch (e: CancellationException) {
                // RUN_COMMAND has no cancellation protocol. Cancelling this wait does not kill its command.
                throw e
            } catch (e: Exception) {
                ActionResult.fail("Could not obtain a Termux result: ${e.message}. Check Termux setup and allow-external-apps=true. If dispatched, the command may still be running.")
            } finally {
                pending.remove(id)
                callback.cancel()
                result.cancel()
            }
        }

    internal fun receive(id: String, bundle: Bundle?) {
        val response = pending.remove(id) ?: return
        response.complete(parseResult(bundle))
    }

    internal fun parseResult(bundle: Bundle?): ActionResult {
        if (bundle == null) return ActionResult.fail("Termux returned no result bundle; execution outcome is unknown.")
        val error = bundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, Activity.RESULT_OK)
        if (error != Activity.RESULT_OK) {
            return ActionResult.fail("Termux error $error: " +
                bundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG).orEmpty().take(8000))
        }
        if (!bundle.containsKey(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE)) {
            return ActionResult.fail("Termux returned no exit code; execution outcome is unknown.")
        }
        val stdout = bundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty()
        val stderr = bundle.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty()
        val exitCode = bundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE)
        val truncated = stdout.length > MAX_OUTPUT_CHARACTERS || stderr.length > MAX_OUTPUT_CHARACTERS ||
            bundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH, stdout.length) > stdout.length ||
            bundle.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH, stderr.length) > stderr.length
        // A command's nonzero exit is a tool result, distinct from failure to obtain a result.
        return ActionResult.success(buildString {
            appendLine("Termux exit code: $exitCode")
            appendLine("stdout:")
            appendLine(stdout.take(MAX_OUTPUT_CHARACTERS))
            appendLine("stderr:")
            append(stderr.take(MAX_OUTPUT_CHARACTERS))
            if (truncated) append("\n[Output truncated]")
        })
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "neko-termux" || uri.authority != "result") return
        val id = uri.lastPathSegment ?: return
        TermuxCommandRunner.receive(id, intent.getBundleExtra(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE))
    }
}
