package com.livingworld.game.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.livingworld.ai.ondevice.ModelAssetCatalog
import org.livingworld.ai.ondevice.ModelAssetDescriptor
import org.livingworld.ai.ondevice.ModelIntegrity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Whether this phone may run the on-device dialogue AI at all, decided from facts Android reports. */
sealed interface AiEligibility {
    data class Eligible(val highQualityAllowed: Boolean) : AiEligibility
    data class Unsupported(val reason: String) : AiEligibility

    companion object {
        private const val GIB = 1024L * 1024 * 1024
        /** Phones sold as 4 GB report about 3.6 GiB; below this the model would crowd out the game itself. */
        private const val MIN_RAM_BYTES = 34L * GIB / 10
        /** Phones sold as 8 GB report about 7.3 GiB; the 1 GB model is only offered from there. */
        private const val HIGH_QUALITY_RAM_BYTES = 7L * GIB

        fun decide(is64Bit: Boolean, isLowRamDevice: Boolean, totalRamBytes: Long): AiEligibility = when {
            !is64Bit -> Unsupported("This phone runs 32-bit apps only; the on-device AI needs a 64-bit phone.")
            isLowRamDevice -> Unsupported("Android marks this phone as low on memory, so the on-device AI stays off.")
            totalRamBytes < MIN_RAM_BYTES -> Unsupported("This phone has too little memory for the on-device AI.")
            else -> Eligible(highQualityAllowed = totalRamBytes >= HIGH_QUALITY_RAM_BYTES)
        }
    }
}

/** How long a test reply took, and what that means for the player. */
enum class SpeedVerdict {
    GOOD, SLOW, TOO_SLOW;

    companion object {
        fun of(millisForReply: Long): SpeedVerdict = when {
            millisForReply < 10_000 -> GOOD
            millisForReply < 30_000 -> SLOW
            else -> TOO_SLOW
        }
    }
}

/** A tiny key-value store, so settings logic can be tested without Android (the app backs it with SharedPreferences). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = LinkedHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * The player's on-device AI choices. Off until the player turns it on; one crash of the AI process turns it off again
 * and leaves a notice, so a phone that cannot cope never keeps crashing.
 */
class AiSettingsRepository(private val store: KeyValueStore) {
    val enabled: Boolean get() = store.get(ENABLED) == "true"

    val model: ModelAssetDescriptor
        get() = ModelAssetCatalog.ALL_ASSETS.firstOrNull { it.targetFileName == store.get(MODEL) } ?: ModelAssetCatalog.DEFAULT

    val autoDisabledReason: String? get() = store.get(AUTO_DISABLED)

    fun enable() {
        store.put(ENABLED, "true")
        store.put(AUTO_DISABLED, null)
    }

    fun disable() = store.put(ENABLED, "false")

    fun chooseModel(model: ModelAssetDescriptor) = store.put(MODEL, model.targetFileName)

    fun recordCrash() {
        store.put(ENABLED, "false")
        store.put(AUTO_DISABLED, "The on-device AI stopped unexpectedly, so it was turned off. You can turn it back on.")
    }

    private companion object {
        const val ENABLED = "ai_enabled"
        const val MODEL = "ai_model"
        const val AUTO_DISABLED = "ai_auto_disabled"
    }
}

/**
 * Puts a pinned model file into [directory]: streamed to `<name>.part`, checked for the exact size and sha256, then
 * renamed. A wrong, short or interrupted download leaves nothing behind. Only https huggingface.co URLs are opened.
 */
class ModelDownloader(
    private val directory: File,
    private val chunkBytes: Int = 1 shl 20,
    private val open: (url: String) -> InputStream
) {
    fun fileFor(model: ModelAssetDescriptor) = File(directory, model.targetFileName)

    /** Present with the pinned size; the sha256 was checked when it was downloaded. */
    fun isInstalled(model: ModelAssetDescriptor): Boolean =
        fileFor(model).let { it.isFile && ModelIntegrity.sizeProblem(model, it.length()) == null }

    fun delete(model: ModelAssetDescriptor) {
        fileFor(model).delete()
        File(directory, model.targetFileName + ".part").delete()
    }

    suspend fun download(model: ModelAssetDescriptor, progress: (done: Long, total: Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            val target = fileFor(model)
            if (isInstalled(model)) return@withContext Result.success(target)
            val part = File(directory, model.targetFileName + ".part")
            try {
                ModelIntegrity.pinProblem(model)?.let { throw IllegalStateException(it) }
                require(model.downloadUrl.startsWith("https://huggingface.co/")) { "Models are only downloaded from huggingface.co" }
                directory.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                open(model.downloadUrl).use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(chunkBytes)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > model.expectedSizeBytes) throw IllegalStateException("The download is larger than expected.")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                            progress(done, model.expectedSizeBytes)
                        }
                    }
                }
                ModelIntegrity.sizeProblem(model, done)?.let { throw IllegalStateException(it) }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                check(ModelIntegrity.digestMatches(model, hex)) { "The downloaded file does not match its checksum." }
                check(part.renameTo(target)) { "Could not move the model into place." }
                Result.success(target)
            } catch (e: Exception) {
                part.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }
}
