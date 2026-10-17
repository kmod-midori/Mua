package moe.reimu.mua

import android.content.Context

/** One immutable snapshot shared by the foreground notification and main screen. */
internal data class DisplayPowerStatus(
    val displayCount: Int = 0,
    val cpuKeptAwake: Boolean = false,
) {
    fun summary(context: Context): String = if (displayCount == 0) {
        context.getString(R.string.no_displays)
    } else {
        context.resources.getQuantityString(R.plurals.active_displays, displayCount, displayCount)
    }
}
