package moe.reimu.displaycontrol

import android.graphics.Bitmap

/** Immutable UI snapshot. Bitmaps are owned by Compose/GC, never recycled while displayed. */
data class DisplayPreview(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
)
