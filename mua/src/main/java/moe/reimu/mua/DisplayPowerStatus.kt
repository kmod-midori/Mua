package moe.reimu.mua

/** One immutable snapshot shared by the foreground notification and main screen. */
internal data class DisplayPowerStatus(
    val displayActive: Boolean = false,
    val cpuKeptAwake: Boolean = false,
) {
    val displayLabel: String get() = if (displayActive) "Virtual display active" else "No active virtual display"
    val cpuLabel: String get() = if (cpuKeptAwake) "CPU kept awake" else "CPU may sleep"
    val summary: String get() = "$displayLabel • $cpuLabel"
}
