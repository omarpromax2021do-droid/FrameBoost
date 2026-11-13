package com.framewise.app.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun parseValidVersions_withAndWithoutPrefix() {
        val v1 = SemanticVersion.parse("v1.2.3")
        assertEquals(1, v1.major)
        assertEquals(2, v1.minor)
        assertEquals(3, v1.patch)
        assertNull(v1.preRelease)

        val v2 = SemanticVersion.parse("V2.10.0-beta.4+build99")
        assertEquals(2, v2.major)
        assertEquals(10, v2.minor)
        assertEquals(0, v2.patch)
        assertEquals("beta.4", v2.preRelease)
    }

    @Test
    fun compareVersions_followsSemVerPrecedence() {
        assertTrue(SemanticVersion.parse("1.0.1") > SemanticVersion.parse("1.0.0"))
        assertTrue(SemanticVersion.parse("1.1.0") > SemanticVersion.parse("1.0.99"))
        assertTrue(SemanticVersion.parse("2.0.0") > SemanticVersion.parse("1.99.99"))

        // Stable > Pre-release
        assertTrue(SemanticVersion.parse("1.2.0") > SemanticVersion.parse("1.2.0-rc.1"))
        // Numeric pre-release comparison (10 > 2)
        assertTrue(SemanticVersion.parse("1.2.0-alpha.10") > SemanticVersion.parse("1.2.0-alpha.2"))
        // Alphanumeric > Numeric
        assertTrue(SemanticVersion.parse("1.2.0-beta") > SemanticVersion.parse("1.2.0-1"))
        // Build metadata ignored in equality
        assertEquals(0, SemanticVersion.parse("1.0.0+001").compareTo(SemanticVersion.parse("1.0.0+002")))
    }

    @Test
    fun parseInvalidVersions_returnsNull() {
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("1.0"))
        assertNull(SemanticVersion.parseOrNull("latest"))
        assertNull(SemanticVersion.parseOrNull("1.2.3-"))
        assertNull(SemanticVersion.parseOrNull("1.2.3-alpha..1"))
    }
}
