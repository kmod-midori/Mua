package moe.reimu.mua

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import moe.reimu.displaycontrol.DeviceToolResult
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DeviceToolResultTest {
    @Test fun textIsPassedThroughWithoutChangingJsonOrUnicode() {
        val text = """[{"title":"通知","displayId":7}]"""
        val result = DeviceToolResult.Text(text).toMcpResult()
        assertEquals(text, (result.content.single() as TextContent).text)
        assertNotEquals(true, result.isError)
    }

    @Test fun imageBytesAndMimeTypeSurviveMcpEncoding() {
        val bytes = ByteArray(8192) { it.toByte() }
        val result = DeviceToolResult.Image(bytes, "image/png").toMcpResult()
        val image = result.content.single() as ImageContent
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(bytes, Base64.getDecoder().decode(image.data))
        assertFalse(image.data.contains('\n'))
    }
}
