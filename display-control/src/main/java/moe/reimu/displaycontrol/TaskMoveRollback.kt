package moe.reimu.displaycontrol

/** Restore placement before returning any failure from a move that may have changed system state. */
internal fun taskMoveWithRollback(
    restoreDisplayId: Int, wasInactive: Boolean,
    moveAndVerify: () -> Unit, restoreAndVerify: () -> Unit,
) {
    try {
        moveAndVerify()
    } catch (failure: Exception) {
        try {
            restoreAndVerify()
        } catch (rollback: Exception) {
            throw IllegalStateException("Task move failed and restoration to display $restoreDisplayId failed; " +
                "refresh recent_tasks_list to check its location. ${failure.message}", rollback)
        }
        val restored = if (wasInactive) "Previously inactive task restored on main display"
            else "Task restored to display $restoreDisplayId"
        throw IllegalStateException("${failure.message ?: "Task move failed"}. $restored", failure)
    }
}
