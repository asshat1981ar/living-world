package com.livingworld.game.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.livingworld.ai.ondevice.GgufBinaryHeaderParser
import org.livingworld.ai.ondevice.GgufMetadata
import org.livingworld.ai.ondevice.ModelAssetDescriptor
import org.livingworld.ai.ondevice.ModelAssetManager
import org.livingworld.ai.ondevice.ModelIntegrity
import org.livingworld.ai.ondevice.ModelAssetStatus
import org.livingworld.ai.reasoning.LlamaEngineBridge
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class AndroidModelAssetManager(
    private val modelsBaseDir: File
) : ModelAssetManager {

    override fun getStatus(descriptor: ModelAssetDescriptor): ModelAssetStatus {
        val targetFile = File(modelsBaseDir, descriptor.targetFileName)
        if (!targetFile.exists() || !targetFile.isFile) {
            return ModelAssetStatus.NotInstalled
        }
        val length = targetFile.length()
        if (length < GgufBinaryHeaderParser.MIN_HEADER_BYTES) {
            return ModelAssetStatus.Error("Model file ${descriptor.targetFileName} is truncated ($length bytes)")
        }
        // A file is only Ready when the descriptor is pinned and the size matches; the digest is checked by verifyChecksum.
        ModelIntegrity.sizeProblem(descriptor, length)?.let { return ModelAssetStatus.Error(it) }
        return ModelAssetStatus.Ready(localPath = targetFile.absolutePath, fileSizeBytes = length)
    }

    override fun verifyChecksum(descriptor: ModelAssetDescriptor, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return false
        if (ModelIntegrity.sizeProblem(descriptor, file.length()) != null) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read = fis.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = fis.read(buffer)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            ModelIntegrity.digestMatches(descriptor, hex)
        } catch (_: Exception) {
            false
        }
    }
}

class AndroidLlamaEngineBridge(
    private val nativeJniCompleter: ((prompt: String, maxTokens: Int, temperature: Float) -> String)? = null
) : LlamaEngineBridge {

    private var loadedMetadata: GgufMetadata? = null
    private var activeModelPath: String? = null

    override val isModelLoaded: Boolean
        get() = loadedMetadata != null && !activeModelPath.isNullOrBlank()

    val currentMetadata: GgufMetadata?
        get() = loadedMetadata

    override suspend fun loadModel(modelPath: String, threads: Int, contextSize: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (threads <= 0 || contextSize < 128) return@withContext false
            val file = File(modelPath)
            if (!file.exists() || !file.isFile || file.length() < GgufBinaryHeaderParser.MIN_HEADER_BYTES) {
                return@withContext false
            }
            val headerBytes = ByteArray(GgufBinaryHeaderParser.MIN_HEADER_BYTES)
            val bytesRead = FileInputStream(file).use { it.read(headerBytes) }
            if (bytesRead < GgufBinaryHeaderParser.MIN_HEADER_BYTES) {
                return@withContext false
            }

            val parsed = GgufBinaryHeaderParser.parseHeaderBytes(headerBytes, fallbackModelName = file.nameWithoutExtension)
            if (parsed.isFailure) {
                return@withContext false
            }

            loadedMetadata = parsed.getOrNull()
            activeModelPath = file.absolutePath
            true
        }

    override suspend fun generate(prompt: String, maxTokens: Int, temperature: Float): String =
        withContext(Dispatchers.Default) {
            check(isModelLoaded) { "Cannot invoke generate() when GGUF model is not loaded" }
            if (nativeJniCompleter != null) {
                return@withContext nativeJniCompleter.invoke(prompt, maxTokens, temperature)
            }
            // No native runtime attached: say so instead of inventing an answer, so callers fall back deterministically (G7).
            throw IllegalStateException("No native llama runtime is attached to AndroidLlamaEngineBridge")
        }

    override fun unload() {
        loadedMetadata = null
        activeModelPath = null
    }
}
