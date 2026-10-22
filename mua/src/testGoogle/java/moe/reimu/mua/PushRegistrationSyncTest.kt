package moe.reimu.mua

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PushRegistrationSyncTest {
    @Test fun everySyncRetrievesCurrentTokenAndUploadsEvenWhenUnchanged() = runBlocking {
        val state = PushRegistrationState("credential", "Phone", true)
        var retrievals = 0
        val uploads = mutableListOf<String?>()
        repeat(2) {
            assertEquals(200, syncPushRegistration({ state }, { retrievals++; "current-token" }) { _, token ->
                uploads.add(token)
                200
            })
        }
        assertEquals(2, retrievals)
        assertEquals(listOf("current-token", "current-token"), uploads)
    }

    @Test fun disabledWakeClearsServerWithoutContactingFirebase() = runBlocking {
        val state = PushRegistrationState("credential", "Phone", false)
        assertEquals(200, syncPushRegistration({ state }, { error("Must not fetch") }) { _, token ->
            assertNull(token)
            200
        })
    }

    @Test fun optOutDuringRetrievalDoesNotUploadToken() = runBlocking {
        var state = PushRegistrationState("credential", "Phone", true)
        val result = syncPushRegistration({ state }, {
            state = state.copy(enabled = false)
            "new-token"
        }) { _, _ -> error("Must retry with the disabled state") }
        assertNull(result)
    }

    @Test fun changedCredentialsDuringRetrievalAreNotUsedForOldRequest() = runBlocking {
        var state = PushRegistrationState("old", "Phone", true)
        assertNull(syncPushRegistration({ state }, {
            state = state.copy(credential = "new")
            "token"
        }) { _, _ -> error("Must retry with new credentials") })
    }
}
