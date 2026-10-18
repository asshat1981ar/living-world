package com.livingworld.game.android

import com.livingworld.game.android.ai.AndroidLlamaEngineBridge
import com.livingworld.game.android.ai.AndroidModelAssetManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.livingworld.ai.ondevice.ModelAssetCatalog
import org.livingworld.ai.ondevice.ModelAssetDescriptor
import org.livingworld.ai.ondevice.ModelAssetStatus
import java.security.MessageDigest

class ModelAssetIntegrityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(64) { it.toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun descriptor(sha: String?, size: Long = content.size.toLong()): ModelAssetDescriptor =
        ModelAssetCatalog.QWEN_2_5_0_5B_GGUF.copy(targetFileName = "m.gguf", expectedSizeBytes = size, sha256Checksum = sha)

    private fun install(bytes: ByteArray = content) = tmp.newFile("m.gguf").apply { writeBytes(bytes) }

    @Test
    fun a_file_with_the_pinned_size_and_digest_is_ready_and_verifies() {
        val file = install()
        val d = descriptor(sha(content))
        val manager = AndroidModelAssetManager(tmp.root)
        assertTrue(manager.getStatus(d) is ModelAssetStatus.Ready)
        assertTrue(manager.verifyChecksum(d, file.absolutePath))
    }

    @Test
    fun same_size_but_different_bytes_fails_verification() {
        val file = install(ByteArray(64) { 7 })
        assertFalse(AndroidModelAssetManager(tmp.root).verifyChecksum(descriptor(sha(content)), file.absolutePath))
    }

    @Test
    fun wrong_size_is_an_error_not_ready() {
        install(ByteArray(40) { 1 })
        val status = AndroidModelAssetManager(tmp.root).getStatus(descriptor(sha(ByteArray(40) { 1 })))
        assertTrue("a size mismatch must not be Ready: $status", status is ModelAssetStatus.Error)
    }

    @Test
    fun unpinned_or_fake_verified_descriptors_never_verify_or_become_ready() {
        val file = install()
        val manager = AndroidModelAssetManager(tmp.root)
        for (bad in listOf(null, "", "verified", "smollm2-verified")) {
            assertFalse("'$bad' must not verify", manager.verifyChecksum(descriptor(bad), file.absolutePath))
            assertTrue(manager.getStatus(descriptor(bad)) is ModelAssetStatus.Error)
        }
    }

    @Test
    fun the_bundled_303_byte_stubs_are_not_ready() {
        val status = AndroidModelAssetManager(java.io.File("../assets/models")).getStatus(ModelAssetCatalog.SMOLLM2_135M_GGUF)
        assertFalse("a header stub is not a model: $status", status is ModelAssetStatus.Ready)
    }

    @Test
    fun bridge_without_a_native_runtime_refuses_to_invent_output() = runTest {
        val gguf = tmp.newFile("real.gguf").apply {
            val header = ByteArray(32)
            "GGUF".toByteArray().copyInto(header)
            header[4] = 3
            writeBytes(header)
        }
        val bridge = AndroidLlamaEngineBridge()
        assertTrue(bridge.loadModel(gguf.absolutePath, threads = 2, contextSize = 2048))
        try {
            bridge.generate("hello", 8, 0.2f)
            fail("generate() must not fabricate output without a runtime")
        } catch (_: IllegalStateException) {
        }
    }
}
