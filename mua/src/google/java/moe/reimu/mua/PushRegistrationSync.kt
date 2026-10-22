package moe.reimu.mua

internal data class PushRegistrationState(val credential: String, val name: String, val enabled: Boolean)

/** Return null when settings changed during retrieval, so the durable job retries. */
internal suspend fun syncPushRegistration(
    state: () -> PushRegistrationState,
    refreshToken: suspend () -> String,
    upload: suspend (PushRegistrationState, String?) -> Int,
): Int? {
    val before = state()
    val token = if (before.enabled) refreshToken() else null
    if (state() != before) return null
    return upload(before, token)
}
