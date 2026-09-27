package moe.reimu.nekoassistant

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ToolApprovalDetails(
    val title: String,
    val details: String,
    val permissions: List<String> = emptyList(),
)

data class ToolApprovalRequest(val id: String, val details: ToolApprovalDetails)

data class ToolApprovalResponse(val approved: Boolean, val message: String? = null)

/** One response belongs to exactly one request; cancellation never grants approval. */
internal class ToolApprovalGate(private val publish: (ToolApprovalRequest?) -> Unit) {
    private val mutex = Mutex()
    private var pending: Pair<ToolApprovalRequest, CompletableDeferred<ToolApprovalResponse>>? = null

    suspend fun request(details: ToolApprovalDetails): ToolApprovalResponse = mutex.withLock {
        val request = ToolApprovalRequest(UUID.randomUUID().toString(), details)
        val response = CompletableDeferred<ToolApprovalResponse>()
        synchronized(this) {
            pending = request to response
            publish(request)
        }
        try {
            response.await()
        } finally {
            synchronized(this) {
                pending = null
                publish(null)
            }
            response.cancel()
        }
    }

    @Synchronized
    fun respond(id: String, approved: Boolean, message: String? = null) {
        val response = ToolApprovalResponse(
            approved = approved,
            message = message?.trim()?.takeIf { !approved && it.isNotEmpty() },
        )
        pending?.takeIf { it.first.id == id }?.second?.complete(response)
    }
}
