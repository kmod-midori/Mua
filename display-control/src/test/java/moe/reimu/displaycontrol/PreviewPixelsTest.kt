package moe.reimu.displaycontrol

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PreviewPixelsTest {
    @Test fun samplesCroppedRowsWithPaddingAndRgbaChannels() {
        val buffer = ByteBuffer.allocate(48)
        // Three pixels per row, with four padding bytes at the end of each row.
        for (y in 0..2) for (x in 0..2) {
            val offset = y * 16 + x * 4
            buffer.put(offset, (x + 10).toByte())
            buffer.put(offset + 1, (y + 20).toByte())
            buffer.put(offset + 2, 30)
            buffer.put(offset + 3, 128.toByte())
        }
        buffer.position(7)
        assertArrayEquals(intArrayOf(0x800b151e.toInt(), 0x800c151e.toInt(),
            0x800b161e.toInt(), 0x800c161e.toInt()),
            samplePreviewPixels(buffer, 16, 4, 1, 1, 2, 2, 2, 2))
        assertEquals(7, buffer.position())
    }

    @Test fun downsamplesIntoOnlyThumbnailSizedStorage() {
        val buffer = ByteBuffer.allocate(8 * 4 * 4)
        for (y in 0..3) for (x in 0..7) {
            val offset = (y * 8 + x) * 4
            buffer.put(offset, x.toByte())
            buffer.put(offset + 1, y.toByte())
            buffer.put(offset + 3, 255.toByte())
        }
        assertArrayEquals(intArrayOf(0xff020200.toInt(), 0xff060200.toInt()),
            samplePreviewPixels(buffer, 32, 4, 0, 0, 8, 4, 2, 1))
    }
}
