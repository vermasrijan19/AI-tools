package com.vermasrijan.pixelnpu.agent.litert

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/** LLM provider identifier for on-device Android inference via the LiteRT runtime. */
data object LiteRTLLMProvider : LLMProvider("android-litert", "LiteRT")

/**
 * Gemma 4 E2B builds from https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm.
 *
 * Each model's [LLModel.id] is the `.litertlm` file name the client loads from its models directory.
 */
object Gemma4Models {
    /** Generic build with CPU and GPU graphs only; runs on any arm64 Android device. */
    val E2B: LLModel = LLModel(
        provider = LiteRTLLMProvider,
        id = "gemma-4-E2B-it.litertlm",
        capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
        contextLength = 32_000,
        maxOutputTokens = 4_096,
    )

    /**
     * Build compiled ahead of time for the TPU of one Tensor generation, keyed by the
     * normalized `Build.SOC_MODEL` (e.g. "tensor g6"). These builds report a 4,096-token
     * context at runtime.
     */
    private val tensorNpuBuilds: Map<String, LLModel> = mapOf(
        "tensor g5" to tensorNpuBuild("gemma-4-E2B-it_Google_Tensor_G5.litertlm"),
        "tensor g6" to tensorNpuBuild("gemma-4-E2B-it_Google_Tensor_G6.litertlm"),
    )

    /** Returns the NPU build for [socModel] (as reported by `Build.SOC_MODEL`), or `null` if none exists. */
    fun forTensorNpu(socModel: String): LLModel? =
        tensorNpuBuilds[socModel.trim().lowercase().replace('_', ' ')]

    private fun tensorNpuBuild(fileName: String) = LLModel(
        provider = LiteRTLLMProvider,
        id = fileName,
        capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
        contextLength = 4_096,
        maxOutputTokens = 1_024,
    )
}
