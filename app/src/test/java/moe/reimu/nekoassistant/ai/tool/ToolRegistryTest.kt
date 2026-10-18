package moe.reimu.nekoassistant.ai.tool

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun decodesEveryAdvertisedTool() {
        val calls = listOf(
            Triple("SearchApp", """{"query":"browser"}""", SearchApp("browser")),
            Triple("ListApps", """{"page":2}""", ListApps(2)),
            Triple("Launch", """{"packageName":"com.example.app"}""", Launch("com.example.app")),
            Triple("Tap", """{"x":12.5,"y":20}""", Tap(12.5f, 20f)),
            Triple("Type", """{"text":"hello"}""", Type("hello")),
            Triple("Swipe", """{"startX":1,"startY":2,"endX":3,"endY":4}""", Swipe(1f, 2f, 3f, 4f)),
            Triple("LongPress", """{"x":12,"y":20}""", LongPress(12, 20)),
            Triple("DoubleTap", """{"x":12,"y":20}""", DoubleTap(12, 20)),
            Triple("TakeOver", """{"message":"Sign in"}""", TakeOver("Sign in")),
            Triple("Back", "{}", Back),
            Triple("Wait", """{"seconds":3}""", Wait(3)),
            Triple("Finish", """{"message":"Done"}""", Finish("Done")),
            Triple("TermuxShell", """{"command":"pwd","workingDirectory":"~"}""", TermuxShell("pwd", "~")),
            Triple("NotificationsList", "{}", NotificationsList()),
            Triple("NotificationClear", """{"key":"test"}""", NotificationClear("test")),
            Triple("NotificationClick", """{"key":"test","displayId":7}""", NotificationClick("test", 7)),
            Triple("NotificationAction", """{"key":"test","actionIndex":0,"displayId":7}""", NotificationAction("test", 0, 7)),
            Triple("NotificationReply", """{"key":"test","actionIndex":0,"displayId":7,"resultKey":"reply","text":"hello"}""", NotificationReply("test", 0, 7, "reply", "hello")),
        )

        assertEquals(calls.map { it.first }, ToolRegistry.definitions(termuxAvailable = true).map { it.function.name })
        calls.forEach { (name, arguments, expected) ->
            val decoded = ToolRegistry.decode(name, arguments, termuxAvailable = true)
            if (expected is NotificationsList) assertEquals(expected.javaClass, decoded?.javaClass)
            else assertEquals(name, expected, decoded)
        }
    }

    @Test
    fun unavailableTermuxIsNeitherAdvertisedNorDecoded() {
        assertEquals(
            ToolRegistry.definitions(true).filter { it.function.name != "TermuxShell" },
            ToolRegistry.definitions(false),
        )
        assertNull(ToolRegistry.decode("TermuxShell", """{"command":"pwd","workingDirectory":"~"}"""))
        assertEquals(Back, ToolRegistry.decode("Back", "{}"))
    }

    @Test
    fun preservesDefaultsAndIgnoresUnknownArguments() {
        assertEquals(ListApps(), ToolRegistry.decode("ListApps", "{}"))
        assertEquals(Tap(1f, 2f), ToolRegistry.decode("Tap", """{"x":1,"y":2,"extra":true}"""))
        assertSame(Back, ToolRegistry.decode("Back", ""))
    }

    @Test
    fun unknownToolsAndInvalidArgumentsCannotProduceAnAction() {
        assertNull(ToolRegistry.decode("Unknown", "{}"))
        assertThrows(SerializationException::class.java) {
            ToolRegistry.decode("Launch", "{}")
        }
        assertThrows(SerializationException::class.java) {
            ToolRegistry.decode("Wait", """{"seconds":"invalid"}""")
        }
    }
}
