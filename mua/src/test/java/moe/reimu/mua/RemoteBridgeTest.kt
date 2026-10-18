package moe.reimu.mua

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RemoteBridgeTest {
    @Test fun registersValidatesRelaysImagesAndReconnects() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val reconnected = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        var connections = 0
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/devices/phone/ws") {
                    try {
                        assertEquals("Bearer device-secret", call.request.headers["Authorization"])
                        val hello = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                        assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
                        assertEquals("Test phone", hello["name"]?.jsonPrimitive?.content)
                        assertEquals(ToolCatalog.specs.size, hello["tools"]?.jsonArray?.size)
                        hello.getValue("tools").jsonArray.forEach { tool ->
                            val name = tool.jsonObject.getValue("name").jsonPrimitive.content
                            val readOnly = setOf("apps_list", "displays_list", "recent_tasks_list", "focused_app", "screenshot", "notifications_list")
                            val hint = tool.jsonObject["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull ?: false
                            assertEquals(name, name in readOnly, hint)
                            assertEquals("object", tool.jsonObject.getValue("inputSchema").jsonObject["type"]?.jsonPrimitive?.content)
                        }
                        send("""{"type":"ready","version":1}""")
                        connections++
                        if (connections > 1) {
                            reconnected.complete(Unit)
                            incoming.receive() // Keep connection alive until the bridge closes.
                            return@webSocket
                        }
                        suspend fun request(id: String, name: String, args: String): JsonObject {
                            send("""{"type":"call","id":"$id","name":"$name","arguments":$args}""")
                            val result = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                            assertEquals(id, result["id"]?.jsonPrimitive?.content)
                            return result.getValue("result").jsonObject
                        }
                        val invalid = request("1", "tap", """{"displayId":0,"x":null,"y":2}""")
                        assertTrue(invalid.getValue("isError").jsonPrimitive.boolean)
                        request("2", "tap", """{"displayId":0,"x":-1,"y":2,"extra":true}""")
                        val screenshot = request("3", "screenshot", """{"displayId":7}""")
                        assertEquals("image", screenshot.getValue("content").jsonArray[0].jsonObject["type"]?.jsonPrimitive?.content)
                        assertEquals("cG5n", screenshot.getValue("content").jsonArray[0].jsonObject["data"]?.jsonPrimitive?.content)
                        completed.complete(Unit)
                        // Returning closes this socket; the bridge must reconnect without replaying calls.
                    } catch (e: Throwable) {
                        completed.completeExceptionally(e)
                        reconnected.completeExceptionally(e)
                    }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val bridge = RemoteBridge(RemoteConfig(true, "ws://127.0.0.1:$port", "phone", "device-secret", "Test phone"), { println("Bridge status: $it") }) { name, _ ->
            calls.add(name)
            if (name == "screenshot") CallToolResult(content = listOf(ImageContent("cG5n", "image/png")))
            else textContent("OK")
        }
        try {
            bridge.start()
            withTimeout(10000) { completed.await(); reconnected.await() }
            assertEquals(listOf("tap", "screenshot"), calls.toList())
        } finally {
            bridge.close()
            server.stop(0, 1000)
        }
    }
}
