package com.octo.ingestion.onchain.persistence

import com.octo.ingestion.onchain.BalanceSource
import com.octo.ingestion.onchain.OnchainBalance
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Two stake accounts of one wallet with the same delegated amount in one pass used to share an
 * `external_id`, so `on conflict do nothing` silently dropped the second.
 */
class JdbcOnchainStagingStoreSnapshotIdTest {
    private val store =
        JdbcOnchainStagingStore(
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
                error("snapshot ids must not touch the database")
            } as DataSource,
        )

    private fun stake(tokenAccount: String?) =
        OnchainBalance(
            wallet = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
            tokenAccount = tokenAccount,
            mintAddress = null,
            amountRaw = BigInteger("5000000000"),
            decimals = 9,
            usdValue = null,
            source = BalanceSource.RPC,
            slot = null,
            asOf = Instant.parse("2025-06-01T00:00:00Z"),
        )

    @Test
    fun `two stake accounts with equal amounts get distinct ids`() {
        assertNotEquals(store.snapshotExternalId(stake("StakeA111")), store.snapshotExternalId(stake("StakeB222")))
    }

    @Test
    fun `a row without a token account keeps its original id`() {
        assertEquals(
            "solana:7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU:native:balance:rpc:1748736000:5000000000",
            store.snapshotExternalId(stake(null)),
        )
    }
}
