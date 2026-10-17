package moe.reimu.displaycontrol

import java.nio.ByteBuffer

/** Samples RGBA directly into thumbnail-sized storage, including ImageReader row padding/crops. */
internal fun samplePreviewPixels(
    buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
    left: Int, top: Int, width: Int, height: Int, outputWidth: Int, outputHeight: Int,
): IntArray = IntArray(outputWidth * outputHeight) { index ->
    val x = left + ((index % outputWidth + 0.5) * width / outputWidth).toInt().coerceAtMost(width - 1)
    val y = top + ((index / outputWidth + 0.5) * height / outputHeight).toInt().coerceAtMost(height - 1)
    val offset = y * rowStride + x * pixelStride
    val r = buffer.get(offset).toInt() and 255
    val g = buffer.get(offset + 1).toInt() and 255
    val b = buffer.get(offset + 2).toInt() and 255
    val a = buffer.get(offset + 3).toInt() and 255
    (a shl 24) or (r shl 16) or (g shl 8) or b
}
