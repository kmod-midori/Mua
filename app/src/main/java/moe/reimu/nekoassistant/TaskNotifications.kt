package moe.reimu.nekoassistant

internal enum class TaskOutcome { COMPLETED, STOPPED, FAILED }
internal data class TaskNotice(val title: String, val message: String)

internal fun taskEndNotice(outcome: TaskOutcome, appName: String?): TaskNotice? {
    // Failure already sends its detailed error. Never replace it with a completion alert.
    if (outcome == TaskOutcome.FAILED) return null
    val title = if (outcome == TaskOutcome.COMPLETED) "Task complete" else "Task stopped"
    val message = appName?.let { "$it is available. Open the chat to bring it to the front." }
        ?: if (outcome == TaskOutcome.COMPLETED) "Your task has finished." else "The task was stopped."
    return TaskNotice(title, message)
}
