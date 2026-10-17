package moe.reimu.mua

import android.graphics.Bitmap

/** Immutable UI snapshot. Bitmaps are owned by Compose/GC, never recycled while displayed. */
internal data class DisplayPreview(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?,
)
