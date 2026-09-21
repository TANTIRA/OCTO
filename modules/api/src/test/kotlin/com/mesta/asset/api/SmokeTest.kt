package com.mesta.asset.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun `build is wired`() {
        assertTrue(MestaAssetApplication::class.java.name.isNotBlank())
    }
}
