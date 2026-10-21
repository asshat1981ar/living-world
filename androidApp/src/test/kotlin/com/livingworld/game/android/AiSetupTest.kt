package com.livingworld.game.android

import com.livingworld.game.android.ai.AiEligibility
import com.livingworld.game.android.ai.AiSettingsRepository
import com.livingworld.game.android.ai.InMemoryKeyValueStore
import com.livingworld.game.android.ai.ModelDownloader
import com.livingworld.game.android.ai.SpeedVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.livingworld.ai.ondevice.ModelAssetCatalog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/** On-device AI setup: who may run it, how it is switched on and off, and how the model gets onto the phone. */
class AiSetupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val gb = 1024L * 1024 * 1024

    @Test
    fun only_64_bit_phones_that_are_not_low_ram_may_run_the_ai_and_only_big_ones_get_the_large_model() {
        assertTrue(AiEligibility.decide(is64Bit = false, isLowRamDevice = false, totalRamBytes = 8 * gb) is AiEligibility.Unsupported)
        assertTrue(AiEligibility.decide(is64Bit = true, isLowRamDevice = true, totalRamBytes = 8 * gb) is AiEligibility.Unsupported)
        assertTrue(AiEligibility.decide(is64Bit = true, isLowRamDevice = false, totalRamBytes = 3 * gb) is AiEligibility.Unsupported)

        val mid = AiEligibility.decide(is64Bit = true, isLowRamDevice = false, totalRamBytes = 4 * gb) as AiEligibility.Eligible
        assertFalse(mid.highQualityAllowed)
        val big = AiEligibility.decide(is64Bit = true, isLowRamDevice = false, totalRamBytes = 8 * gb) as AiEligibility.Eligible
        assertTrue(big.highQualityAllowed)
        val reason = (AiEligibility.decide(false, false, 8 * gb) as AiEligibility.Unsupported).reason
        assertTrue(reason.contains("64-bit"))
    }

    @Test
    fun the_ai_starts_off_uses_the_default_model_and_turns_itself_off_after_a_crash() {
        val settings = AiSettingsRepository(InMemoryKeyValueStore())
        assertFalse(settings.enabled)
        assertEquals(ModelAssetCatalog.DEFAULT, settings.model)

        settings.enable()
        settings.chooseModel(ModelAssetCatalog.QWEN3_1_7B_LITERTLM)
        assertTrue(settings.enabled)
        assertEquals(ModelAssetCatalog.QWEN3_1_7B_LITERTLM, settings.model)

        settings.recordCrash()
        assertFalse("one crash turns the AI off", settings.enabled)
        assertNotNull(settings.autoDisabledReason)
        settings.enable()
        assertTrue(settings.enabled)
        assertNull("turning it back on clears the notice", settings.autoDisabledReason)
    }

    @Test
    fun settings_survive_a_restart() {
        val store = InMemoryKeyValueStore()
        AiSettingsRepository(store).apply { enable(); chooseModel(ModelAssetCatalog.QWEN3_1_7B_LITERTLM) }
        val reopened = AiSettingsRepository(store)
        assertTrue(reopened.enabled)
        assertEquals(ModelAssetCatalog.QWEN3_1_7B_LITERTLM, reopened.model)
    }

    @Test
    fun a_slow_test_reply_recommends_staying_off() {
        assertEquals(SpeedVerdict.GOOD, SpeedVerdict.of(millisForReply = 4_000))
        assertEquals(SpeedVerdict.SLOW, SpeedVerdict.of(millisForReply = 15_000))
        assertEquals(SpeedVerdict.TOO_SLOW, SpeedVerdict.of(millisForReply = 40_000))
    }

    private val body = ByteArray(1000) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val asset = ModelAssetCatalog.DEFAULT.copy(targetFileName = "m.litertlm", expectedSizeBytes = body.size.toLong(), sha256Checksum = sha(body))

    @Test
    fun a_download_is_verified_then_moved_into_place_with_progress() = runTest {
        val progress = mutableListOf<Long>()
        val downloader = ModelDownloader(tmp.root, chunkBytes = 300) { url ->
            assertEquals(asset.downloadUrl, url)
            ByteArrayInputStream(body)
        }
        val file = downloader.download(asset) { done, _ -> progress += done }.getOrThrow()
        assertArrayEquals(body, file.readBytes())
        assertEquals(1000L, progress.last())
        assertTrue(downloader.isInstalled(asset))
        assertEquals(listOf("m.litertlm"), tmp.root.list()!!.toList())
    }

    @Test
    fun a_wrong_or_broken_download_leaves_nothing_behind() = runTest {
        val wrong = ModelDownloader(tmp.root) { ByteArrayInputStream(ByteArray(1000) { 7 }) }
        assertTrue(wrong.download(asset) { _, _ -> }.isFailure)
        val dropped = ModelDownloader(tmp.root) { object : java.io.InputStream() { override fun read(): Int = throw IOException("connection lost") } }
        assertTrue(dropped.download(asset) { _, _ -> }.isFailure)
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
        assertFalse(wrong.isInstalled(asset))
    }

    @Test
    fun an_installed_model_is_not_downloaded_again_and_can_be_deleted() = runTest {
        var opened = 0
        val downloader = ModelDownloader(tmp.root) { opened++; ByteArrayInputStream(body) }
        downloader.download(asset) { _, _ -> }.getOrThrow()
        downloader.download(asset) { _, _ -> }.getOrThrow()
        assertEquals(1, opened)
        downloader.delete(asset)
        assertFalse(downloader.isInstalled(asset))
    }
}
