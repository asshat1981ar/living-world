package org.livingworld.ai.huggingface

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transport interface for dispatching HTTP requests to Hugging Face APIs or Spaces.
 * Ensures zero platform leaks in commonMain (Invariant G9).
 */
interface HfTransport {
    suspend fun executePost(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): Result<String>
}

/** Hugging Face answered with a non-2xx status; [status] lets callers tell a bad token from an outage. */
class HfHttpError(val status: Int) : Exception("Hugging Face returned HTTP $status")

/** Hugging Face did not answer within the configured timeout. */
class HfTimeout(timeoutMillis: Long) : Exception("Hugging Face request timed out after $timeoutMillis ms")

/**
 * Mock transport for deterministic testing and headless simulation verification without network access.
 */
class MockHfTransport(
    private val mockResponseProvider: (url: String, body: String) -> Result<String>
) : HfTransport {
    override suspend fun executePost(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): Result<String> = mockResponseProvider(url, jsonBody)
}

/**
 * Offline transport that always triggers deterministic fallback (Invariant G7).
 */
class OfflineHfTransport : HfTransport {
    override suspend fun executePost(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): Result<String> = Result.failure(IllegalStateException("Hugging Face transport offline / no network configured"))
}

/**
 * One bounded request: a hang becomes a failed Result (so callers fall back deterministically, G7), any other
 * failure is reported the same way, and genuine cancellation of the caller is never swallowed.
 */
suspend fun HfTransport.postBounded(
    config: HuggingFaceEndpointConfig,
    jsonBody: String,
    headers: Map<String, String>
): Result<String> = try {
    // withTimeoutOrNull only reports OUR timeout as null; a cancellation coming from the caller still propagates.
    withTimeoutOrNull(config.timeoutMillis) { executePost(config.effectiveUrl, jsonBody, headers) }
        ?: Result.failure(HfTimeout(config.timeoutMillis))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
