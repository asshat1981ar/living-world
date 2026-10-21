package org.livingworld.ai.ondevice

import kotlin.test.*

/** Supply chain: a model descriptor must vouch for exactly one file (pinned revision, size and sha256). */
class ModelIntegrityTest {
    private val pinnedSha = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    private fun descriptor(sha: String?, size: Long = 100L) = ModelAssetCatalog.QWEN3_1_7B_LITERTLM.copy(expectedSizeBytes = size, sha256Checksum = sha)

    @Test
    fun every_catalog_entry_is_fully_pinned() {
        for (d in ModelAssetCatalog.ALL_ASSETS) {
            assertNull(ModelIntegrity.pinProblem(d), "${d.targetFileName} must carry a real sha256 and size")
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(d.downloadUrl), "${d.targetFileName} URL must pin a commit, not 'main'")
        }
    }

    @Test
    fun missing_blank_verified_or_malformed_hashes_are_never_trusted() {
        for (bad in listOf(null, "", "   ", "verified", "smollm2-verified", "abc123", pinnedSha.dropLast(1), pinnedSha.replace('7', 'z'))) {
            assertNotNull(ModelIntegrity.pinProblem(descriptor(bad)), "'$bad' must be rejected")
            assertFalse(descriptor(bad).isPinned)
            assertFalse(ModelIntegrity.digestMatches(descriptor(bad), bad ?: ""), "'$bad' must not verify anything")
        }
        assertNotNull(ModelIntegrity.pinProblem(descriptor(pinnedSha, size = 0L)))
    }

    @Test
    fun size_must_match_exactly() {
        val d = descriptor(pinnedSha, size = 100L)
        assertNull(ModelIntegrity.sizeProblem(d, 100L))
        assertNotNull(ModelIntegrity.sizeProblem(d, 99L))
        assertNotNull(ModelIntegrity.sizeProblem(d, 303L), "a 303-byte header stub is not a model")
    }

    @Test
    fun digest_comparison_ignores_case_but_nothing_else() {
        val d = descriptor(pinnedSha)
        assertTrue(ModelIntegrity.digestMatches(d, pinnedSha.uppercase()))
        assertFalse(ModelIntegrity.digestMatches(d, pinnedSha.replace('7', '8')))
    }

}
