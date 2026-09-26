package com.mesta.asset.ingestion.onchain.evm

/**
 * Resolves ERC-20 `decimals` for the contracts a scan encounters. The instrument registry
 * wins — it is the governed value — then a `decimals()` `eth_call`, cached for the life of
 * the resolver (one scan run). A contract that cannot answer returns null, and the caller
 * skips its legs rather than ever guessing a number into a financial fact.
 */
class EvmDecimalsResolver(
    private val rpc: EvmRpcApi,
    private val registered: Map<String, Int>,
) {
    private val cache = mutableMapOf<String, Int?>()

    fun resolve(contract: String): Int? =
        cache.getOrPut(contract) { registered[contract] ?: rpc.decimals(contract) }
}
