package moe.reimu.mua

import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PushRegistrationClientTest {
    @Test fun registersRefreshesClearsAndDoesNotForwardCredentialsToRedirects() = runBlocking {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                put("/devices/phone/push") {
                    assertEquals("Bearer secret", call.request.headers["Authorization"])
                    requests.add(Json.parseToJsonElement(call.receiveText()).jsonObject)
                    call.respond("OK")
                }
                put("/redirect") { call.respondRedirect("/devices/phone/push") }
            }
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            for (token in listOf("first", "rotated", null)) {
                assertEquals(200, PushRegistrationClient.register("$base/devices/phone/push", "secret", "Phone", token))
            }
            assertEquals(listOf(JsonPrimitive("first"), JsonPrimitive("rotated"), JsonNull), requests.map { it["token"] })
            assertEquals(302, PushRegistrationClient.register("$base/redirect", "secret", "Phone", "token"))
            assertEquals(3, requests.size)
        } finally { server.stop(0, 1000) }
    }
}
