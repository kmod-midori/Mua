package moe.reimu.nekoassistant.ai.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.reimu.nekoassistant.ToolApprovalDetails
import moe.reimu.nekoassistant.TermuxCommandRunner
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.reimu.nekoassistant.AgentService
import moe.reimu.nekoassistant.ai.ActionResult

@Serializable
sealed class Action {
    open fun approval(): ToolApprovalDetails? = null

    suspend fun execute(service: AgentService): ActionResult {
        approval()?.let {
            val response = service.awaitToolApproval(it)
            if (!response.approved) {
                return ActionResult.retry(buildString {
                    append("用户拒绝执行此工具，工具未执行。请尊重该决定，不要通过其他工具执行同一操作。")
                    response.message?.let { message ->
                        append("\n用户对下一步的指示：\n")
                        append(message)
                    }
                })
            }
        }
        currentCoroutineContext().ensureActive()
        return executeApproved(service)
    }

    protected abstract suspend fun executeApproved(service: AgentService): ActionResult
}

/** Metadata and argument decoding live beside the action that implements each tool. */
open class ToolDefinition<out T : Action>(
    val name: String,
    val description: String,
    val parameters: Parameters,
    val decode: (Json, String) -> T,
) {
    val definition: Tool
        get() = Tool.function(name = name, description = description, parameters = parameters)
}

internal fun objectParameters(vararg props: Pair<String, String>): Parameters =
    Parameters.buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            props.forEach { (name, type) ->
                putJsonObject(name) { put("type", type) }
            }
        }
        putJsonArray("required") {
            props.forEach { (name, _) -> add(JsonPrimitive(name)) }
        }
    }

/** Register each tool once, for both the advertised schema and argument decoding. */
object ToolRegistry {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = listOf(
        SearchApp, ListApps, Launch, Tap, Type, Swipe,
        LongPress, DoubleTap, TakeOver, BackTool, Wait, Finish, TermuxShell,
    )
    private val toolsByName = tools.associateBy { it.name }

    val definitions = tools.map { it.definition }

    fun decode(name: String, arguments: String): Action? =
        toolsByName[name]?.decode(json, arguments)
}

@Serializable
@SerialName("SearchApp")
data class SearchApp(val query: String) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        if (query.isBlank()) {
            return ActionResult.retry("SearchApp 的 query 不能为空")
        }
        val packageManager = service.packageManager
        val installedApps =
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val matches = installedApps.mapNotNull { appInfo ->
            val name = packageManager.getApplicationLabel(appInfo).toString()
            val pkg = appInfo.packageName
            if (name.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)) {
                "$name ($pkg)"
            } else {
                null
            }
        }
        return if (matches.isEmpty()) {
            ActionResult.retry("没有找到匹配 \"$query\" 的应用，请尝试其他关键词")
        } else {
            ActionResult.success("匹配的应用：\n" + matches.joinToString("\n"))
        }
    }

    companion object : ToolDefinition<SearchApp>(
        name = "SearchApp",
        description = "按名称搜索已安装应用，返回应用的名称和包名，可能返回多个结果。query 不能为空。",
        parameters = objectParameters("query" to "string"),
        decode = { json, arguments -> json.decodeFromString(SearchApp.serializer(), arguments) },
    )
}

@Serializable
@SerialName("ListApps")
data class ListApps(val page: Int = 0) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val packageManager = service.packageManager
        // An activity answering the launcher intent is what puts an icon in the launcher,
        // so that is the set of apps a user would call launchable. One entry per package:
        // an app can declare several of them.
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                packageName to info.loadLabel(packageManager).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }

        if (apps.isEmpty()) {
            return ActionResult.retry("没有找到可启动的应用")
        }

        val currentPage = page.coerceAtLeast(0)
        val pageCount = (apps.size + PAGE_SIZE - 1) / PAGE_SIZE
        val from = currentPage * PAGE_SIZE
        if (from >= apps.size) {
            return ActionResult.retry("没有第 $currentPage 页，共 $pageCount 页（page 从 0 开始）")
        }

        val listing = apps
            .drop(from)
            .take(PAGE_SIZE)
            .joinToString("\n") { (packageName, label) -> "$label ($packageName)" }

        val continuation = if (from + PAGE_SIZE < apps.size) {
            "还有下一页：page=${currentPage + 1}"
        } else {
            "已是最后一页"
        }

        return ActionResult.success(
            "可启动的应用（第 $currentPage 页，共 $pageCount 页，${apps.size} 个）：\n" +
                "$listing\n$continuation"
        )
    }

    companion object : ToolDefinition<ListApps>(
        name = "ListApps",
        description = "列出可启动的应用，返回应用名称和包名，每页 50 个。" +
            "page 从 0 开始，第一次调用传 page=0，返回值会说明是否有下一页。当 SearchApp 找不到或需要浏览全部应用时使用。",
        parameters = objectParameters("page" to "integer"),
        decode = { json, arguments -> json.decodeFromString(ListApps.serializer(), arguments) },
    ) {
        private const val PAGE_SIZE = 50
    }
}

@Serializable
@SerialName("Launch")
data class Launch(val packageName: String) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            Log.i(TAG, "Launching app: $packageName")
            service.startActivityRemote(launchIntent)
            delay(5000)
            ActionResult.success()
        } else {
            Log.e(TAG, "No launch intent found for package: $packageName")
            ActionResult.retry("找不到包名 $packageName 对应的应用，请先调用 SearchApp 搜索获取正确包名")
        }
    }

    companion object : ToolDefinition<Launch>(
        name = "Launch",
        description = "按包名启动目标应用（不是显示名称）。这是启动应用的唯一方式，如果不知道包名，需先通过 SearchApp 或 ListApps 获取，不要尝试用其他方法打开应用。",
        parameters = objectParameters("packageName" to "string"),
        decode = { json, arguments -> json.decodeFromString(Launch.serializer(), arguments) },
    ) {
        private const val TAG = "Action.Launch"
    }
}

@Serializable
@SerialName("Tap")
data class Tap(val x: Float, val y: Float, val message: String? = null) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val x = service.mapXToScreen(x)
            ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
        val y = service.mapYToScreen(y)
            ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

        if (!service.injectTapEvent(x, y)) {
            Log.e(TAG, "Failed to inject tap event at ($x, $y)")
            return ActionResult.fail()
        }

        return ActionResult.success()
    }

    companion object : ToolDefinition<Tap>(
        name = "Tap",
        description = "点击屏幕上的特定点，用于点击按钮、选择项目或与任何可点击的 UI 元素交互。",
        parameters = objectParameters("x" to "number", "y" to "number"),
        decode = { json, arguments -> json.decodeFromString(Tap.serializer(), arguments) },
    ) {
        private const val TAG = "Action.Tap"
    }
}

@Serializable
@SerialName("Type")
data class Type(val text: String) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val cm = service.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(null, text))

        delay(Random.nextLong(180, 220))

        service.injectKeyPressEvent(KeyEvent.KEYCODE_PASTE)

        return ActionResult.success(
            "已尝试输入，请验证输入是否成功，如果输入不成功，可能是输入框没有聚焦，请先使用 Tap 点击输入框，不要继续尝试 Type"
        )
    }

    companion object : ToolDefinition<Type>(
        name = "Type",
        description = "在当前聚焦的输入框中输入文本。使用前请确保输入框已被聚焦（先 Tap 点击它）。文本中不要出现转义字符。",
        parameters = objectParameters("text" to "string"),
        decode = { json, arguments -> json.decodeFromString(Type.serializer(), arguments) },
    )
}

@Serializable
@SerialName("Swipe")
data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) :
    Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val (startX, startY) = service.mapToScreenCoordinates(startX, startY)
            ?: return ActionResult.retry("起始坐标不在范围内，请重试")
        val (endX, endY) = service.mapToScreenCoordinates(endX, endY)
            ?: return ActionResult.retry("结束坐标不在范围内，请重试")
        val distance = (endX - startX).pow(2) + (endY - startY).pow(2)
        val durationMs = (distance / 1000.0f).toLong().coerceIn(1000, 2000)
        return if (service.injectSwipeEvent(startX, startY, endX, endY, durationMs)) {
            ActionResult.success()
        } else {
            ActionResult.fail()
        }
    }

    companion object : ToolDefinition<Swipe>(
        name = "Swipe",
        description = "通过从起始坐标拖动到结束坐标来执行滑动手势，用于滚动内容、在屏幕间导航、下拉通知栏等。",
        parameters = objectParameters(
            "startX" to "number", "startY" to "number",
            "endX" to "number", "endY" to "number",
        ),
        decode = { json, arguments -> json.decodeFromString(Swipe.serializer(), arguments) },
    )
}

@Serializable
@SerialName("LongPress")
data class LongPress(val x: Int, val y: Int) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val screenX = service.mapXToScreen(x.toFloat())
            ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
        val screenY = service.mapYToScreen(y.toFloat())
            ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

        return if (service.injectSwipeEvent(
                screenX, screenY,
                screenX, screenY,
                Random.nextLong(900, 1000),
        )) {
            ActionResult.success()
        } else {
            ActionResult.fail()
        }
    }

    companion object : ToolDefinition<LongPress>(
        name = "LongPress",
        description = "在屏幕上的特定点长按，用于触发上下文菜单、选择文本或激活长按交互。",
        parameters = objectParameters("x" to "integer", "y" to "integer"),
        decode = { json, arguments -> json.decodeFromString(LongPress.serializer(), arguments) },
    )
}

@Serializable
@SerialName("DoubleTap")
data class DoubleTap(val x: Int, val y: Int) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        val screenX = service.mapXToScreen(x.toFloat())
            ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
        val screenY = service.mapYToScreen(y.toFloat())
            ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

        if (!service.injectTapEvent(screenX, screenY)) {
            return ActionResult.fail()
        }

        delay(Random.nextLong(100, 160).milliseconds)

        if (!service.injectTapEvent(screenX, screenY)) {
            return ActionResult.fail()
        }
        return ActionResult.success()
    }

    companion object : ToolDefinition<DoubleTap>(
        name = "DoubleTap",
        description = "在屏幕上的特定点快速连续点按两次，用于激活双击交互。",
        parameters = objectParameters("x" to "integer", "y" to "integer"),
        decode = { json, arguments -> json.decodeFromString(DoubleTap.serializer(), arguments) },
    )
}

@Serializable
@SerialName("TakeOver")
data class TakeOver(val message: String) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        service.awaitUserTakeOver(message)
        return ActionResult.success("用户已接管完成，请先检查当前界面，再决定下一步操作")
    }

    companion object : ToolDefinition<TakeOver>(
        name = "TakeOver",
        description = "接管操作，表示在登录和验证阶段需要用户协助。message 尽量简洁。",
        parameters = objectParameters("message" to "string"),
        decode = { json, arguments -> json.decodeFromString(TakeOver.serializer(), arguments) },
    )
}

@Serializable
@SerialName("Back")
data object Back : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        if (service.injectKeyPressEvent(KeyEvent.KEYCODE_BACK)) {
            return ActionResult.success()
        }
        return ActionResult.fail()
    }
}

object BackTool : ToolDefinition<Back>(
    name = "Back",
    description = "返回到上一个屏幕或关闭当前对话框，相当于按下 Android 返回按钮。",
    parameters = Parameters.Empty,
    decode = { _, _ -> Back },
)

@Serializable
@SerialName("Wait")
data class Wait(val seconds: Int) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult {
        delay((seconds * 1000L).milliseconds)
        return ActionResult.success()
    }

    companion object : ToolDefinition<Wait>(
        name = "Wait",
        description = "等待页面加载，seconds 为需要等待的秒数。",
        parameters = objectParameters("seconds" to "integer"),
        decode = { json, arguments -> json.decodeFromString(Wait.serializer(), arguments) },
    )
}

@Serializable
@SerialName("Finish")
data class Finish(val message: String) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult = ActionResult.success()

    companion object : ToolDefinition<Finish>(
        name = "Finish",
        description = "结束任务，表示准确完整地完成了任务。message 为简洁的任务结果总结，将作为完成通知的正文展示给用户。",
        parameters = objectParameters("message" to "string"),
        decode = { json, arguments -> json.decodeFromString(Finish.serializer(), arguments) },
    )
}


@Serializable
@SerialName("TermuxShell")
data class TermuxShell(val command: String, val workingDirectory: String) : Action() {
    override fun approval() = ToolApprovalDetails(
        title = "Run command in Termux?",
        details = "Working directory: $workingDirectory\n\n$command\n\n" +
            "Runs with access to Termux files and installed tools. " +
            "Stopping the agent stops waiting for results; the command may continue. " +
            "Termux must have allow-external-apps=true in ~/.termux/termux.properties.",
        permissions = listOf(TermuxConstants.PERMISSION_RUN_COMMAND),
    )

    override suspend fun executeApproved(service: AgentService): ActionResult =
        TermuxCommandRunner.run(service, command, workingDirectory)

    companion object : ToolDefinition<TermuxShell>(
        name = "TermuxShell",
        description = "经用户确认后，在 Termux 后台运行 Bash 命令，返回 stdout、stderr 和退出码。" +
            "command 为命令，workingDirectory 为 Termux 中的绝对路径或 ~。每次使用新 shell。" +
            "需要已安装并初始化 Termux、授予运行命令权限并启用 allow-external-apps。" +
            "结果等待最多 120 秒；超时不代表命令已停止，不要盲目重试。不能用于绕过用户拒绝或应用启动限制。",
        parameters = objectParameters("command" to "string", "workingDirectory" to "string"),
        decode = { json, arguments -> json.decodeFromString(TermuxShell.serializer(), arguments) },
    )
}
