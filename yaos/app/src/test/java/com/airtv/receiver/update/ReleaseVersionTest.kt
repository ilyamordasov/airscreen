package com.airtv.receiver.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun `new minor release is newer`() {
        assertTrue(ReleaseVersion.parse("v0.2.0")!! > ReleaseVersion.parse("0.1.0")!!)
        assertEquals("0.2.0", ReleaseVersion.parse("v0.2.0").toString())
    }

    @Test fun `malformed versions are rejected`() {
        listOf("v1.2", "v1.2.3-beta", "v01.2.3", "v1.2.99999999999999999999")
            .forEach { assertNull(ReleaseVersion.parse(it)) }
    }
}
