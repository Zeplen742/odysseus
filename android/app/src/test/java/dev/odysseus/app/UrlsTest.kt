package dev.odysseus.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    @Test fun bareHostGetsHttps() =
        assertEquals("https://odysseus.vaultserver.uk", Urls.normalizeBase(" odysseus.vaultserver.uk/ "))

    @Test fun lanAddressGetsHttpAndKeepsPort() =
        assertEquals("http://192.168.68.51:7000", Urls.normalizeBase("192.168.68.51:7000"))

    @Test fun pathIsDroppedAndHostLowercased() =
        assertEquals("https://example.com", Urls.normalizeBase("HTTPS://Example.com/chat#abc"))

    @Test fun nonWebSchemesAreRejected() {
        assertNull(Urls.normalizeBase("file:///etc/passwd"))
        assertNull(Urls.normalizeBase("javascript:alert(1)"))
        assertNull(Urls.normalizeBase(""))
    }

    @Test fun serverUrlMatchesOriginOnly() {
        val b = "https://odysseus.vaultserver.uk"
        assertTrue(Urls.isServerUrl(b, "https://odysseus.vaultserver.uk/#abc"))
        assertTrue(Urls.isServerUrl(b, "https://odysseus.vaultserver.uk:443/api/x"))
        assertFalse(Urls.isServerUrl(b, "http://odysseus.vaultserver.uk/"))
        assertFalse(Urls.isServerUrl(b, "https://odysseus.vaultserver.uk.evil.com/"))
        assertFalse(Urls.isServerUrl(b, "https://github.com/"))
    }

    @Test fun sessionIdsAreUuidLike() {
        assertTrue(Urls.isSessionId("3f2b1c9e-0d4a-4c1b-9a77-2f1e5b6c7d8e"))
        assertFalse(Urls.isSessionId("abc'; alert(1); '"))
        assertFalse(Urls.isSessionId("../etc"))
        assertFalse(Urls.isSessionId(""))
        assertFalse(Urls.isSessionId(null))
    }

    @Test fun chatUrlUsesHash() =
        assertEquals("https://h/#s1", Urls.chatUrl("https://h", "s1"))
}
