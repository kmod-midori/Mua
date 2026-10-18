package moe.reimu.displaycontrol

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Process
import android.util.Log
import moe.reimu.nekoassistant.IUserService
import java.util.concurrent.Executors

/** Grants/revokes access off main; the privileged side also watches the host Binder lifetime. */
class AccessibilityAccess(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val component = ComponentName(app, AgentTextAccessibilityService::class.java)
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
        if (remote == null) AgentTextAccessibilityService.setActive(owner, false)
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
                AgentTextAccessibilityService.setActive(owner, true)
            }
            current = remote
            remote.setAccessibilityServiceAccess(component.flattenToString(), userId, owner, true)
            synchronized(gate) {
                if (closed || desired !== remote) {
                    AgentTextAccessibilityService.setActive(owner, false)
                }
            }
        } catch (e: Exception) {
            AgentTextAccessibilityService.setActive(owner, false)
            revoke()
            Log.w("AccessibilityAccess", "Could not enable accessibility service", e)
        }
    }

    private fun revoke() {
        current?.let { remote ->
            try { remote.setAccessibilityServiceAccess(component.flattenToString(), userId, owner, false) }
            catch (e: Exception) { Log.w("AccessibilityAccess", "Could not revoke accessibility service access", e) }
        }
        current = null
    }

    override fun close(): Unit = synchronized(gate) {
        if (closed) return
        closed = true
        desired = null
        AgentTextAccessibilityService.setActive(owner, false)
        worker.execute { revoke() }
        worker.shutdown()
    }
}
