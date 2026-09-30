package com.octo.api.ingestion

import com.octo.api.ingestion.EvmSyncRunner.Companion.redactUrls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

// #343: the poll-failure log line must never carry the RPC credential.
class EvmSyncRunnerTest {
    @Test
    fun `a bad rpc url's own exception message loses the path key`() {
        val message =
            runCatching { URI.create("https://arb-mainnet.g.alchemy.com/v2/SECRETKEY with space") }
                .exceptionOrNull()!!
                .message
        assertThat(message).contains("SECRETKEY")
        assertThat(redactUrls(message))
            .doesNotContain("SECRETKEY")
            .contains("https://arb-mainnet.g.alchemy.com/<redacted>")
    }

    @Test
    fun `query keys and userinfo are dropped, host and port kept`() {
        val out = redactUrls("connect to https://user:pw@rpc.example.com:8443/rpc?apikey=SECRETKEY failed")
        assertThat(out)
            .isEqualTo("connect to https://rpc.example.com:8443/<redacted> failed")
    }

    @Test
    fun `text without urls passes through and a null message is named`() {
        assertThat(redactUrls("evm rpc eth_getLogs http 429")).isEqualTo("evm rpc eth_getLogs http 429")
        assertThat(redactUrls(null)).isEqualTo("(no message)")
    }
}
