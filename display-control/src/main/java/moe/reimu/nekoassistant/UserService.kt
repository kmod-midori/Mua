package moe.reimu.nekoassistant

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.IInterface
import android.os.SystemClock
import android.util.Log
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import moe.reimu.nekoassistant.wrappers.ActivityManagerWrapper
import moe.reimu.nekoassistant.wrappers.FakeContext
import moe.reimu.nekoassistant.wrappers.ServiceManager
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.exitProcess

@Keep
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class UserService : IUserService.Stub() {
    private val displays = linkedMapOf<Int, VirtualDisplay>()
    private var defaultDisplayId: Int? = null

    @Synchronized
    override fun destroy() {
        Log.i(TAG, "destroy")

        try {
            displays.keys.toList().forEach { id ->
                try { releaseDisplay(id) } catch (e: Exception) { Log.w(TAG, "Failed to release display $id", e) }
            }
        } finally { exitProcess(0) }
    }

    @Synchronized
    override fun startDisplay(
        width: Int, height: Int, dpi: Int, surface: Surface?
    ): Boolean {
        Log.i(TAG, "startDisplay $width x $height @ $dpi DPI")

        if (width <= 0 || height <= 0 || dpi <= 0 || surface == null) {
            Log.e(TAG, "startDisplay called with invalid dimensions or null surface")
            return false
        }

        stopDisplay()

        val id = createVirtualDisplay(width, height, dpi, surface)
        defaultDisplayId = id.takeIf { it >= 0 }
        return id >= 0
    }

    @Synchronized
    override fun createDisplay(surface: Surface?): Int {
        val metrics = getMainDisplayMetrics()
        return createVirtualDisplay(metrics[0], metrics[1], metrics[2], surface)
    }

    private fun displayManager(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(FakeContext.getInstance())
    }

    @Suppress("DEPRECATION")
    @Synchronized
    override fun getMainDisplayMetrics(): IntArray {
        val main = checkNotNull(displayManager().getDisplay(Display.DEFAULT_DISPLAY)) { "Main display unavailable" }
        val metrics = DisplayMetrics()
        main.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface?): Int {
        require(width > 0 && height > 0 && dpi > 0 && surface != null) {
            "Invalid display dimensions, density or surface"
        }
        require(width.toLong() * height <= 8_388_608) { "Display must be at most 8 megapixels" }
        getDisplayIds() // Discard displays removed externally before applying resource limits.
        check(displays.size < 4) { "At most 4 virtual displays may be active" }
        val pixels = displays.values.sumOf { it.display.mode.let { mode -> mode.physicalWidth.toLong() * mode.physicalHeight } }
        check(pixels + width.toLong() * height <= 16_777_216) { "Active displays must total at most 16 megapixels" }
        var flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS)
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP)
        }

        try {
            val displayManager = displayManager()

            @SuppressLint("WrongConstant")
            val virtualDisplay = displayManager.createVirtualDisplay(
                "NekoAssistant",
                width, height,
                dpi, surface, flags
            )

            Log.i(TAG, "Created virtual display $virtualDisplay")

            try {
                ServiceManager.windowManager.setDisplayImePolicy(
                    virtualDisplay.display.displayId,
                    2, /* DISPLAY_IME_POLICY_HIDE */
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set DISPLAY_IME_POLICY_HIDE", e)
            }

            val id = virtualDisplay.display.displayId
            displays[id] = virtualDisplay
            return id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create display", e)
            return -1
        }
    }

    @Synchronized
    override fun stopDisplay(): Boolean {
        defaultDisplayId?.let { releaseDisplay(it) }
        return true
    }

    @Synchronized
    override fun releaseDisplay(displayId: Int): Boolean {
        val display = displays[displayId] ?: return false
        display.release()
        displays.remove(displayId)
        if (defaultDisplayId == displayId) defaultDisplayId = null
        return true
    }

    @Synchronized
    override fun getDisplayIds(): IntArray {
        displays.filterValues { !it.display.isValid }.keys.toList().forEach { releaseDisplay(it) }
        return displays.keys.toIntArray()
    }

    private fun requireDisplay(displayId: Int) {
        check(displays[displayId]?.display?.isValid == true) { "Unknown or inactive virtual display: $displayId" }
    }

    private fun getGetTasksMethod(manager: IInterface): Method {
        return manager::class.java.getMethod("getTasks", Int::class.javaPrimitiveType)
    }

    @Synchronized
    override fun startActivity(
        intent: Intent,
        forceStop: Boolean
    ) {
        val options = ActivityOptions.makeBasic()
        val displayId = defaultDisplayId ?: error("No virtual display")
        options.launchDisplayId = displayId

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            val am = ActivityManagerWrapper.get()
            am.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startActivity $intent", e)
            throw IllegalStateException("Failed to launch activity", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runningTasks(): List<ActivityManager.RunningTaskInfo> {
        val am = ServiceManager.activityManager
        // Query Android on every launch; no cached app ownership or launch registry.
        return getGetTasksMethod(am).invoke(am, Int.MAX_VALUE) as List<ActivityManager.RunningTaskInfo>
    }

    private val taskDisplayIdField by lazy { ActivityManager.RunningTaskInfo::class.java.getField("displayId") }
    private val taskFocusedField by lazy { ActivityManager.RunningTaskInfo::class.java.getField("isFocused") }
    private fun taskContainsPackage(task: ActivityManager.RunningTaskInfo, pkg: String) =
        task.baseActivity?.packageName == pkg || task.topActivity?.packageName == pkg ||
            task.baseIntent.component?.packageName == pkg || task.baseIntent.`package` == pkg

    @Synchronized
    override fun startActivityOnDisplay(displayId: Int, intent: Intent) {
        requireDisplay(displayId)
        val pkg = requireNotNull(intent.component?.packageName) { "Launch requires an explicit activity" }
        val tasks = runningTasks()
        val conflict = tasks.firstOrNull { taskContainsPackage(it, pkg) && taskDisplayIdField.getInt(it) != displayId }
        check(conflict == null) { "App $pkg already has a task on display ${taskDisplayIdField.getInt(conflict)}" }
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        val existing = tasks.firstOrNull { taskContainsPackage(it, pkg) && taskDisplayIdField.getInt(it) == displayId }
        val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (existing != null) {
            ActivityOptions::class.java.getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                .invoke(options, existing.taskId)
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        ActivityManagerWrapper.get().startActivity(launch, options.toBundle())
        // Keep launch/check atomic for this Binder service until Android publishes the task.
        // A failed query or unconfirmed launch fails closed rather than recording guessed ownership.
        val deadline = SystemClock.uptimeMillis() + 3000
        do {
            val current = runningTasks().filter { taskContainsPackage(it, pkg) }
            check(current.none { taskDisplayIdField.getInt(it) != displayId }) { "App $pkg appeared on another display during launch" }
            if (current.any { taskDisplayIdField.getInt(it) == displayId }) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Android did not confirm a task for $pkg on display $displayId")
    }

    @Synchronized
    override fun getFocusedPackageName(): String? {
        return try { defaultDisplayId?.let { getFocusedPackageOnDisplay(it) } }
        catch (e: Exception) {
            Log.e(TAG, "Failed to get focused package", e)
            null
        }
    }

    @Synchronized
    override fun getFocusedPackageOnDisplay(displayId: Int): String? {
        requireDisplay(displayId)
        return runningTasks().firstOrNull {
            taskDisplayIdField.getInt(it) == displayId && taskFocusedField.getBoolean(it)
        }?.topActivity?.packageName
    }

    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private val injectInputMethod by lazy {
        InputManager::class.java.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }

    val inputManager by lazy {
        FakeContext.getInstance().getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    @Synchronized
    override fun injectInputEvent(input: InputEvent): Boolean {
        return defaultDisplayId?.let { injectInputEventOnDisplay(it, input) } ?: false
    }

    @Synchronized
    override fun injectInputEventOnDisplay(displayId: Int, input: InputEvent): Boolean {
        requireDisplay(displayId)
        try {
            setDisplayIdMethod.invoke(input, displayId)
            Log.d(TAG, "injectInputEvent $input")
            return injectInputMethod.invoke(inputManager, input, 2) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event $input", e)
            return false
        }
    }

    private fun runInputCommand(displayId: Int, command: String): Boolean {
        requireDisplay(displayId)

        val runtime = Runtime.getRuntime()
        val fullCommand = "input -d $displayId $command"
        Log.i(TAG, "Execute input command: $fullCommand")
        val process = runtime.exec(fullCommand)

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }

        return process.waitFor() == 0
    }

    private fun makeTapEvent(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent = MotionEvent.obtain(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        x,
        y,
        1.0f,
        0.05f, 0, 1.0f, 1.0f, 0, 0
    ).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }


    @Synchronized
    override fun injectTapEvent(x: Float, y: Float): Boolean =
        defaultDisplayId?.let { injectTapOnDisplay(it, x, y) } ?: false

    @Synchronized
    override fun injectTapOnDisplay(displayId: Int, x: Float, y: Float): Boolean {
        requireDisplay(displayId)
        try {
            val downTime = SystemClock.uptimeMillis()

            val eventDown = makeTapEvent(downTime, MotionEvent.ACTION_DOWN, x, y)
            val downInjected = try { injectInputEventOnDisplay(displayId, eventDown) } finally { eventDown.recycle() }
            if (!downInjected) {
                Log.e(TAG, "Failed to inject ACTION_DOWN event at ($x, $y)")
                return false
            }

            Thread.sleep(Random.nextLong(180, 220))

            val eventUp = makeTapEvent(downTime, MotionEvent.ACTION_UP, x, y)
            val upInjected = try { injectInputEventOnDisplay(displayId, eventUp) } finally { eventUp.recycle() }
            if (!upInjected) {
                Log.e(TAG, "Failed to inject ACTION_UP event at ($x, $y)")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "injectTapEvent($x, $y) failed", e)
            return false
        }
    }

    @Synchronized
    override fun injectKeyPressEvent(keyCode: Int): Boolean {
        return defaultDisplayId?.let { injectKeyOnDisplay(it, keyCode) } ?: false
    }

    @Synchronized
    override fun injectSwipeEvent(
        x1: Float, y1: Float, x2: Float, y2: Float, duration: Long
    ): Boolean {
        return defaultDisplayId?.let { injectSwipeOnDisplay(it, x1, y1, x2, y2, duration) } ?: false
    }

    @Synchronized
    override fun injectKeyOnDisplay(displayId: Int, keyCode: Int): Boolean =
        runInputCommand(displayId, "keyevent $keyCode")

    @Synchronized
    override fun injectSwipeOnDisplay(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean =
        runInputCommand(displayId, "swipe $x1 $y1 $x2 $y2 $duration")

    companion object {
        private const val TAG = "UserService"


        // Internal fields copied from android.hardware.display.DisplayManager
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
