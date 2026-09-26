package moe.reimu.nekoassistant

internal enum class TaskOutcome { COMPLETED, STOPPED, FAILED }
internal data class TaskNotice(val title: String, val message: String)

internal fun taskEndNotice(
    outcome: TaskOutcome,
    appName: String?,
    completionMessage: String? = null,
): TaskNotice? {
    // Failure already sends its detailed error. Never replace it with a completion alert.
    if (outcome == TaskOutcome.FAILED) return null
    val title = if (outcome == TaskOutcome.COMPLETED) "Task complete" else "Task stopped"
    val message = if (outcome == TaskOutcome.COMPLETED) {
        completionMessage?.takeIf { it.isNotBlank() } ?: "Your task has finished."
    } else {
        appName?.let { "$it is available. Open the chat to bring it to the front." }
            ?: "The task was stopped."
    }
    return TaskNotice(title, message)
}
