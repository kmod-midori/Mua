package moe.reimu.mua

import org.junit.Assert.*
import org.junit.Test

class RemoteConfigTest {
    @Test fun disabledNeedsNoCredentials() {
        RemoteConfig().validate()
    }

    @Test fun endpointPreservesPrefixAndEncodesDeviceId() {
        assertEquals("wss://example.com/mua/devices/test%20phone/ws",
            RemoteConfig(true, "https://example.com/mua/", "test phone", "token").endpoint())
        assertEquals("ws://192.168.1.2:8000/devices/a/ws",
            RemoteConfig(true, "http://192.168.1.2:8000", "a", "token").endpoint())
    }

    @Test fun pushEndpointPreservesPrefixAndUsesHttpScheme() {
        assertEquals("https://example.com/mua/devices/test%20phone/push",
            RemoteConfig(true, "https://example.com/mua/", "test phone", "token").pushEndpoint())
        assertEquals("http://192.168.1.2:8000/devices/a/push",
            RemoteConfig(true, "http://192.168.1.2:8000", "a", "token").pushEndpoint())
    }

    @Test fun rejectsInvalidUrlsAndHeaderInjection() {
        for (url in listOf("ws://example.com", "wss://example.com", "https:///", "https://user:pass@example.com",
            "https://example.com?token=x", "https://example.com#fragment", "http://example.com:0")) {
            assertThrows(Exception::class.java) { RemoteConfig(true, url, "a", "token").validate() }
        }
        assertThrows(Exception::class.java) { RemoteConfig(true, "https://example.com", "a/b", "token").validate() }
        assertThrows(Exception::class.java) { RemoteConfig(true, "https://example.com", "a", "").validate() }
        assertThrows(Exception::class.java) { RemoteConfig(true, "https://example.com", "a", "x\r\nInjected: 1").validate() }
    }
}
