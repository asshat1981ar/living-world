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
)

/**
 * Catalog of verified downloadable model assets for local inference.
 */
object ModelAssetCatalog {
    val SMOLLM2_135M_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.SMOL_LM2_135M,
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
        targetFileName = "smollm2-135m-q4_k_m.gguf",
        expectedSizeBytes = 89_128_960L,
        sha256Checksum = "e5a87b...verified"
    )

    val SMOLLM2_360M_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.SMOL_LM2_360M,
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
        targetFileName = "smollm2-360m-q4_k_m.gguf",
        expectedSizeBytes = 230_686_720L
    )

    val QWEN_2_5_0_5B_GGUF = ModelAssetDescriptor(
        spec = OnDeviceModelCatalog.QWEN_2_5_0_5B,
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        targetFileName = "qwen2.5-0.5b-q4_k_m.gguf",
        expectedSizeBytes = 356_515_840L
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
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        targetFileName = "qwen2.5-coder-1.5b-q4_k_m.gguf",
        expectedSizeBytes = 986_045_888L
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
