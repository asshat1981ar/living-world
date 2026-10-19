package org.livingworld.ai.huggingface

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Configuration for connecting to Hugging Face Inference API, Serverless Router, or Hugging Face Spaces.
 */
@Serializable
data class HuggingFaceEndpointConfig(
    val endpointUrl: String = "https://router.huggingface.co/hf-inference/models/",
    val modelId: String = "Qwen/Qwen2.5-7B-Instruct",
    /** Never serialized and never printed: the token is supplied at runtime and only ever sent to an allow-listed host. */
    @Transient val apiToken: String? = null,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 256,
    val useOpenAiChatFormat: Boolean = true,
    val isFreeSpace: Boolean = false,
    val customSpaceUrl: String? = null,
    /** Upper bound for one request; a slow or hung endpoint falls back to the deterministic path instead of stalling a tick. */
    val timeoutMillis: Long = 20_000L
) {
    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(isAllowedHost(endpointUrl)) { "endpointUrl must be https on a Hugging Face host" }
        if (!customSpaceUrl.isNullOrBlank()) {
            require(isAllowedHost(customSpaceUrl)) { "customSpaceUrl must be https on a Hugging Face host (huggingface.co, *.huggingface.co or *.hf.space)" }
        }
    }

    override fun toString(): String =
        "HuggingFaceEndpointConfig(modelId=$modelId, endpointUrl=$endpointUrl, apiToken=${if (apiToken.isNullOrBlank()) "none" else "***"}, " +
            "isFreeSpace=$isFreeSpace, customSpaceUrl=$customSpaceUrl, timeoutMillis=$timeoutMillis)"

    /**
     * Resolves the target URL based on whether connecting to a Free Space, Serverless Router, or Inference API.
     */
    val effectiveUrl: String
        get() = when {
            isFreeSpace && !customSpaceUrl.isNullOrBlank() -> customSpaceUrl
            useOpenAiChatFormat -> "https://router.huggingface.co/hf-inference/v1/chat/completions"
            else -> endpointUrl.trimEnd('/') + "/" + modelId
        }

    companion object {
        /** True only for https URLs whose host is Hugging Face's own; rejects userinfo tricks like https://huggingface.co@evil.example. */
        fun isAllowedHost(url: String): Boolean {
            if (!url.startsWith("https://", ignoreCase = true)) return false
            val authority = url.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
            if (authority.isEmpty() || authority.contains('@') || authority.contains('\\')) return false
            val host = authority.substringBefore(':').lowercase()
            if (authority.contains(':') && authority.substringAfter(':').any { !it.isDigit() }) return false
            return host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.space")
        }

        // Recommended free and low-cost open-weight models on Hugging Face
        const val MODEL_QWEN_2_5_7B = "Qwen/Qwen2.5-7B-Instruct"
        const val MODEL_LLAMA_3_2_3B = "meta-llama/Llama-3.2-3B-Instruct"
        const val MODEL_MISTRAL_7B = "mistralai/Mistral-7B-Instruct-v0.3"
        const val MODEL_ZEPHYR_7B = "HuggingFaceH4/zephyr-7b-beta"
        const val MODEL_DEEPSEEK_R1_7B = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B"

        fun defaultFreeTier(apiToken: String? = null): HuggingFaceEndpointConfig {
            return HuggingFaceEndpointConfig(
                modelId = MODEL_QWEN_2_5_7B,
                apiToken = apiToken,
                temperature = 0.3f,
                maxTokens = 256
            )
        }

        fun freeSpace(spaceUrl: String): HuggingFaceEndpointConfig {
            return HuggingFaceEndpointConfig(
                isFreeSpace = true,
                customSpaceUrl = spaceUrl,
                temperature = 0.3f
            )
        }
    }
}

@Serializable
data class HfChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class HfChatRequest(
    val model: String,
    val messages: List<HfChatMessage>,
    val temperature: Float = 0.3f,
    val max_tokens: Int = 256
)

@Serializable
data class HfChatChoice(
    val message: HfChatMessage,
    val finish_reason: String? = null
)

@Serializable
data class HfChatResponse(
    val id: String? = null,
    val choices: List<HfChatChoice> = emptyList()
)

@Serializable
data class HfGenerationParams(
    val max_new_tokens: Int = 256,
    val temperature: Float = 0.3f,
    val return_full_text: Boolean = false
)

@Serializable
data class HfTextGenerationRequest(
    val inputs: String,
    val parameters: HfGenerationParams = HfGenerationParams()
)

@Serializable
data class HfTextGenerationResult(
    val generated_text: String = ""
)

@Serializable
data class ReasoningJsonResponse(
    val selected_index: Int,
    val confidence: Float = 0.8f,
    val explanation: String? = null
)


/**
 * Detailed specification for on-device / local small language models on Hugging Face.
 */
@Serializable
data class OnDeviceModelSpec(
    val modelId: String,
    val family: String,
    val parameterCount: String,
    val int4MemoryMb: Int,
    val contextWindowTokens: Int,
    val recommendedEngines: List<String>,
    val huggingFaceUrl: String,
    val optimalTask: String
)

object OnDeviceModelCatalog {
    val SMOL_LM2_135M = OnDeviceModelSpec(
        modelId = "HuggingFaceTB/SmolLM2-135M-Instruct",
        family = "SmolLM2",
        parameterCount = "135M",
        int4MemoryMb = 85,
        contextWindowTokens = 2048,
        recommendedEngines = listOf("llama.cpp (GGUF)", "Transformers.js (ONNX)", "MediaPipe LiteRT"),
        huggingFaceUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct",
        optimalTask = "Ultra-fast reactive NPC intent ranking and mobile background simulation"
    )

    val SMOL_LM2_360M = OnDeviceModelSpec(
        modelId = "HuggingFaceTB/SmolLM2-360M-Instruct",
        family = "SmolLM2",
        parameterCount = "360M",
        int4MemoryMb = 220,
        contextWindowTokens = 4096,
        recommendedEngines = listOf("llama.cpp (GGUF)", "ONNX Runtime Mobile", "MediaPipe LiteRT"),
        huggingFaceUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct",
        optimalTask = "Structured JSON decision extraction and routine NPC dialogue"
    )

    val QWEN_2_5_0_5B = OnDeviceModelSpec(
        modelId = "Qwen/Qwen2.5-0.5B-Instruct",
        family = "Qwen2.5",
        parameterCount = "490M",
        int4MemoryMb = 340,
        contextWindowTokens = 32768,
        recommendedEngines = listOf("MediaPipe LiteRT (litert-community)", "llama.cpp (GGUF)", "ONNX Runtime"),
        huggingFaceUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct",
        optimalTask = "Accurate multi-candidate utility reasoning and long-term memory scanning"
    )

    val LLAMA_3_2_1B = OnDeviceModelSpec(
        modelId = "meta-llama/Llama-3.2-1B-Instruct",
        family = "Llama 3.2",
        parameterCount = "1.23B",
        int4MemoryMb = 720,
        contextWindowTokens = 131072,
        recommendedEngines = listOf("ExecuTorch", "llama.cpp (GGUF)", "MediaPipe LiteRT"),
        huggingFaceUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct",
        optimalTask = "High-fidelity in-character dialogue and complex deception detection"
    )

    val SMOL_LM2_1_7B = OnDeviceModelSpec(
        modelId = "HuggingFaceTB/SmolLM2-1.7B-Instruct",
        family = "SmolLM2",
        parameterCount = "1.71B",
        int4MemoryMb = 980,
        contextWindowTokens = 8192,
        recommendedEngines = listOf("llama.cpp (GGUF)", "ExecuTorch", "ONNX Runtime"),
        huggingFaceUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct",
        optimalTask = "High-reasoning strategic planning, faction negotiations, and lore derivation"
    )

    val ALL_ON_DEVICE_MODELS: List<OnDeviceModelSpec> = listOf(
        SMOL_LM2_135M,
        SMOL_LM2_360M,
        QWEN_2_5_0_5B,
        LLAMA_3_2_1B,
        SMOL_LM2_1_7B
    )

    fun findForMemoryBudget(maxMemoryMb: Int): List<OnDeviceModelSpec> {
        return ALL_ON_DEVICE_MODELS.filter { it.int4MemoryMb <= maxMemoryMb }
    }
}
