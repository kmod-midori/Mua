package moe.reimu.mua

import android.content.Context

internal object PushRegistration {
    fun schedule(context: Context, config: RemoteConfig = context.loadRemoteConfig()) = Unit
}
