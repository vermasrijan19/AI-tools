package com.vermasrijan.pixelnpu.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.vermasrijan.pixelnpu.agent.litert.Gemma4Models
import com.vermasrijan.pixelnpu.agent.litert.LiteRTClientConfig
import com.vermasrijan.pixelnpu.agent.litert.LiteRTLLMClient
import com.vermasrijan.pixelnpu.agent.litert.LiteRTLLMProvider
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTimedValue

enum class Accelerator { NPU, GPU, CPU }

/** Why an accelerator was not used: skipped before loading, or failed while loading. */
data class AcceleratorRejection(val accelerator: Accelerator, val reason: String)

/**
 * A Koog agent backed by Gemma 4 running on the phone through LiteRT-LM.
 *
 * Use [load] to create one: it tries the Tensor TPU first, then the GPU, then the CPU, and
 * keeps the first backend whose engine initializes.
 */
class OnDeviceGemmaAgent private constructor(
    private val client: LiteRTLLMClient,
    private val toolRegistry: ToolRegistry,
    val accelerator: Accelerator,
    val model: LLModel,
    val modelFile: File,
    val loadTime: Duration,
    val rejected: List<AcceleratorRejection>,
) : AutoCloseable {
    private val executor = MultiLLMPromptExecutor(LiteRTLLMProvider to client)

    /**
     * Runs one agent task. Each call starts a fresh conversation; the loaded engine is reused.
     *
     * @param onToolCall Called with a readable description of each tool call the model makes.
     */
    suspend fun run(input: String, onToolCall: (String) -> Unit = {}): String {
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            toolRegistry = toolRegistry,
            systemPrompt = SYSTEM_PROMPT,
            maxIterations = 10,
            installFeatures = {
                handleEvents {
                    onToolCallStarting { event -> onToolCall("${event.toolName}(${event.toolArgs})") }
                }
            },
        )
        return agent.run(input)
    }

    override fun close() = client.close()

    /** Thrown by [load] when no backend could load a model. */
    class NoUsableBackendException(val rejected: List<AcceleratorRejection>) :
        IllegalStateException(rejected.joinToString("\n") { "${it.accelerator}: ${it.reason}" })

    companion object {
        /** Directory the app scans for `.litertlm` files after its own [Context.getFilesDir]/models. */
        const val ADB_MODELS_DIR = "/data/local/tmp/llm"

        /** Vendor dispatch library LiteRT needs to reach the Tensor TPU; see scripts/build-tensor-dispatch.sh. */
        const val TENSOR_DISPATCH_LIBRARY = "libLiteRtDispatch_GoogleTensor.so"

        private val SYSTEM_PROMPT = """
            You are an assistant running entirely on this Android phone, on-device, with no internet access.
            Use the tools to look up facts about the phone (hardware, battery, temperature, memory, time)
            instead of guessing. Keep answers short.
        """.trimIndent()

        /**
         * Loads Gemma 4 on the fastest backend that works on this device.
         *
         * @param onAttempt Called before each backend is tried, for progress reporting.
         * @throws NoUsableBackendException if every backend was skipped or failed.
         */
        suspend fun load(context: Context, onAttempt: (Accelerator) -> Unit = {}): OnDeviceGemmaAgent {
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            val modelDirs = listOf(File(context.filesDir, "models"), File(ADB_MODELS_DIR))
            val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.path
            val toolRegistry = ToolRegistry { tools(DeviceTools(context).asTools()) }
            val rejected = mutableListOf<AcceleratorRejection>()

            val npuModel = Gemma4Models.forTensorNpu(Build.SOC_MODEL)
            val candidates = buildList {
                when {
                    npuModel == null ->
                        rejected += AcceleratorRejection(Accelerator.NPU, "no Gemma 4 NPU build for SoC '${Build.SOC_MODEL}'")
                    !File(nativeLibraryDir, TENSOR_DISPATCH_LIBRARY).isFile ->
                        rejected += AcceleratorRejection(Accelerator.NPU, "$TENSOR_DISPATCH_LIBRARY is not bundled in the APK")
                    else -> add(Triple(Accelerator.NPU, npuModel, Backend.NPU(nativeLibraryDir)))
                }
                add(Triple(Accelerator.GPU, Gemma4Models.E2B, Backend.GPU()))
                add(Triple(Accelerator.CPU, Gemma4Models.E2B, Backend.CPU()))
            }

            for ((accelerator, model, backend) in candidates) {
                val modelDir = modelDirs.firstOrNull { File(it, model.id).isFile }
                if (modelDir == null) {
                    rejected += AcceleratorRejection(
                        accelerator,
                        "${model.id} not found in ${modelDirs.joinToString(" or ")}",
                    )
                    continue
                }
                onAttempt(accelerator)
                val client = LiteRTLLMClient(
                    LiteRTClientConfig(modelsPath = modelDir.path, cacheDir = cacheDir, backend = backend)
                )
                try {
                    val (_, loadTime) = measureTimedValue { client.warmUp(model) }
                    return OnDeviceGemmaAgent(
                        client = client,
                        toolRegistry = toolRegistry,
                        accelerator = accelerator,
                        model = model,
                        modelFile = File(modelDir, model.id),
                        loadTime = loadTime,
                        rejected = rejected.toList(),
                    )
                } catch (e: CancellationException) {
                    client.close()
                    throw e
                } catch (e: Exception) {
                    client.close()
                    rejected += AcceleratorRejection(accelerator, e.describe())
                } catch (e: LinkageError) {
                    // A missing or incompatible native NPU/GPU library surfaces as UnsatisfiedLinkError.
                    client.close()
                    rejected += AcceleratorRejection(accelerator, e.describe())
                }
            }
            throw NoUsableBackendException(rejected)
        }

        private fun Throwable.describe(): String = message ?: this::class.java.simpleName
    }
}
