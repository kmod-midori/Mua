package moe.reimu.displaycontrol

import org.junit.Assert.*
import org.junit.Test

class TaskMoveRollbackTest {
    @Test fun foregroundConflictAfterMoveRestoresSourceBeforeReportingError() {
        var display = 4
        val error = assertThrows(IllegalStateException::class.java) {
            taskMoveWithRollback(4, false, {
                display = 7
                error("App is in front on display 4")
            }, { display = 4 })
        }
        assertEquals(4, display)
        assertTrue(error.message!!.contains("Task restored to display 4"))
    }

    @Test fun failedRestorationReportsUncertainLocation() {
        val error = assertThrows(IllegalStateException::class.java) {
            taskMoveWithRollback(4, false, { error("Conflict") }, { error("Source display removed") })
        }
        assertTrue(error.message!!.contains("restoration to display 4 failed"))
        assertTrue(error.message!!.contains("refresh recent_tasks_list"))
        assertEquals("Source display removed", error.cause?.message)
    }

    @Test fun successfulMoveDoesNotRestore() {
        var restored = false
        taskMoveWithRollback(4, false, {}, { restored = true })
        assertFalse(restored)
    }

    @Test fun failedInactiveRestoreReportsMainDisplayFallback() {
        var restored = false
        val error = assertThrows(IllegalStateException::class.java) {
            taskMoveWithRollback(0, true, { error("Conflict") }, { restored = true })
        }
        assertTrue(restored)
        assertTrue(error.message!!.contains("Previously inactive task restored on main display"))
    }
}
