package moe.reimu.mua

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCatalogTest {
    private fun validate(name: String, args: String) = ToolCatalog.specs.single { it.name == name }
        .validate(Json.parseToJsonElement(args).jsonObject)

    @Test fun requiredArgumentsMatchAdvertisedSchemas() {
        assertEquals(12, ToolCatalog.specs.size)
        ToolCatalog.specs.forEach { spec ->
            assertEquals(spec.properties.keys, spec.schema().required?.toSet())
            assertEquals(spec.properties, spec.schema().properties)
        }
    }

    @Test fun malformedCoordinatesCannotReachDisplayOperations() {
        for (args in listOf("""{"displayId":7,"x":"2","y":2}""", """{"displayId":7,"x":1}""",
            """{"displayId":7,"x":null,"y":2}""")) {
            assertThrows(Exception::class.java) { validate("tap", args) }
        }
    }

    @Test fun displayAndGestureLimitsAreEnforced() {
        assertThrows(Exception::class.java) { validate("key_press", """{"displayId":7,"keyCode":2.5}""") }
        assertThrows(Exception::class.java) { validate("display_start", """{"width":4097,"height":720,"dpi":240}""") }
        assertThrows(Exception::class.java) { validate("swipe", """{"displayId":7,"x1":1,"x2":2,"y1":1,"y2":2,"durationMs":-1}""") }
        assertThrows(Exception::class.java) { validate("app_launch", """{"displayId":7,"package":""}""") }
    }

    @Test fun validDisplaySpecificArgumentsAreAccepted() {
        validate("tap", """{"displayId":7,"x":1.5,"y":2}""")
        validate("display_start", "{}")
        validate("app_launch", """{"displayId":7,"package":"com.android.settings"}""")
        validate("screenshot", """{"displayId":7}""")
        validate("displays_list", "{}")
        validate("recent_tasks_list", "{}")
        validate("recent_task_move", """{"taskId":42,"displayId":7}""")
    }
    @Test fun displayStartDoesNotAcceptSizeOrDensityOverrides() {
        for (args in listOf("{\"width\":720}", "{\"height\":1280}", "{\"dpi\":240}")) {
            assertThrows(Exception::class.java) { validate("display_start", args) }
        }
    }

    @Test fun displayToolsRequireAnExplicitNonnegativeId() {
        val examples = mapOf(
            "recent_task_move" to "{\"taskId\":42}",
            "display_stop" to "{}", "screenshot" to "{}", "focused_app" to "{}",
            "tap" to "{\"x\":1,\"y\":2}",
            "swipe" to "{\"x1\":1,\"y1\":1,\"x2\":2,\"y2\":2,\"durationMs\":100}",
            "key_press" to "{\"keyCode\":4}", "app_launch" to "{\"package\":\"com.android.settings\"}",
        )
        examples.forEach { (name, args) ->
            assertThrows(Exception::class.java) { validate(name, args) }
            for (id in listOf("-1", "1.5", "null", "\"7\"")) {
                val invalid = JsonObject(Json.parseToJsonElement(args).jsonObject + ("displayId" to Json.parseToJsonElement(id)))
                assertThrows(Exception::class.java) { validate(name, invalid.toString()) }
            }
        }
    }

    @Test fun recentTaskMoveRequiresAnIntegerTaskId() {
        for (id in listOf("-1", "1.5", "null", "\"42\"")) {
            assertThrows(Exception::class.java) {
                validate("recent_task_move", "{\"displayId\":7,\"taskId\":$id}")
            }
        }
        assertThrows(Exception::class.java) { validate("recent_task_move", "{\"displayId\":7}") }
    }

    @Test fun removedNumericAndStringCapsAreAccepted() {
        validate("tap", """{"displayId":7,"x":-10,"y":100000}""")
        validate("swipe", """{"displayId":7,"x1":-1,"y1":0,"x2":100000,"y2":0,"durationMs":60000}""")
        validate("swipe", """{"displayId":7,"x1":0,"y1":0,"x2":1,"y2":1,"durationMs":9223372036854775807}""")
        validate("key_press", """{"displayId":7,"keyCode":1001}""")
        validate("app_launch", """{"displayId":7,"package":"${"a".repeat(256)}"}""")
    }

    @Test fun extraArgumentsAndMainDisplayAreAccepted() {
        validate("tap", """{"displayId":0,"x":-10,"y":100000,"extra":true}""")
        validate("app_launch", """{"displayId":0,"package":"com.android.settings"}""")
        validate("apps_list", """{"extra":"ignored"}""")
    }

}
