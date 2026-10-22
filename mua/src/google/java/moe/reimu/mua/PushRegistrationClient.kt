package moe.reimu.mua

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PushRegistrationClient {
    suspend fun register(endpoint: String, credential: String, name: String, token: String?): Int =
        HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        }.use { client ->
            client.put(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(buildJsonObject {
                    put("name", name)
                    put("token", token?.let(::JsonPrimitive) ?: JsonNull)
                }.toString())
            }.status.value
        }
}
