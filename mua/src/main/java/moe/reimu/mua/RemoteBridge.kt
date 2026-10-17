package moe.reimu.mua

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Outbound transport only: validation and display execution are shared with local MCP. */
internal class RemoteBridge(
    private val config: RemoteConfig,
    private val status: (UiText) -> Unit,
    private val execute: suspend (String, JsonObject) -> CallToolResult,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) {
        followRedirects = false // Never forward a device credential to a redirect target.
        install(WebSockets) {
            pingIntervalMillis = 20000
            maxFrameSize = Long.MAX_VALUE
        }
    }
    private var job: Job? = null
    @Volatile private var closed = false

    fun start() {
        check(job == null) { "Bridge already started" }
        config.validate()
        job = scope.launch {
            var retryMillis = 1000L
            while (isActive) {
                report(UiText(R.string.connecting))
                var failure = ""
                try {
                    client.webSocket(config.endpoint(), request = {
                        header("Authorization", "Bearer ${config.token}")
                    }) {
                        send(buildJsonObject {
                            put("type", "hello"); put("version", 1); put("name", config.deviceName)
                            put("tools", buildJsonArray {
                                ToolCatalog.specs.forEach { spec -> add(buildJsonObject {
                                    put("name", spec.name); put("description", spec.description)
                                    put("inputSchema", McpJson.encodeToJsonElement(spec.schema()))
                                    spec.annotations?.let { put("annotations", McpJson.encodeToJsonElement(it)) }
                                }) }
                            })
                        }.toString())
                        val ready = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                        check(ready["type"]?.jsonPrimitive?.content == "ready" &&
                            ready["version"]?.jsonPrimitive?.int == 1) { "Unsupported bridge protocol" }
                        retryMillis = 1000L
                        report(UiText(R.string.connected))
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val request = Json.parseToJsonElement(frame.readText()).jsonObject
                            check(request["type"]?.jsonPrimitive?.content == "call") { "Expected call" }
                            val id = request.getValue("id").jsonPrimitive.content
                            val result = try {
                                val name = request.getValue("name").jsonPrimitive.content
                                val args = request.getValue("arguments").jsonObject
                                val spec = ToolCatalog.specs.firstOrNull { it.name == name }
                                    ?: error("Unknown tool: $name")
                                spec.validate(args)
                                execute(name, args)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                CallToolResult(content = listOf(io.modelcontextprotocol.kotlin.sdk.types.TextContent(
                                    e.message ?: "Display operation failed")), isError = true)
                            }
                            send(buildJsonObject {
                                put("type", "result"); put("id", id)
                                put("result", McpJson.encodeToJsonElement(result))
                            }.toString())
                        }
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // A closed WebSocket session may cancel its own request without stopping the bridge.
                } catch (e: Exception) {
                    // Class names aid diagnosis without exposing URLs, credentials, or response bodies.
                    failure = " (${e.javaClass.simpleName})"
                }
                report(UiText(R.string.retrying, listOf(failure, retryMillis / 1000)))
                delay(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30000)
            }
        }
    }

    @Synchronized
    private fun report(value: UiText) { if (!closed) status(value) }

    @Synchronized
    override fun close() {
        closed = true
        scope.cancel()
        client.close()
        status(UiText(R.string.stopped))
    }
}
