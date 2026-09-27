package moe.reimu.nekoassistant

import com.termux.shared.termux.TermuxConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxWorkingDirectoryTest {
    @Test
    fun expandsBareHomeAndHomeRelativePaths() {
        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        assertEquals(home, TermuxCommandRunner.resolveWorkingDirectory("~"))
        assertEquals("$home/", TermuxCommandRunner.resolveWorkingDirectory("~/"))
        assertEquals("$home/my files", TermuxCommandRunner.resolveWorkingDirectory("~/my files"))
    }

    @Test
    fun preservesAbsolutePathsIncludingLiteralTildes() {
        assertEquals("/sdcard/Download", TermuxCommandRunner.resolveWorkingDirectory("/sdcard/Download"))
        assertEquals("/tmp/~", TermuxCommandRunner.resolveWorkingDirectory("/tmp/~"))
    }
}
