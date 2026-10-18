package moe.reimu.displaycontrol

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Process
import android.service.notification.NotificationListenerService
import android.util.Log
import moe.reimu.nekoassistant.IUserService
import java.util.concurrent.Executors

/** Grants/revokes access off main; the privileged side also watches the host Binder lifetime. */
class NotificationAccess(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val component = ComponentName(app, AgentNotificationListener::class.java)
    private val owner = Binder()
    private val userId = android.os.UserHandle.getUserHandleForUid(Process.myUid()).hashCode()
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var desired: IUserService? = null
    private var current: IUserService? = null
    fun connected(remote: IUserService?) = synchronized(gate) {
        if (closed) return@synchronized
        desired = remote
        if (remote == null) AgentNotificationListener.setActive(app, owner, false)
        worker.execute { update(remote) }
    }

    // Only this executor owns current; gate protects activation against close/disconnect.
    private fun update(remote: IUserService?) {
        if (closed || desired !== remote) return
        revoke()
        if (remote == null) return
        try {
            synchronized(gate) {
                if (closed || desired !== remote) return
                AgentNotificationListener.setActive(app, owner, true)
            }
            current = remote
            remote.setNotificationListenerAccess(component.flattenToString(), userId, owner, true)
            synchronized(gate) {
                if (!closed && desired === remote) {
                    NotificationListenerService.requestRebind(component)
                } else {
                    AgentNotificationListener.setActive(app, owner, false)
                }
            }
        } catch (e: Exception) {
            AgentNotificationListener.setActive(app, owner, false)
            revoke()
            Log.w("NotificationAccess", "Could not enable listener", e)
        }
    }

    private fun revoke() {
        current?.let { remote ->
            try { remote.setNotificationListenerAccess(component.flattenToString(), userId, owner, false) }
            catch (e: Exception) { Log.w("NotificationAccess", "Could not revoke listener access", e) }
        }
        current = null
    }

    override fun close(): Unit = synchronized(gate) {
        if (closed) return
        closed = true
        desired = null
        AgentNotificationListener.setActive(app, owner, false)
        worker.execute { revoke() }
        worker.shutdown()
    }
}
