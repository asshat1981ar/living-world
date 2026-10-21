package com.livingworld.game.android.ai

import org.livingworld.ai.ondevice.ModelAssetDescriptor
import org.livingworld.ai.ondevice.ModelAssetManager
import org.livingworld.ai.ondevice.ModelAssetStatus
import org.livingworld.ai.ondevice.ModelIntegrity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Where on-device model files live, and whether one is exactly the file its pinned descriptor promises. */
class AndroidModelAssetManager(
    private val modelsBaseDir: File
) : ModelAssetManager {

    override fun getStatus(descriptor: ModelAssetDescriptor): ModelAssetStatus {
        val targetFile = File(modelsBaseDir, descriptor.targetFileName)
        if (!targetFile.exists() || !targetFile.isFile) {
            return ModelAssetStatus.NotInstalled
        }
        val length = targetFile.length()
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
