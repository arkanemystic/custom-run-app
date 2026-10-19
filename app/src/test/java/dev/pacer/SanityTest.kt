package dev.pacer

import org.junit.Assert.assertEquals
import org.junit.Test

class SanityTest {
    @Test
    fun buildConfigIsGenerated() {
        assertEquals("dev.pacer", BuildConfig.APPLICATION_ID)
    }
}
