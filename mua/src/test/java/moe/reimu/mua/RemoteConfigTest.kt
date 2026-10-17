package moe.reimu.mua

import org.junit.Assert.*
import org.junit.Test

class RemoteConfigTest {
    @Test fun disabledNeedsNoCredentials() {
        RemoteConfig().validate()
    }

    @Test fun endpointPreservesPrefixAndEncodesDeviceId() {
        assertEquals("wss://example.com/mua/devices/test%20phone/ws",
            RemoteConfig(true, "wss://example.com/mua/", "test phone", "token").endpoint())
        assertEquals("ws://192.168.1.2:8000/devices/a/ws",
            RemoteConfig(true, "ws://192.168.1.2:8000", "a", "token").endpoint())
    }

    @Test fun rejectsInvalidUrlsAndHeaderInjection() {
        for (url in listOf("https://example.com", "wss:///", "wss://user:pass@example.com",
            "wss://example.com?token=x", "wss://example.com#fragment", "ws://example.com:0")) {
            assertThrows(Exception::class.java) { RemoteConfig(true, url, "a", "token").validate() }
        }
        assertThrows(Exception::class.java) { RemoteConfig(true, "wss://example.com", "a/b", "token").validate() }
        assertThrows(Exception::class.java) { RemoteConfig(true, "wss://example.com", "a", "").validate() }
        assertThrows(Exception::class.java) { RemoteConfig(true, "wss://example.com", "a", "x\r\nInjected: 1").validate() }
    }
}
