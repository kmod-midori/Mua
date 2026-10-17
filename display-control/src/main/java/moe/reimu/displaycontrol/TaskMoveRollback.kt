package moe.reimu.displaycontrol

/** Request restoration before returning any failure from a move that may have changed system state. */
internal fun taskMoveWithRollback(
    restoreDisplayId: Int, wasInactive: Boolean,
    moveAndCheck: () -> Unit, requestRestore: () -> Unit,
) {
    try {
        moveAndCheck()
    } catch (failure: Exception) {
        try {
            requestRestore()
        } catch (rollback: Exception) {
            throw IllegalStateException("Task move failed and restoration to display $restoreDisplayId failed; " +
                "refresh recent_tasks_list to check its location. ${failure.message}", rollback)
        }
        val restoration = if (wasInactive) "Restoration requested on main display for previously inactive task"
            else "Restoration requested to display $restoreDisplayId"
        throw IllegalStateException("${failure.message ?: "Task move failed"}. $restoration; refresh recent_tasks_list to check its location.", failure)
    }
}
