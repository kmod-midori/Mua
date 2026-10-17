package moe.reimu.mua

/** One immutable snapshot shared by the foreground notification and main screen. */
internal data class DisplayPowerStatus(
    val displayCount: Int = 0,
    val cpuKeptAwake: Boolean = false,
) {
    val displayLabel: String get() = when (displayCount) {
        0 -> "No active virtual displays"
        1 -> "1 virtual display active"
        else -> "$displayCount virtual displays active"
    }
    val cpuLabel: String get() = if (cpuKeptAwake) "CPU kept awake" else "CPU may sleep"
    val summary: String get() = "$displayLabel • $cpuLabel"
}
