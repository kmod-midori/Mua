package moe.reimu.nekoassistant.ai.tool

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
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
        if (service.hasPendingUserMessages) {
            return ActionResult.retry("用户发来了新的指示，此工具未执行。请先根据新指示重新规划。")
        }
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
    private val tools = listOf(TakeOver, Wait, Finish, TermuxShell)
    private val toolsByName = tools.associateBy { it.name }

    fun definitions(termuxAvailable: Boolean = false): List<Tool> =
        moe.reimu.displaycontrol.DeviceToolCatalog.specs.map { spec ->
            Tool.function(spec.name, spec.description, Parameters(
                Json.encodeToJsonElement(moe.reimu.displaycontrol.DeviceToolSchema.serializer(), spec.inputSchema)
            ))
        } + tools.filter { termuxAvailable || it !== TermuxShell }.map { it.definition }

    fun decode(name: String, arguments: String, termuxAvailable: Boolean = false): Action? {
        moe.reimu.displaycontrol.DeviceToolCatalog.find(name)?.let {
            return DeviceAction(it.decode(arguments))
        }
        return toolsByName[name]?.takeIf { termuxAvailable || it !== TermuxShell }?.decode(json, arguments)
    }
}

/** Generic agent adapter; device names, arguments, validation and handlers are shared. */
class DeviceAction(val call: moe.reimu.displaycontrol.DeviceToolCall<*, *>) : Action() {
    override suspend fun executeApproved(service: AgentService): ActionResult = try {
        service.executeDeviceCall(call)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult.retry(e.message ?: "Device tool failed")
    }

    override fun toString(): String = call.toString()
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
