package moe.reimu.displaycontrol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

@Serializable
data class DeviceToolAnnotations(val readOnlyHint: Boolean = false)

@Serializable
data class ToolProperty(val type: String, val minimum: Double? = null, val maximum: Double? = null, val minLength: Int? = null)

@Serializable
data class DeviceToolSchema(val type: String, val properties: Map<String, ToolProperty>, val required: List<String>)

@Serializable
data class DeviceToolSpec(val name: String, val description: String, val inputSchema: DeviceToolSchema, val annotations: DeviceToolAnnotations? = null) {
    // Argument keys are dynamic at the protocol boundary; command execution uses typed values.
    fun validate(args: JsonObject) {
        require(args.keys.containsAll(inputSchema.required)) { "Expected arguments: ${inputSchema.required.joinToString()}" }
        if (name == "display_start") require(args.keys.none { it in setOf("width", "height", "dpi") }) {
            "Display dimensions and DPI are taken from the main display"
        }
        inputSchema.properties.forEach { (key, rule) ->
            val value = args[key] as? JsonPrimitive ?: error("$key must be a primitive")
            when (rule.type) {
                "string" -> {
                    require(value.isString) { "$key must be a string" }
                    rule.minLength?.let { minimum ->
                        require(value.content.length >= minimum) { "$key must contain at least $minimum characters" }
                    }
                }
                else -> {
                    val number = value.doubleOrNull
                    require(!value.isString && number != null && number.isFinite()) { "$key must be a number" }
                    if (rule.type == "integer") {
                        require(if (key == "durationMs") value.longOrNull != null else value.intOrNull != null) { "$key must be an integer" }
                    } else require(value.floatOrNull?.isFinite() == true) { "$key must be a finite coordinate" }
                    rule.minimum?.let { require(number >= it) { "$key is outside the allowed range" } }
                    rule.maximum?.let { require(number <= it) { "$key is outside the allowed range" } }
                }
            }
        }
    }
}

class DeviceToolDefinition<C : DeviceCommand<R>, R> internal constructor(
    val spec: DeviceToolSpec,
    val type: KClass<C>,
    val serializer: KSerializer<C>,
    private val encode: (R) -> DeviceToolResult,
) {
    fun decode(arguments: String): DeviceToolCall<C, R> {
        val json = DeviceToolCatalog.json
        val args = json.parseToJsonElement(arguments).jsonObject
        spec.validate(args)
        return DeviceToolCall(this, json.decodeFromJsonElement(serializer, args))
    }

    internal fun result(value: R): DeviceToolResult = encode(value)
}

class DeviceToolCall<C : DeviceCommand<R>, R> internal constructor(
    val definition: DeviceToolDefinition<C, R>,
    val command: C,
) {
    fun execute(executor: DeviceToolExecutor): DeviceToolResult = definition.result(executor.execute(command))
    internal fun execute(dispatcher: DeviceToolDispatcher): DeviceToolResult = definition.result(dispatcher.execute(command))
    override fun toString(): String = "${definition.spec.name}: $command"
}

/** The only device-tool catalog. Hosts adapt definitions to their protocol SDKs. */
object DeviceToolCatalog {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val metadata: Map<String, DeviceToolSpec> = checkNotNull(
        DeviceToolCatalog::class.java.getResourceAsStream("/device_tools.json")
    ) { "Missing device tool catalog" }.bufferedReader().use {
        json.decodeFromString<List<DeviceToolSpec>>(it.readText()).associateBy(DeviceToolSpec::name)
    }

    val definitions: List<DeviceToolDefinition<*, *>> = listOf(
        data<StartDisplay, DisplayInfo>("display_start"),
        data<ListDisplays, List<DisplayInfo>>("displays_list"),
        data<ListRecentTasks, List<RecentTaskInfo>>("recent_tasks_list"),
        text<MoveRecentTask>("recent_task_move"), text<StopDisplay>("display_stop"),
        DeviceToolDefinition(spec("screenshot"), Screenshot::class, Screenshot.serializer()) { it },
        data<ListApps, List<AppInfo>>("apps_list"), text<LaunchApp>("app_launch"),
        text<FocusedApp>("focused_app"), text<Tap>("tap"), text<Swipe>("swipe"), text<KeyPress>("key_press"),
        data<ListNotifications, List<NotificationInfo>>("notifications_list"),
        text<ClearNotification>("notification_clear"), text<ClickNotification>("notification_click"),
        text<NotificationAction>("notification_action"), text<ReplyNotification>("notification_reply"),
        data<SearchApps, List<AppInfo>>("apps_search"), text<InputText>("text_input"),
        text<LongPress>("long_press"), text<DoubleTap>("double_tap"),
    )
    private val byName = definitions.associateBy { it.spec.name }
    private val byType = definitions.associateBy { it.type }
    val specs: List<DeviceToolSpec> get() = definitions.map { it.spec }

    init {
        check(byName.keys == metadata.keys && byName.size == byType.size) { "Incomplete or duplicate tool definitions" }
    }

    fun find(name: String): DeviceToolDefinition<*, *>? = byName[name]

    internal fun <C : DeviceCommand<R>, R> definition(type: KClass<C>): DeviceToolDefinition<C, R> {
        // Every registration binds a concrete command to its declared result type.
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(byType[type]) as DeviceToolDefinition<C, R>
    }

    private fun spec(name: String) = checkNotNull(metadata[name])
    private inline fun <reified C : DeviceCommand<R>, reified R> data(name: String) =
        DeviceToolDefinition(spec(name), C::class, serializer<C>()) { value: R -> DeviceToolResult.Text(json.encodeToString(value)) }
    private inline fun <reified C : DeviceCommand<String>> text(name: String) =
        DeviceToolDefinition(spec(name), C::class, serializer<C>()) { DeviceToolResult.Text(it) }
}
