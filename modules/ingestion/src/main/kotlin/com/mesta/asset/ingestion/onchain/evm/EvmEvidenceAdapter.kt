package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.mesta.asset.ingestion.onchain.EvidenceKind
import com.mesta.asset.ingestion.onchain.EvidenceSubject
import com.mesta.asset.ingestion.onchain.OnchainEvidence
import java.math.BigDecimal
import java.time.Instant

/**
 * The EVM counterpart of `OnchainEvidenceAdapter` (issue #116, megaplan phase ARB-8).
 * Every record is what the chain reported at the `finalized` tag — never the verdict;
 * `ClaimVerifier` judges downstream and treats missing evidence as INCONCLUSIVE.
 *
 * Honesty limits on plain JSON-RPC:
 * - `holder-concentration` produces no row: there is no holder index on `eth_*` calls,
 *   so the kind stays unverifiable rather than approximated.
 * - `account-activity` is the nonce (`eth_getTransactionCount`) — outbound transactions
 *   only; inbound transfers are not counted.
 * - `treasury-balance` reports native ETH in wei, or `balanceOf` when the claim subject
 *   also names a `mintAddress` (the token's treasury holding).
 */
class EvmEvidenceAdapter(
    private val rpc: EvmRpcApi,
    private val config: EvmConfig,
) {
    fun gather(
        subject: EvidenceSubject,
        asOf: Instant = Instant.now(),
    ): List<OnchainEvidence> =
        buildList {
            subject.mintAddress?.lowercase()?.let { mint ->
                rpc.totalSupply(mint)?.let { supply ->
                    add(
                        evidence(
                            subject,
                            EvidenceKind.TOKEN_SUPPLY,
                            mint,
                            supply.toBigDecimal(),
                            null,
                            numericPayload("rawSupply", supply.toBigDecimal()),
                            asOf,
                        ),
                    )
                }
            }
            subject.treasuryAddress?.lowercase()?.let { treasury ->
                val mint = subject.mintAddress?.lowercase()
                val balance = mint?.let { rpc.balanceOf(it, treasury) } ?: rpc.nativeBalance(treasury)
                add(
                    evidence(
                        subject,
                        EvidenceKind.TREASURY_BALANCE,
                        treasury,
                        balance.toBigDecimal(),
                        null,
                        numericPayload(if (mint == null) "wei" else "rawBalance", balance.toBigDecimal()),
                        asOf,
                    ),
                )
                add(
                    evidence(
                        subject,
                        EvidenceKind.ACCOUNT_ACTIVITY,
                        treasury,
                        BigDecimal.valueOf(rpc.transactionCount(treasury)),
                        null,
                        numericPayload("nonce", BigDecimal.valueOf(rpc.transactionCount(treasury))),
                        asOf,
                    ),
                )
            }
        }

    private fun numericPayload(
        field: String,
        value: BigDecimal,
    ): JsonNode = JsonNodeFactory.instance.objectNode().put(field, value.toPlainString())

    private fun evidence(
        subject: EvidenceSubject,
        kind: EvidenceKind,
        address: String,
        numeric: BigDecimal?,
        text: String?,
        payload: JsonNode,
        asOf: Instant,
    ): OnchainEvidence {
        val observed = numeric?.toPlainString() ?: text ?: "none"
        return OnchainEvidence(
            externalId = "${config.chain}:${kind.db}:$address:${asOf.epochSecond}:$observed",
            claimRef = subject.claimRef,
            subjectAddress = address,
            kind = kind,
            observedNumeric = numeric,
            observedText = text,
            payload = payload,
            asOf = asOf,
            chain = config.chain,
            sourceSystem = config.sourceSystem,
        )
    }
}
