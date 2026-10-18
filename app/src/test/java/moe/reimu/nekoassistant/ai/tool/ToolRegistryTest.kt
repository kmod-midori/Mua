package moe.reimu.nekoassistant.ai.tool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import moe.reimu.displaycontrol.DeviceToolCatalog
import moe.reimu.displaycontrol.DeviceToolSchema
import moe.reimu.displaycontrol.Tap
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    @Test fun deviceDefinitionsComeDirectlyFromSharedCatalog() {
        val definitions = ToolRegistry.definitions(true).associateBy { it.function.name }
        DeviceToolCatalog.specs.forEach { spec ->
            val tool = checkNotNull(definitions[spec.name]).function
            assertEquals(spec.description, tool.description)
            assertEquals(Json.encodeToJsonElement(DeviceToolSchema.serializer(), spec.inputSchema), tool.parameters?.schema)
        }
        assertEquals(DeviceToolCatalog.specs.map { it.name }.toSet() + setOf("TakeOver", "Wait", "Finish", "TermuxShell"), definitions.keys)
    }

    @Test fun deviceCallsUseTheSharedDecoder() {
        val action = ToolRegistry.decode("tap", """{"displayId":7,"x":12.5,"y":20,"extra":true}""") as DeviceAction
        assertEquals(Tap(7, 12.5f, 20f), action.call.command)
        assertThrows(Exception::class.java) { ToolRegistry.decode("tap", """{"x":1,"y":2}""") }
        assertNull(ToolRegistry.decode("Tap", """{"x":1,"y":2}"""))
    }

    @Test fun orchestrationAndApprovalRemainLocal() {
        assertEquals(TakeOver("Sign in"), ToolRegistry.decode("TakeOver", """{"message":"Sign in"}"""))
        assertEquals(Wait(3), ToolRegistry.decode("Wait", """{"seconds":3}"""))
        assertEquals(Finish("Done"), ToolRegistry.decode("Finish", """{"message":"Done"}"""))
        assertNull(ToolRegistry.decode("TermuxShell", """{"command":"pwd","workingDirectory":"~"}"""))
        assertEquals(TermuxShell("pwd", "~"), ToolRegistry.decode("TermuxShell", """{"command":"pwd","workingDirectory":"~"}""", true))
        assertFalse(ToolRegistry.definitions().any { it.function.name == "TermuxShell" })
        assertThrows(SerializationException::class.java) { ToolRegistry.decode("Wait", """{"seconds":"invalid"}""") }
        assertNull(ToolRegistry.decode("Unknown", "{}"))
    }
}
