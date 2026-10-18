package org.livingworld.ai.huggingface

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Supply chain: the bearer token only ever goes to Hugging Face, never prints or serializes, and requests are bounded. */
class HuggingFaceEndpointConfigTest {
    private val token = "hf_SECRET_token_123"

    @Test
    fun hosts_are_allow_listed_and_https_only() {
        for (ok in listOf("https://huggingface.co/x", "https://router.huggingface.co/v1", "https://my-space.hf.space/api", "https://MY.HF.SPACE:443/x"))
            assertTrue(HuggingFaceEndpointConfig.isAllowedHost(ok), ok)
        for (bad in listOf(
            "http://my-space.hf.space/", "https://evil.example/", "https://huggingface.co.evil.example/", "https://evilhuggingface.co/",
            "https://huggingface.co@evil.example/", "https://evil.example\\@huggingface.co/", "https://hf.space.evil.example/", "ftp://huggingface.co/", "https:///x", ""
        )) assertFalse(HuggingFaceEndpointConfig.isAllowedHost(bad), bad)
    }

    @Test
    fun a_config_pointing_at_a_foreign_host_cannot_be_built() {
        assertFailsWith<IllegalArgumentException> { HuggingFaceEndpointConfig.freeSpace("https://evil.example/space") }
        assertFailsWith<IllegalArgumentException> { HuggingFaceEndpointConfig.freeSpace("http://my.hf.space/") }
        assertFailsWith<IllegalArgumentException> { HuggingFaceEndpointConfig(endpointUrl = "https://evil.example/models/") }
        assertFailsWith<IllegalArgumentException> { HuggingFaceEndpointConfig(timeoutMillis = 0) }
        assertEquals("https://my.hf.space/run", HuggingFaceEndpointConfig.freeSpace("https://my.hf.space/run").effectiveUrl)
    }

    @Test
    fun the_token_neither_prints_nor_serializes() {
        val config = HuggingFaceEndpointConfig.defaultFreeTier(apiToken = token)
        assertEquals(token, config.apiToken, "still available at runtime for the Authorization header")
        assertFalse(token in config.toString())
        assertFalse(token in Json.encodeToString(config))
    }

    private val slowTransport = object : HfTransport {
        override suspend fun executePost(url: String, jsonBody: String, headers: Map<String, String>): Result<String> {
            delay(1_000_000); return Result.success("late")
        }
    }

    @Test
    fun a_hung_endpoint_becomes_a_failure_after_the_timeout() = runTest {
        val result = slowTransport.postBounded(HuggingFaceEndpointConfig(timeoutMillis = 50), "{}", emptyMap())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("timed out"))
    }

    @Test
    fun an_outer_cancellation_is_not_swallowed_as_a_failure() = runTest {
        assertFailsWith<CancellationException> {
            withTimeout(10) { slowTransport.postBounded(HuggingFaceEndpointConfig(timeoutMillis = 1_000_000), "{}", emptyMap()) }
        }
    }

    @Test
    fun a_throwing_transport_is_a_failure_not_a_crash() = runTest {
        val broken = object : HfTransport {
            override suspend fun executePost(url: String, jsonBody: String, headers: Map<String, String>): Result<String> = error("boom")
        }
        assertTrue(broken.postBounded(HuggingFaceEndpointConfig(), "{}", emptyMap()).isFailure)
    }
}
