package moe.reimu.displaycontrol

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import androidx.core.graphics.createBitmap

/** Owns the capture surface and latest frame. Close after stopping the remote display. */
class DisplayCapture(width: Int, height: Int, handler: Handler, onFrame: () -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var image: Image? = null
    private val reader = ImageReader.newInstance(
        width, height, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
    )
    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            val updated = synchronized(lock) {
                if (closed) return@synchronized false
                val next = source.acquireLatestImage() ?: return@synchronized false
                image?.close()
                image = next
                true
            }
            // Never call host code while holding the image lock.
            if (updated) onFrame()
        }, handler)
    }

    /** Returns independent pixels owned by the caller, or null before the first frame. */
    fun bitmap(): Bitmap? = synchronized(lock) {
        val frame = image ?: return@synchronized null
        val plane = frame.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = createBitmap(paddedWidth, frame.height)
        try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val crop = frame.cropRect
            Bitmap.createBitmap(padded, crop.left, crop.top, crop.width(), crop.height()).also {
                if (it !== padded) padded.recycle()
            }
        } catch (e: Exception) {
            padded.recycle()
            throw e
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            reader.setOnImageAvailableListener(null, null)
            image?.close()
            image = null
            reader.close()
        }
    }
}
