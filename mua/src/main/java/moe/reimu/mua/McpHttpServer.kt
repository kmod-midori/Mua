package moe.reimu.mua

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** SDK-owned MCP protocol and transport; application-owned authorization and display tools. */
internal class McpHttpServer(
    private val token: String,
    port: Int = 8765,
    address: String = "127.0.0.1",
    private val tokenProvider: () -> String = { token },
    private val execute: suspend (String, JsonObject) -> CallToolResult,
) {
    init {
        require(address in ListenerConfig.ADDRESSES)
        // Port zero is reserved for ephemeral listeners in transport tests.
        require(port == 0 || port in 1024..65535)
    }
    private val mcp = Server(
        serverInfo = Implementation(name = "mua", version = "1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
    ).apply {
        ToolCatalog.specs.forEach { spec ->
            addTool(name = spec.name, description = spec.description, inputSchema = spec.schema(),
                toolAnnotations = spec.annotations) { request ->
                try {
                    val args = request.arguments ?: JsonObject(emptyMap())
                    spec.validate(args)
                    execute(spec.name, args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CallToolResult(content = listOf(TextContent(e.message ?: "Display operation failed")), isError = true)
                }
            }
        }
    }
    private val engine = embeddedServer(CIO, host = address, port = port) {
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val supplied = call.request.headers[HttpHeaders.Authorization].orEmpty()
            if (!MessageDigest.isEqual(supplied.toByteArray(), "Bearer ${tokenProvider()}".toByteArray())) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
            }
        }
        mcpStatelessStreamableHttp(
            path = "/mcp",
            enableDnsRebindingProtection = false,
        ) { mcp }
    }

    val listeningPort: Int get() = runBlocking { engine.engine.resolvedConnectors().single().port }
    fun start() { engine.start(wait = false) }
    fun stop() {
        try { engine.stop(0, 1000) } finally { runBlocking { mcp.close() } }
    }
}
