package kr.coms.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteApiTest {
    @Test fun productionRequiresHttpsAndDebugAllowsOnlyEmulatorLoopback() {
        assertEquals("https://example.org", checkedBaseUrl("https://example.org/", false))
        assertThrows(IllegalArgumentException::class.java) { checkedBaseUrl("http://example.org", false) }
        assertEquals("http://10.0.2.2:3000", checkedBaseUrl("http://10.0.2.2:3000/", true))
        assertThrows(IllegalArgumentException::class.java) { checkedBaseUrl("http://localhost:3000", true) }
        assertEquals("https://example.org/companion", checkedBaseUrl("https://example.org/companion/", false))
        assertEquals("https://example.org/club-v2/api_site", checkedBaseUrl("https://example.org/club-v2/api_site", false))
        assertEquals("http://10.0.2.2:3000/companion", checkedBaseUrl("http://10.0.2.2:3000/companion/", true))
        listOf("/companion//api", "/companion/./api", "/companion/../api", "/companion/%2fapi",
            "/companion/%5capi", "/companion/%2e%2e/api", "/companion;api", "/companion//").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { checkedBaseUrl("https://example.org$path", false) }
        }
        listOf("https://user:pass@example.org/companion", "https://example.org/companion?mode=1",
            "https://example.org/companion#part").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { checkedBaseUrl(value, false) }
        }
    }
    @Test fun onlyDefinitiveRejectionReleasesPublishOperation() {
        org.junit.Assert.assertTrue(ApiFailure(400, "invalid").definitiveNoSave)
        org.junit.Assert.assertTrue(ApiFailure(409, "conflict").definitiveNoSave)
        org.junit.Assert.assertFalse(ApiFailure(500, "uncertain").definitiveNoSave)
        org.junit.Assert.assertFalse(ApiFailure(408, "timeout").definitiveNoSave)
    }

}
