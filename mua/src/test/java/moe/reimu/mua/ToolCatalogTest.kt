package moe.reimu.mua

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTest {
    private fun validate(name: String, args: String) = ToolCatalog.specs.single { it.name == name }
        .validate(Json.parseToJsonElement(args).jsonObject)

    @Test fun requiredArgumentsMatchAdvertisedSchemas() {
        assertEquals(9, ToolCatalog.specs.size)
        ToolCatalog.specs.forEach { spec ->
            assertEquals(spec.properties.keys, spec.schema().required?.toSet())
            assertEquals(spec.properties, spec.schema().properties)
        }
    }

    @Test fun malformedCoordinatesCannotReachDisplayOperations() {
        for (args in listOf("""{"x":-1,"y":2}""", """{"x":"2","y":2}""", """{"x":1}""",
            """{"x":1,"y":2,"extra":3}""", """{"x":null,"y":2}""")) {
            assertThrows(Exception::class.java) { validate("tap", args) }
        }
    }

    @Test fun displayAndGestureLimitsAreEnforced() {
        assertThrows(Exception::class.java) { validate("key_press", """{"keyCode":2.5}""") }
        assertThrows(Exception::class.java) { validate("display_start", """{"width":4097,"height":720,"dpi":240}""") }
        assertThrows(Exception::class.java) { validate("swipe", """{"x1":1,"x2":2,"y1":1,"y2":2,"durationMs":5001}""") }
        assertThrows(Exception::class.java) { validate("app_launch", """{"package":""}""") }
    }

    @Test fun validArgumentsRemainCompatible() {
        validate("tap", """{"x":1.5,"y":2}""")
        validate("display_start", """{"width":720,"height":1280,"dpi":240}""")
        validate("app_launch", """{"package":"com.android.settings"}""")
        validate("screenshot", "{}")
    }
}
