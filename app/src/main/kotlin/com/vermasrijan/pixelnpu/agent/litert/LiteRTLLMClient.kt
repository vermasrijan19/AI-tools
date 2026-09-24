// Adapted from JetBrains Koog 1.3.0 (Apache License 2.0), module prompt-executor-litert-client:
// https://github.com/JetBrains/koog/tree/1.3.0/prompt/prompt-executor/prompt-executor-clients/prompt-executor-litert-client
// Vendored because the published client is compiled against LiteRT-LM 0.11.0 and fails with
// NoSuchMethodError on 0.17.0, the runtime Gemma 4's Tensor G6 NPU build was validated on.

package com.vermasrijan.pixelnpu.agent.litert

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.utils.time.KoogClock
import com.google.ai.edge.litertlm.Backend
import kotlin.time.ExperimentalTime

/**
 * Configuration for [LiteRTLLMClient].
 *
 * @property modelsPath Absolute path on the device where `.litertlm` model files are stored.
 * @property cacheDir Absolute path used by the LiteRT engine for intermediate caches.
 *   When `null`, LiteRT defaults to the directory of the model file. Set to `":nocache"`
 *   to disable caching entirely (see LiteRT's `EngineConfig.cacheDir` contract).
 * @property backend LiteRT compute backend (e.g. CPU, GPU). Defaults to [Backend.CPU].
 * @property clock Clock instance used for response timestamp metadata.
 */
public data class LiteRTClientConfig(
    val modelsPath: String = "/data/local/tmp/llm",
    val cacheDir: String? = null,
    val backend: Backend = Backend.CPU(),
    @OptIn(ExperimentalTime::class)
    val clock: KoogClock = KoogClock.System,
)

/**
 * [LLMClient] implementation that runs inference on-device using the LiteRT runtime.
 *
 * Delegates to an internal [LiteRTLLMSession] which manages the LiteRT [Engine] and
 * [Conversation] lifecycle. Tools are forwarded to LiteRT as part of the
 * [Conversation] configuration; tool execution itself is performed by the koog
 * agent framework, not by LiteRT (`automaticToolCalling = false`).
 *
 * @param config Configuration specifying model paths, backend, and runtime options.
 */
public class LiteRTLLMClient(config: LiteRTClientConfig) : LLMClient() {
    private val session = LiteRTLLMSession(config)

    /**
     * Runs inference for [prompt] using [model] and returns the model's response messages.
     *
     * Note: [tools] are currently not forwarded to the underlying LiteRT session.
     */
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        return session.execute(prompt, model, tools)
    }

    /**
     * Loads [model] into the LiteRT engine without generating anything.
     *
     * Engine initialization is where an unsupported backend fails (e.g. NPU without the
     * vendor dispatch library), so calling this up front lets callers fall back to another
     * backend before the agent runs. The loaded engine is reused by [execute].
     */
    public suspend fun warmUp(model: LLModel) {
        session.warmUp(model)
    }

    /**
     * Not supported — content moderation is unavailable for on-device models.
     *
     * @throws UnsupportedOperationException always.
     */
    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult {
        throw UnsupportedOperationException("Moderation is not supported for Android local models")
    }

    /** Returns [LiteRTLLMProvider] as the provider for all models served by this client. */
    override fun llmProvider(): LLMProvider = LiteRTLLMProvider

    /** Closes the underlying [LiteRTLLMSession] and releases all LiteRT resources. */
    override fun close() {
        kotlinx.coroutines.runBlocking { session.close() }
    }
}
