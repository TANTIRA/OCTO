package com.octo.ontology

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the dual-format parser's read of the canonical `ontology/octo-investment.cypher`:
 * counts, kinds, sub-typing, reification, enum values, and the computed players map.
 */
class CypherSchemaTest {
    private val schema =
        CypherSchema.parse(
            File(System.getProperty("ontology.dir"), "octo-investment.cypher").toPath(),
        )

    @Test
    fun `parses every declared attribute`() {
        assertEquals(51, schema.attributes.size)
        assertEquals(
            listOf(
                "contribution",
                "distribution",
                "management-fee",
                "expense",
                "carried-interest",
                "recallable-distribution",
                "other-income",
            ),
            schema.attributes.getValue("flow-type").values,
        )
        assertEquals("^[A-Z]{3}$", schema.attributes.getValue("currency-code").regex)
        assertEquals("0", schema.attributes.getValue("decimals").rangeMin)
        assertEquals("255", schema.attributes.getValue("decimals").rangeMax)
        val uuid = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        assertEquals(uuid, schema.attributes.getValue("tenant-id").regex)
        assertEquals(uuid, schema.attributes.getValue("octo-id").regex)
    }

    @Test
    fun `tenant-owned entities own tenant-id and a unique octo-id, reference data owns neither`() {
        val tenantOwned = listOf("party", "fund", "deal", "investment", "document", "wallet", "evm-wallet")
        for (name in tenantOwned) {
            val owns = schema.entities.getValue(name).owns
            assertTrue(owns.any { it.attribute == "tenant-id" && !it.key }, "$name must own tenant-id")
            assertTrue(owns.single { it.attribute == "octo-id" }.unique, "$name must own octo-id @unique")
        }
        val owners =
            schema.entities.values
                .filter { type -> type.owns.any { it.attribute == "tenant-id" } }
                .map { it.name }
                .toSet()
        assertEquals(tenantOwned.toSet(), owners)
    }

    @Test
    fun `parses entities with sub and abstract flags`() {
        assertEquals(23, schema.entities.size)
        assertTrue(schema.entities.getValue("party").isAbstract)
        assertEquals("organization", schema.entities.getValue("fund-manager").superType)
        assertEquals("instrument", schema.entities.getValue("solana-mint").superType)
        assertNull(schema.entities.getValue("instrument").superType)
    }

    @Test
    fun `parses owns annotations`() {
        val instrument = schema.entities.getValue("instrument")
        assertTrue(instrument.owns.single { it.attribute == "instrument-id" }.key)
        assertTrue(
            schema.entities
                .getValue("solana-mint")
                .owns
                .single { it.attribute == "solana-address" }
                .unique,
        )
    }

    @Test
    fun `parses relations and marks reified n-ary relations`() {
        assertEquals(22, schema.relations.size)
        val reified =
            schema.relations.values
                .filter { it.reified }
                .map { it.name }
                .sorted()
        assertEquals(listOf("cash-flow-attribution", "fund-investment", "instrument-flow-of"), reified)
        assertEquals(
            listOf("flow-side", "instrument-side", "wallet-side"),
            schema.relations
                .getValue("instrument-flow-of")
                .relates
                .map { it.role },
        )
    }

    @Test
    fun `computes players from plays clauses`() {
        val walletSide =
            schema.relations
                .getValue("instrument-flow-of")
                .players
                .getValue("wallet-side")
        assertEquals(setOf("wallet", "evm-wallet"), walletSide)
        assertTrue(
            schema.relations
                .getValue("wallet-custody")
                .players
                .getValue("owner")
                .contains("organization"),
        )
    }
}
