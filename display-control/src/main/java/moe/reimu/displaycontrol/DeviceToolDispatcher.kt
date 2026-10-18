package moe.reimu.displaycontrol

/** Binds implementations to shared definitions; hosts never register their own device handlers. */
internal class DeviceToolDispatcher(vararg handlers: Handler<*, *>) {
    private val byType = handlers.associateBy { it.definition.type }
    val names: Set<String> = handlers.map { it.definition.spec.name }.toSet()

    init {
        require(names.size == handlers.size && byType.size == handlers.size) { "Duplicate device tool registration" }
    }

    fun execute(name: String, arguments: String): DeviceToolResult =
        checkNotNull(DeviceToolCatalog.find(name)) { "Unknown tool: $name" }.decode(arguments).execute(this)

    fun <R> execute(command: DeviceCommand<R>): R {
        val handler = checkNotNull(byType[command::class]) { "Unregistered command: ${command::class.simpleName}" }
        @Suppress("UNCHECKED_CAST")
        return (handler as Handler<DeviceCommand<R>, R>).execute(command)
    }

    class Handler<C : DeviceCommand<R>, R>(val definition: DeviceToolDefinition<C, R>, val execute: (C) -> R)
}
