package moe.reimu.displaycontrol

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import moe.reimu.nekoassistant.IUserService
import moe.reimu.nekoassistant.UserService
import rikka.shizuku.Shizuku

/** Call lifecycle methods on the main thread. Hosts own permission prompts and disconnect policy. */
class ShizukuDisplayConnection(
    context: Context,
    debug: Boolean,
    version: Int,
    private val onChanged: (IUserService?) -> Unit,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, UserService::class.java.name))
        .daemon(false).processNameSuffix("display").debuggable(debug).version(version)
    private var registered = false
    private var bound = false
    private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!closed) onChanged(IUserService.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) {
            releaseBinding(false)
            if (!closed) onChanged(null)
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }
    private val received = Shizuku.OnBinderReceivedListener { connect() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> connect() }
    private val dead = Shizuku.OnBinderDeadListener {
        releaseBinding(false)
        if (!closed) onChanged(null)
    }

    fun start() {
        check(!closed)
        if (registered) return
        registered = true
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addBinderReceivedListenerSticky(received)
    }

    fun connect() {
        if (closed || bound) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            bound = true
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            releaseBinding(false)
            onError(e)
        }
    }

    private fun releaseBinding(remove: Boolean) {
        if (!bound) return
        bound = false
        try {
            if (remove) Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) {
            onError(e)
        } finally {
            try { Shizuku.unbindUserService(args, connection, false) } catch (e: Exception) { onError(e) }
        }
    }

    override fun close() {
        closed = true
        if (registered) {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
            registered = false
        }
        releaseBinding(true)
        onChanged(null)
    }
}
