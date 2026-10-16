package org.livingworld.ai.huggingface

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
