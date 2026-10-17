package moe.reimu.mua

internal data class ListenerConfig(val address: String = "127.0.0.1", val port: Int = 8765, val enabled: Boolean = true) {
    init {
        require(address in ADDRESSES) { "Select 127.0.0.1 or 0.0.0.0" }
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
    }

    companion object {
        val ADDRESSES = listOf("127.0.0.1", "0.0.0.0")
    }
}
