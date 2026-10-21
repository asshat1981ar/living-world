package org.livingworld.ai.ondevice

import kotlinx.serialization.Serializable

/**
 * Status of an on-device model asset on the local client device.
 */
@Serializable
sealed interface ModelAssetStatus {
    @Serializable
    data object NotInstalled : ModelAssetStatus

    @Serializable
    data class Downloading(val progressPercent: Float, val bytesDownloaded: Long, val totalBytes: Long) : ModelAssetStatus

    @Serializable
    data class Ready(val localPath: String, val fileSizeBytes: Long) : ModelAssetStatus

    @Serializable
    data class Error(val message: String, val canRetry: Boolean = true) : ModelAssetStatus
}

/**
 * Descriptor containing remote URL, expected hashes, and target filenames for on-device deployment.
 */
@Serializable
data class ModelAssetDescriptor(
    /** Shown to the player, e.g. in the AI tab. */
    val displayName: String,
    val downloadUrl: String,
    val targetFileName: String,
    val expectedSizeBytes: Long,
    val sha256Checksum: String? = null
) {
    /** A descriptor is only trusted once it carries a real 64-hex sha256 and a positive size. */
    val isPinned: Boolean get() = ModelIntegrity.pinProblem(this) == null
}

/**
 * Decides whether a model file is the one a descriptor promises. There is no "verified" escape hatch: a
 * descriptor without a real sha256 and size is never trusted, and a file is accepted only when both its size
 * and its digest match.
 */
object ModelIntegrity {
    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    /** Why [descriptor] cannot vouch for any file, or null when it is fully pinned. */
    fun pinProblem(descriptor: ModelAssetDescriptor): String? {
        val sha = descriptor.sha256Checksum
        return when {
            descriptor.expectedSizeBytes <= 0L -> "${descriptor.targetFileName}: expected size is not pinned"
            sha.isNullOrBlank() || !SHA256_HEX.matches(sha) -> "${descriptor.targetFileName}: sha256 is not pinned"
            else -> null
        }
    }

    /** Null when [actualSize] is acceptable, else the reason it is not. */
    fun sizeProblem(descriptor: ModelAssetDescriptor, actualSize: Long): String? =
        pinProblem(descriptor)
            ?: if (actualSize != descriptor.expectedSizeBytes)
                "${descriptor.targetFileName}: size $actualSize does not match expected ${descriptor.expectedSizeBytes}"
            else null

    fun digestMatches(descriptor: ModelAssetDescriptor, actualSha256Hex: String): Boolean =
        pinProblem(descriptor) == null && descriptor.sha256Checksum.equals(actualSha256Hex, ignoreCase = true)
}

/**
 * The on-device dialogue model, pinned to a repository commit, size and sha256 (Hugging Face's published LFS values,
 * checked 2026-10-21). It is Apache-2.0 and ungated, so downloading it needs no account or token.
 */
object ModelAssetCatalog {
    /** Qwen3-1.7B for LiteRT-LM, int4 weights: the chosen balance of line quality and download size. */
    val QWEN3_1_7B_LITERTLM = ModelAssetDescriptor(
        displayName = "Qwen3 1.7B",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/79bf0667b32ae025b972e5fe555fe72ca6e8816c/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        targetFileName = "qwen3-1.7b-int4.litertlm",
        expectedSizeBytes = 977_184_032L,
        sha256Checksum = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8"
    )

    val ALL_ASSETS = listOf(QWEN3_1_7B_LITERTLM)
}

/**
 * Interface for checking, loading, and verifying on-device model files.
 */
interface ModelAssetManager {
    fun getStatus(descriptor: ModelAssetDescriptor): ModelAssetStatus
    fun verifyChecksum(descriptor: ModelAssetDescriptor, filePath: String): Boolean
}
