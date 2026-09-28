package com.octo.controlpanel.judgment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `the default registry approves jev as a zero-data-retention judge`() {
        val model = ModelRegistry.DEFAULT.require("typesafe/jev-1.13", ModelRole.JUDGE)
        assertTrue(model.zeroDataRetention)
        assertEquals(ModelRole.JUDGE, model.role)
    }

    @Test
    fun `an unregistered model is rejected`() {
        val e =
            assertFailsWith<ModelNotApprovedException> {
                ModelRegistry.DEFAULT.require("acme/unknown-1", ModelRole.JUDGE)
            }
        assertEquals("acme/unknown-1", e.modelId)
    }

    @Test
    fun `a registered but non-ZDR model is rejected fail-closed`() {
        val registry = ModelRegistry(listOf(ApprovedModel("acme/leaky-1", zeroDataRetention = false, role = ModelRole.JUDGE)))
        assertFailsWith<ModelNotApprovedException> { registry.require("acme/leaky-1", ModelRole.JUDGE) }
    }

    @Test
    fun `a model approved for another role is rejected`() {
        assertFailsWith<ModelNotApprovedException> {
            ModelRegistry.DEFAULT.require("typesafe/jev-1.13", ModelRole.DRAFTER)
        }
    }

    @Test
    fun `an empty registry is rejected`() {
        assertFailsWith<IllegalArgumentException> { ModelRegistry(emptyList()) }
    }

    @Test
    fun `duplicate model ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelRegistry(
                listOf(
                    ApprovedModel("dup", zeroDataRetention = true, role = ModelRole.JUDGE),
                    ApprovedModel("dup", zeroDataRetention = true, role = ModelRole.DRAFTER),
                ),
            )
        }
    }

    @Test
    fun `a blank model id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ApprovedModel("  ", zeroDataRetention = true, role = ModelRole.JUDGE)
        }
    }
}
