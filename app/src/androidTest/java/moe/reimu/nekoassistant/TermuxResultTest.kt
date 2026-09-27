package moe.reimu.nekoassistant

import android.app.Activity
import android.os.Bundle
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import org.junit.Assert.*
import org.junit.Test

class TermuxResultTest {
    @Test
    fun returnsBothStreamsAndNonzeroExitCode() {
        val result = TermuxCommandRunner.parseResult(Bundle().apply {
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, Activity.RESULT_OK)
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, 7)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "out")
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "err")
        })
        assertFalse(result.stop)
        assertEquals("Termux exit code: 7\nstdout:\nout\nstderr:\nerr", result.prompt)
    }

    @Test
    fun rejectsMissingResultsAndReportsProtocolErrors() {
        assertTrue(TermuxCommandRunner.parseResult(null).stop)
        assertTrue(TermuxCommandRunner.parseResult(Bundle()).stop)
        val result = TermuxCommandRunner.parseResult(Bundle().apply {
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 1)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "External apps disabled")
        })
        assertTrue(result.stop)
        assertTrue(result.prompt.contains("External apps disabled"))
    }

    @Test
    fun preservesOutputUpToOneMillionCharactersPerStream() {
        val stdout = "a".repeat(1_000_000)
        val stderr = "b".repeat(1_000_000)
        val result = TermuxCommandRunner.parseResult(Bundle().apply {
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, 0)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, stdout)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, stderr)
        })
        assertEquals("Termux exit code: 0\nstdout:\n$stdout\nstderr:\n$stderr", result.prompt)
    }

    @Test
    fun capsOutputAndReportsUpstreamTruncation() {
        val result = TermuxCommandRunner.parseResult(Bundle().apply {
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, 0)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "x".repeat(1_000_001))
        })
        assertTrue(result.prompt.contains("[Output truncated]"))
        assertEquals("x".repeat(1_000_000), result.prompt.substringAfter("stdout:\n").substringBefore("\nstderr:"))
        val upstream = TermuxCommandRunner.parseResult(Bundle().apply {
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, 0)
            putString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "tail")
            putInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH, 100000)
        })
        assertTrue(upstream.prompt.contains("[Output truncated]"))
    }
}
