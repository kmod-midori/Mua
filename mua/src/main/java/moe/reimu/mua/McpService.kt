package moe.reimu.mua

import android.annotation.SuppressLint
import android.os.PowerManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import moe.reimu.displaycontrol.ShizukuDisplayConnection
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

class McpService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val controlDispatcher = worker.asCoroutineDispatcher()
    private lateinit var display: DisplayTools
    private lateinit var connection: ShizukuDisplayConnection
    private var server: McpHttpServer? = null
    private var bridge: RemoteBridge? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationBuilder: Notification.Builder
    @Volatile private var destroying = false
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (!destroying) worker.execute {
                if (!destroying) try { display.refreshDisplays() }
                catch (e: Exception) { android.util.Log.w("MuaMcp", "Could not refresh displays", e) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val listener = loadListenerConfig()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mcp", "mua server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        notificationBuilder = Notification.Builder(this, "mcp")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("mua is running")
            .setSubText("MCP ${listener.address}:${listener.port}").setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true)
        displayPowerStatus = DisplayPowerStatus()
        startForeground(1, buildNotification())
        try {
            display = DisplayTools(applicationContext, ::setDisplayCount)
            getSystemService(android.hardware.display.DisplayManager::class.java)
                .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            connection = ShizukuDisplayConnection(this, BuildConfig.DEBUG, BuildConfig.VERSION_CODE, { remote ->
                worker.execute {
                    display.connected(remote)
                    if (!destroying) status = if (remote == null) "Waiting for Shizuku permission/connection" else "Shizuku connected"
                }
            }, { error -> if (!destroying) status = error.message ?: "Shizuku connection failed" })
            status = "Waiting for Shizuku permission/connection"
            connection.start()
            server = McpHttpServer(accessToken(this), listener.port, listener.address,
                tokenProvider = { accessToken(this) }) { name, args ->
                withContext(controlDispatcher) { display.execute(name, args) }
            }
            server?.start()
            val remoteConfig = loadRemoteConfig()
            remoteStatus = if (remoteConfig.enabled) "Connecting" else "Disabled"
            if (remoteConfig.enabled) {
                bridge = RemoteBridge(remoteConfig,
                    { remoteStatus = it }) { name, args ->
                    withContext(controlDispatcher) {
                        check(!destroying) { "mua is stopping" }
                        display.execute(name, args)
                    }
                }.also { it.start() }
            }
            running = true
            previewService = this
        } catch (e: Exception) {
            destroying = true
            android.util.Log.e("MuaMcp", "Server startup failed", e)
            status = "Server failed: ${e.message}"
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf() else if (!destroying) connection.connect()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    // Display callbacks run on the control executor; destruction runs on main.
    // Serialize ownership and prevent queued display creation from reacquiring after shutdown.
    // No timeout: a live display needs CPU access until it is stopped/disconnected.
    @SuppressLint("WakelockTimeout", "Wakelock") // Released by the inactive callback or onDestroy, not on method return.
    @Synchronized
    private fun setDisplayCount(count: Int) {
        if (count > 0 && !destroying) {
            if (cpuWakeLock?.isHeld != true) {
                cpuWakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mua:McpServer").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
            cpuWakeLock = null
        }
        if (!destroying) {
            displayPowerStatus = DisplayPowerStatus(count, cpuWakeLock?.isHeld == true)
            // Notification failures must not interrupt display/resource cleanup.
            try {
                getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            } catch (e: Exception) {
                android.util.Log.w("MuaMcp", "Could not update display status notification", e)
            }
        }
    }

    private fun buildNotification(): Notification {
        val text = displayPowerStatus.summary
        return notificationBuilder.setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).build()
    }

    override fun onDestroy() {
        destroying = true
        if (previewService === this) previewService = null
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener)
        running = false
        setDisplayCount(0)
        displayPowerStatus = DisplayPowerStatus()
        try {
            bridge?.close()
            server?.stop()
        } finally {
            try {
                // Remove callbacks before retiring the worker; queued operations finish before capture cleanup.
                try {
                    if (::connection.isInitialized) connection.close()
                } finally {
                    worker.execute {
                        try { if (::display.isInitialized) display.close() }
                        catch (e: Exception) { android.util.Log.w("MuaMcp", "Display cleanup failed", e) }
                    }
                    worker.shutdown()
                }
            } finally {
                setDisplayCount(0)
                super.onDestroy()
            }
        }
    }

    companion object {
        // Accessed only from main; capture work stays on the serialized control executor.
        private var previewService: McpService? = null
        internal fun requestPreviews(callback: (List<DisplayPreview>) -> Unit) {
            val service = previewService
            if (service == null || service.destroying) {
                callback(emptyList())
                return
            }
            service.worker.execute {
                val previews = try {
                    if (service.destroying) emptyList() else service.display.previews()
                } catch (e: Exception) {
                    android.util.Log.w("MuaMcp", "Could not capture previews", e)
                    emptyList()
                }
                Handler(Looper.getMainLooper()).post {
                    callback(if (service.destroying) emptyList() else previews)
                }
            }
        }
        @Volatile internal var displayPowerStatus = DisplayPowerStatus()
            private set
        @Volatile var remoteStatus = "Stopped"
            private set
        @Volatile var status = "Stopped"
            private set
        @Volatile var running = false
            private set
        @Synchronized fun accessToken(context: Context): String {
            val prefs = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
            return prefs.getString("token", null) ?: resetAccessToken(context)
        }
        @Synchronized fun resetAccessToken(context: Context): String {
            // 128 random bits in 22 URL-safe characters, without padding.
            val token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            context.getSharedPreferences("mcp", Context.MODE_PRIVATE).edit { putString("token", token) }
            return token
        }
    }
}
