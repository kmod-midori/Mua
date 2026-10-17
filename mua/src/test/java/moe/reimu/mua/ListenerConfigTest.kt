package moe.reimu.mua

import org.junit.Assert.*
import org.junit.Test

class ListenerConfigTest {
    @Test fun acceptsOnlySupportedAddressesAndUnprivilegedPorts() {
        assertEquals(ListenerConfig("127.0.0.1", 8765), ListenerConfig())
        for (address in ListenerConfig.ADDRESSES) {
            for (port in listOf(1024, 8765, 65535)) assertEquals(port, ListenerConfig(address, port).port)
        }
        for (port in listOf(-1, 0, 80, 1023, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { ListenerConfig(port = port) }
        }
        for (address in listOf("", "localhost", "192.168.1.1", "::")) {
            assertThrows(IllegalArgumentException::class.java) { ListenerConfig(address = address) }
        }
    }
}
