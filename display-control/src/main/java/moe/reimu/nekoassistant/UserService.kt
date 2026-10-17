package moe.reimu.nekoassistant

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
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
import moe.reimu.displaycontrol.taskMoveWithRollback
import moe.reimu.nekoassistant.wrappers.ActivityManagerWrapper
import moe.reimu.nekoassistant.wrappers.FakeContext
import moe.reimu.nekoassistant.wrappers.ServiceManager
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
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
        check(displayManager().getDisplay(displayId)?.isValid == true) { "Unknown or inactive display: $displayId" }
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

    private val activityTaskManager by lazy {
        ServiceManager.getService("activity_task", "android.app.IActivityTaskManager")
    }

    @Suppress("UNCHECKED_CAST")
    private fun recentTasks(): List<ActivityManager.RecentTaskInfo> {
        val userId = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        val manager = activityTaskManager
        val slice = manager.javaClass.getMethod("getRecentTasks", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, Int.MAX_VALUE, 0, userId)
        return slice.javaClass.getMethod("getList").invoke(slice) as List<ActivityManager.RecentTaskInfo>
    }

    @Synchronized
    override fun getRecentTasksJson(): String = JSONArray().apply {
        val displayField = ActivityManager.RecentTaskInfo::class.java.getField("displayId")
        val userField = ActivityManager.RecentTaskInfo::class.java.getField("userId")
        val pm = FakeContext.getInstance().packageManager
        recentTasks().forEach { task ->
            val pkg = task.baseActivity?.packageName ?: task.baseIntent.component?.packageName
                ?: task.baseIntent.`package`
            val title = task.taskDescription?.label?.takeIf { it.isNotEmpty() } ?: pkg?.let {
                try {
                    val info = pm.javaClass.getMethod("getApplicationInfoAsUser", String::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(pm, it, 0, userField.getInt(task)) as ApplicationInfo
                    pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    // Removed/unavailable packages must not prevent listing the remaining tasks.
                    Log.w(TAG, "Could not load app label for $it", e)
                    null
                }
            }
            put(JSONObject().apply {
                put("taskId", task.taskId)
                put("title", title ?: JSONObject.NULL)
                put("displayId", displayField.getInt(task))
                put("isRunning", task.isRunning)
                put("baseActivity", task.baseActivity?.flattenToShortString() ?: JSONObject.NULL)
                put("topActivity", task.topActivity?.flattenToShortString() ?: JSONObject.NULL)
                put("package", pkg ?: JSONObject.NULL)
            })
        }
    }.toString()

    @Synchronized
    override fun moveRecentTaskToDisplay(taskId: Int, displayId: Int) {
        requireDisplay(displayId)
        require(Display::class.java.getMethod("getType").invoke(displayManager().getDisplay(displayId)) ==
            Display::class.java.getField("TYPE_VIRTUAL").getInt(null)) { "Destination must be a virtual display" }
        val task = recentTasks().firstOrNull { it.taskId == taskId }
            ?: error("Unknown recent task: $taskId. Refresh recent_tasks_list.")
        val pkg = task.topActivity?.packageName ?: task.baseIntent.component?.packageName
            ?: task.baseIntent.`package`
        val before = runningTasks()
        checkForegroundConflict(before, pkg, displayId, taskId)
        val sourceDisplayId = before.firstOrNull { it.taskId == taskId }?.let { taskDisplayIdField.getInt(it) }
            ?.takeIf { it >= 0 }
        val restoreDisplayId = sourceDisplayId ?: Display.DEFAULT_DISPLAY
        taskMoveWithRollback(restoreDisplayId, sourceDisplayId == null, moveAndCheck = {
            startRecentTask(taskId, displayId)
            checkForegroundConflict(runningTasks(), pkg, displayId, taskId)
        }, requestRestore = {
            // Moving the top task may reveal another foreground task of the same app underneath it.
            startRecentTask(taskId, restoreDisplayId)
        })
    }

    private fun startRecentTask(taskId: Int, displayId: Int) {
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        val manager = activityTaskManager
        val result = manager.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType,
            Bundle::class.java).invoke(manager, taskId, options.toBundle()) as Int
        check(result >= 0) { "Android rejected task move (result $result)" }
    }

    private val taskDisplayIdField by lazy { ActivityManager.RunningTaskInfo::class.java.getField("displayId") }
    private val taskFocusedField by lazy { ActivityManager.RunningTaskInfo::class.java.getField("isFocused") }
    private fun checkForegroundConflict(
        tasks: List<ActivityManager.RunningTaskInfo>, pkg: String?, displayId: Int, movingTaskId: Int? = null,
    ) {
        if (pkg == null) return
        val conflict = tasks.firstOrNull {
            it.taskId != movingTaskId && taskDisplayIdField.getInt(it) != displayId &&
                taskFocusedField.getBoolean(it) && it.topActivity?.packageName == pkg
        }
        check(conflict == null) { "App $pkg is in front on display ${taskDisplayIdField.getInt(conflict)}" }
    }
    private fun taskContainsPackage(task: ActivityManager.RunningTaskInfo, pkg: String) =
        task.baseActivity?.packageName == pkg || task.topActivity?.packageName == pkg ||
            task.baseIntent.component?.packageName == pkg || task.baseIntent.`package` == pkg

    @Synchronized
    override fun startActivityOnDisplay(displayId: Int, intent: Intent) {
        requireDisplay(displayId)
        val pkg = requireNotNull(intent.component?.packageName) { "Launch requires an explicit activity" }
        val tasks = runningTasks()
        checkForegroundConflict(tasks, pkg, displayId)
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
        checkForegroundConflict(runningTasks(), pkg, displayId)
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
