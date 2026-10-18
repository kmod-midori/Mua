package moe.reimu.displaycontrol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class DeviceToolDispatcherTest {
    @Test fun wireAndTypedCallsReachTheSameHandler() {
        val received = mutableListOf<KeyPress>()
        val dispatcher = DeviceToolDispatcher(DeviceToolDispatcher.Handler(
            DeviceToolCatalog.definition<KeyPress, String>(KeyPress::class),
        ) { command -> received.add(command); "OK" })

        assertEquals("OK", dispatcher.execute(KeyPress(7, 4)))
        assertEquals(DeviceToolResult.Text("OK"), dispatcher.execute("key_press", """{"displayId":7,"keyCode":4,"extra":true}"""))
        assertEquals(listOf(KeyPress(7, 4), KeyPress(7, 4)), received)
        assertEquals(setOf("key_press"), dispatcher.names)
    }

    @Test fun unknownOrMalformedCallsNeverExecute() {
        var calls = 0
        val dispatcher = DeviceToolDispatcher(DeviceToolDispatcher.Handler(
            DeviceToolCatalog.definition<KeyPress, String>(KeyPress::class),
        ) { calls++; "OK" })

        assertThrows(IllegalStateException::class.java) { dispatcher.execute("unknown", "{}") }
        assertThrows(Exception::class.java) { dispatcher.execute("key_press", """{"displayId":7}""") }
        assertThrows(Exception::class.java) { dispatcher.execute("key_press", """{"displayId":[],"keyCode":4}""") }
        assertEquals(0, calls)
    }

    @Test fun textInputPreservesWhitespaceAndEnforcesSchemaLength() {
        val definition = DeviceToolCatalog.definition<InputText, String>(InputText::class)
        assertEquals(InputText(0, " \n"), definition.decode("""{"displayId":0,"text":" \n"}""").command)
        assertThrows(IllegalArgumentException::class.java) {
            definition.decode("""{"displayId":0,"text":""}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            definition.decode("""{"displayId":0,"text":123}""")
        }
    }

    @Test fun typedRepliesPreserveUnicodeAndNotificationKeys() {
        val command = ReplyNotification("0|package|42|null|10001", 1, 0, "reply", "你好\nreply")
        val json = DeviceToolCatalog.json
        assertEquals(command, json.decodeFromString<ReplyNotification>(json.encodeToString(command)))
    }

    @Test fun taskResultsPreserveNullableFieldsAndExactTitles() {
        val task = RecentTaskInfo(42, "超级教练 学员版", 7, false, "fixture", null, null)
        val json = DeviceToolCatalog.json
        assertEquals(listOf(task), json.decodeFromString<List<RecentTaskInfo>>(json.encodeToString(listOf(task))))
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun everySchemaMatchesItsTypedCommand() {
        DeviceToolCatalog.definitions.forEach { definition ->
            val descriptor = definition.serializer.descriptor
            val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
            assertEquals(definition.spec.name, names, definition.spec.inputSchema.properties.keys)
            assertEquals(names, definition.spec.inputSchema.required.toSet())
        }
    }
}
