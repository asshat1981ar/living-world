package org.livingworld.ai.ondevice

import kotlinx.serialization.Serializable
import org.livingworld.ai.huggingface.OnDeviceModelSpec
import org.livingworld.ai.huggingface.OnDeviceModelCatalog

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
    val spec: OnDeviceModelSpec,
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
 * Catalog of downloadable model assets, each pinned to a repository commit, size and sha256 for local inference.
 */
object ModelAssetCatalog {
    val SMOLLM2_135M_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.SMOL_LM2_135M,
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/09816acd5d99df7be770d85ea30822623dab342c/SmolLM2-135M-Instruct-Q4_K_M.gguf",
        targetFileName = "smollm2-135m-q4_k_m.gguf",
        expectedSizeBytes = 105_454_432L,
        sha256Checksum = "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d"
    )

    val SMOLLM2_360M_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.SMOL_LM2_360M,
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/7be6f65f1db715fe5dc5a4634c0d459b4eed42ec/SmolLM2-360M-Instruct-Q4_K_M.gguf",
        targetFileName = "smollm2-360m-q4_k_m.gguf",
        expectedSizeBytes = 270_590_880L,
        sha256Checksum = "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2"
    )

    val QWEN_2_5_0_5B_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.QWEN_2_5_0_5B,
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        targetFileName = "qwen2.5-0.5b-q4_k_m.gguf",
        // Size and sha256 for every entry are Hugging Face's published values (x-linked-size / x-linked-etag at the pinned commit, 2026-10-18); only Qwen 0.5B has been hashed locally so far.
        expectedSizeBytes = 491_400_032L,
        sha256Checksum = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    )

    val QWEN_2_5_CODER_1_5B_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelSpec(
            modelId = "Qwen/Qwen2.5-Coder-1.5B-Instruct",
            family = "Qwen2.5-Coder",
            parameterCount = "1.5B",
            int4MemoryMb = 986,
            contextWindowTokens = 32768,
            recommendedEngines = listOf("llama.cpp (Termux)", "ExecuTorch", "Desktop GGUF"),
            huggingFaceUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            optimalTask = "High-reasoning mobile edge deployment via user Drive model"
        ),
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/f86cb2c1fa58255f8052cc32aeede1b7482d4361/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        targetFileName = "qwen2.5-coder-1.5b-q4_k_m.gguf",
        expectedSizeBytes = 1_117_320_768L,
        sha256Checksum = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046"
    )

    val ALL_ASSETS = listOf(
        SMOLLM2_135M_GGUF,
        SMOLLM2_360M_GGUF,
        QWEN_2_5_0_5B_GGUF,
        QWEN_2_5_CODER_1_5B_GGUF
    )
}

/**
 * Interface for checking, loading, and verifying on-device model files.
 */
interface ModelAssetManager {
    fun getStatus(descriptor: ModelAssetDescriptor): ModelAssetStatus
    fun verifyChecksum(descriptor: ModelAssetDescriptor, filePath: String): Boolean
}
