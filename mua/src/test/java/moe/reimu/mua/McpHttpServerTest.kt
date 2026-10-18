package moe.reimu.mua

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class McpHttpServerTest {
    @Test fun localHttpTransportEnforcesAccessAndHandlesMcpMessages() {
        verifyTransport("127.0.0.1")
    }

    @Test fun allInterfacesTransportAcceptsDeviceIpAndStillEnforcesAccess() {
        verifyTransport("0.0.0.0")
    }

    private fun verifyTransport(address: String) {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val token = java.util.concurrent.atomic.AtomicReference("test-token")
        val server = McpHttpServer("test-token", 0, address, tokenProvider = { token.get() }) { name, _ ->
            calls.add(name)
            if (name == "screenshot") error("Shizuku disconnected")
            textContent("OK")
        }
        val deviceIp = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
        val connectHost = if (address == "0.0.0.0") deviceIp else "127.0.0.1"
        server.start()
        try {
            fun call(body: String? = """{"jsonrpc":"2.0","id":1,"method":"ping"}""", token: String = "test-token", origin: String? = null, version: String? = null, host: String = connectHost): Pair<Int, String> {
                return Socket(connectHost, server.listeningPort).use { socket ->
                    socket.soTimeout = 5000
                    val payload = body?.toByteArray() ?: byteArrayOf()
                    val headers = buildString {
                        append("${if (body == null) "GET" else "POST"} /mcp HTTP/1.1\r\n")
                        append("Host: $host:${server.listeningPort}\r\nAuthorization: Bearer $token\r\n")
                        append("Accept: application/json, text/event-stream\r\nContent-Type: application/json\r\n")
                        append("Connection: close\r\nContent-Length: ${payload.size}\r\n")
                        origin?.let { append("Origin: $it\r\n") }
                        version?.let { append("MCP-Protocol-Version: $it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().write(headers.toByteArray() + payload)
                    val response = socket.getInputStream().bufferedReader().readText()
                    response.substringBefore("\r\n").split(" ")[1].toInt() to response.substringAfter("\r\n\r\n")
                }
            }
            assertEquals(200, call(host = "custom.example").first)
            assertEquals(200, call(origin = "http://$connectHost:${server.listeningPort}").first)
            assertEquals(401, call(token = "wrong").first)
            assertEquals(200, call(origin = "https://custom.example").first)
            assertEquals(401, call(token = "wrong", host = "custom.example", origin = "https://custom.example").first)
            assertEquals(400, call(version = "unsupported").first)
            assertEquals(405, call(body = null).first)
            val initialized = call("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
            assertEquals(200, initialized.first)
            assertTrue(initialized.second.contains("\"name\":\"mua\""))
            val discovered = call("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
            assertEquals(200, discovered.first)
            ToolCatalog.specs.forEach { assertTrue(discovered.second.contains("\"name\":\"${it.name}\"")) }
            val body = discovered.second.let { it.substring(it.indexOf('{'), it.lastIndexOf('}') + 1) }
            val tools = Json.parseToJsonElement(body).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
            val readOnly = setOf("apps_list", "displays_list", "recent_tasks_list", "focused_app", "screenshot", "notifications_list")
            tools.forEach { entry ->
                val tool = entry.jsonObject
                val name = tool.getValue("name").jsonPrimitive.content
                val hint = tool["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull ?: false
                assertEquals(name, name in readOnly, hint)
            }
            val tap = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"tap","arguments":{"displayId":7,"x":1,"y":2}}}""")
            assertTrue(tap.second.contains("\"text\":\"OK\""))
            val invalid = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap","arguments":{"displayId":7,"x":null,"y":2}}}""")
            assertTrue(invalid.second.contains("\"isError\":true"))
            assertEquals(listOf("tap"), calls.toList())
            val failed = call("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"screenshot","arguments":{"displayId":7}}}""")
            assertTrue(failed.second.contains("Shizuku disconnected"))
            assertTrue(failed.second.contains("\"isError\":true"))
            val ping = call()
            assertEquals(200, ping.first)
            assertTrue(ping.second.contains("\"result\":{}"))
            assertEquals(202, call("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
            assertTrue(call("{").second.contains("-32700"))
            token.set("replacement-token")
            assertEquals(401, call(token = "test-token").first)
            assertEquals(200, call(token = "replacement-token").first)
        } finally { server.stop() }
    }
}
