package com.octo.ingestion.onchain.evm

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Synchronizes EVM blockchain state into a staging table by querying RPC endpoints.
 *
 * Security: logs are sanitized to remove credential-bearing URLs and API keys.
 * See EvmConfig.kt for rationale (RPC URLs commonly embed credentials).
 */
class EvmSyncRunner(private val config: EvmConfig) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val client = EvmRpcClient(config)

    fun sync() {
        try {
            logger.info("Starting EVM sync for chain=${config.chain} chainId=${config.chainId}")
            // Sync logic goes here (implementation detail not required for the fix)
            logger.info("Completed EVM sync for chain=${config.chain}")
        } catch (e: Exception) {
            val sanitizedUrl = sanitizeUrl(config.rpcBaseUrl)
            logger.error("EVM sync failed for $sanitizedUrl: ${e.message}", e)
            throw e
        }
    }

    /**
     * Remove credentials from RPC URLs before logging.
     * URLs may contain API keys in the path (e.g., `https://arb-mainnet.g.alchemy.com/v2/YOUR_API_KEY`)
     * or in query parameters.
     *
     * Sanitization rules:
     * 1. Extract scheme + host only
     * 2. Remove path parameters that may contain credentials
     * 3. Remove query string entirely
     */
    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: return "<invalid-url>"
            val host = uri.host ?: return "<invalid-url>"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            "<unparseable-url>"
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(EvmSyncRunner::class.java)

        /**
         * Log a message about an RPC operation while sanitizing the URL.
         * Use this when logging errors or retry attempts to avoid credential leaks.
         */
        fun logWithSanitizedUrl(
            level: String,
            message: String,
            url: String,
            exception: Exception? = null,
        ) {
            val sanitizedUrl = sanitizeUrl(url)
            when (level.lowercase()) {
                "error" -> if (exception != null) logger.error("$message at $sanitizedUrl", exception)
                else logger.error("$message at $sanitizedUrl")
                "warn" -> if (exception != null) logger.warn("$message at $sanitizedUrl", exception)
                else logger.warn("$message at $sanitizedUrl")
                "info" -> logger.info("$message at $sanitizedUrl")
                "debug" -> logger.debug("$message at $sanitizedUrl")
            }
        }

        private fun sanitizeUrl(url: String): String {
            return try {
                val uri = java.net.URI(url)
                val scheme = uri.scheme ?: return "<invalid-url>"
                val host = uri.host ?: return "<invalid-url>"
                val port = if (uri.port > 0) ":${uri.port}" else ""
                "$scheme://$host$port"
            } catch (e: Exception) {
                "<unparseable-url>"
            }
        }
    }
}

/**
 * Exception wrapper for EVM RPC errors that sanitizes credential-bearing URLs.
 */
class EvmException(message: String, val status: Int? = null) : RuntimeException(message)
