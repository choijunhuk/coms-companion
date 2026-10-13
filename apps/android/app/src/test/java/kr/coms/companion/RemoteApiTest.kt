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
        assertThrows(IllegalArgumentException::class.java) { checkedBaseUrl("https://example.org/other", false) }
    }
    @Test fun onlyDefinitiveRejectionReleasesPublishOperation() {
        org.junit.Assert.assertTrue(ApiFailure(400, "invalid").definitiveNoSave)
        org.junit.Assert.assertTrue(ApiFailure(409, "conflict").definitiveNoSave)
        org.junit.Assert.assertFalse(ApiFailure(500, "uncertain").definitiveNoSave)
        org.junit.Assert.assertFalse(ApiFailure(408, "timeout").definitiveNoSave)
    }

}
