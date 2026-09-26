# Pixel NPU Lab

An Android app for running machine-learning workloads on the **TPU (NPU) inside the Google Tensor chip** of a Pixel 11, and measuring what that hardware can actually do compared with the CPU and GPU.

> **Status:** first working piece. The app runs a [Koog](https://github.com/JetBrains/koog) agent backed by **Gemma 4 E2B** on-device, preferring the Tensor TPU. Everything under *Planned features* is still to come.

## Why

The Tensor SoC in recent Pixels includes a dedicated TPU built for on-device inference. Google's own features (Gemini Nano, camera processing, Recorder, Live Translate) use it heavily, but it is hard for a third-party developer to see or use that compute directly. This project aims to:

1. **Reach the TPU** from a regular, sideloadable app, using whatever public APIs Google exposes.
2. **Benchmark it** with repeatable latency, throughput, and power numbers per accelerator (CPU / GPU / NPU).
3. **Put it to work** on useful on-device tasks (vision, audio, text, small LLMs) without sending data to the cloud.

## How an app reaches the Pixel TPU

There is no raw "TPU SDK" for Pixel. Access goes through Google's ML runtimes, each of which decides whether a workload runs on the TPU:

| Path | What it gives you | Notes |
|------|-------------------|-------|
| **LiteRT** (formerly TensorFlow Lite), `CompiledModel` API with the NPU accelerator | Run your own `.tflite` models, with NPU → GPU → CPU fallback | Main path for custom models. The model may need ahead-of-time compilation for the target SoC, and not every op is supported on the NPU. |
| **ML Kit GenAI APIs / AICore (Gemini Nano)** | Summarize, proofread, rewrite, describe images, free-form prompts | Runs on the TPU through AICore. You can't bring your own model. Availability depends on device and AICore version. |
| **LiteRT-LM** | Run open small LLMs (e.g. Gemma 4) on device | NPU needs a model compiled for the specific SoC (e.g. `gemma-4-E2B-it_Google_Tensor_G6.litertlm`) plus a vendor dispatch library. Used by the Gemma 4 agent below. |
| ~~NNAPI~~ | — | Deprecated since Android 15. Don't build new code on it. |

What is supported changes between LiteRT and AICore releases, so the app detects capabilities at runtime instead of assuming them. Check the latest LiteRT and ML Kit docs for Tensor NPU support before relying on any path.

## Gemma 4 agent (Koog + LiteRT-LM)

The app's first screen is a Koog agent running Gemma 4 E2B entirely on the phone. It has read-only tools for the phone's own state, so you can ask things like *"Is my phone running hot right now?"* or *"How much RAM is free?"*:

| Tool | Returns |
|------|---------|
| `getDeviceInfo` | Phone model, SoC, Android version |
| `getBatteryStatus` | Charge %, charging state, battery temperature |
| `getThermalStatus` | Thermal throttling status and headroom |
| `getMemoryInfo` | Total and available RAM |
| `getCurrentDateTime` | Local date, time, time zone |

On start the app picks the fastest backend that loads, then shows which one it's using and why it skipped the others:

1. **NPU (Tensor TPU):** needs all three of these:
   - a Gemma 4 build for this SoC (Tensor G5 or G6)
   - `libLiteRtDispatch_GoogleTensor.so` bundled in the APK
   - the matching model file on the device
2. **GPU:** needs the generic `gemma-4-E2B-it.litertlm`.
3. **CPU:** uses the same generic file.

Each prompt runs as a separate agent task. The loaded model stays in memory between prompts, but the conversation doesn't carry over.

### Code layout

```
app/src/main/kotlin/com/vermasrijan/pixelnpu/
  agent/OnDeviceGemmaAgent.kt   Backend selection (NPU → GPU → CPU) and the Koog agent
  agent/DeviceTools.kt          Koog @Tool functions
  agent/litert/                 Koog's LiteRT LLM client, vendored (see below) + Gemma 4 model catalog
  ui/                           Compose screen + ViewModel
scripts/push-model.sh           adb-push .litertlm files to /data/local/tmp/llm
scripts/build-tensor-dispatch.sh  Build the Tensor NPU dispatch library from LiteRT source
```

**Why the LiteRT client is vendored:** Koog 1.3.0 ships a `LiteRTLLMClient`, but it's compiled against LiteRT-LM 0.11.0. The Tensor G6 build of Gemma 4 was validated on LiteRT-LM 0.17.0, and Koog's compiled client throws `NoSuchMethodError` there because `ConversationConfig` gained parameters. So `agent/litert/` is Koog's client source (Apache-2.0) recompiled against 0.17.0, plus a `warmUp()` hook that loads the model early so a failing backend can fall back before the first prompt.

### Running it

1. **Get a model onto the phone**, using either option:
   - **In the app:** if no model is found, the app offers to download one into its own storage. That's the Tensor NPU build when the APK bundles the dispatch library, otherwise `gemma-4-E2B-it.litertlm` (2.6 GB). The [Hugging Face repo](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) is gated, so first accept the license there, then paste a read token from <https://huggingface.co/settings/tokens>. Keep the app open while it downloads. If you pause or lose the connection, it resumes where it left off.
   - **From a computer:** download the file and push it with adb:
     ```bash
     hf download litert-community/gemma-4-E2B-it-litert-lm gemma-4-E2B-it.litertlm --local-dir models
     scripts/push-model.sh models/gemma-4-E2B-it.litertlm
     ```
   For the TPU you need `gemma-4-E2B-it_Google_Tensor_G6.litertlm` (3.3 GB) instead.
2. **(For the TPU) build the Tensor dispatch library.** Google doesn't publish it prebuilt for current LiteRT, so this builds it with Bazel from the LiteRT commit that LiteRT-LM 0.17.0 pins, and places it in `app/src/main/jniLibs/arm64-v8a/`:
   ```bash
   ANDROID_NDK_HOME=/path/to/ndk scripts/build-tensor-dispatch.sh
   ```
   Without it, the app skips the NPU and runs on the GPU.
3. **Build and install.** Every push also builds a debug APK in GitHub Actions (the *Build APK* workflow's artifact).
   ```bash
   ./gradlew :app:installDebug
   ```

The app looks for models in its private `files/models/` directory first (in-app downloads go there), then in `/data/local/tmp/llm`.

## Planned features

- **Device & accelerator report:** SoC model, Android and AICore versions, and which accelerators LiteRT can create on this device.
- **Benchmark suite:** a fixed set of reference models (e.g. MobileNet, EfficientDet, a small transformer) run on CPU, GPU, and NPU, reporting:
  - model load / compile time
  - first-inference and steady-state latency (p50/p90/p99)
  - throughput (inferences/sec)
  - energy per inference, where the Pixel's on-device power rails are readable via Perfetto
  - numerical drift between NPU output and CPU reference
- **Bring-your-own model:** load a `.tflite` file, run it on the NPU, and see which ops fell back to another accelerator.
- **Demo workloads:** live camera classification/segmentation, speech-to-text, and on-device text generation through Gemini Nano.
- **Exportable results:** save runs as JSON/CSV to compare across builds, OS updates, and devices.

## Planned architecture

This is the target layout. Today only the agent, the LiteRT client and the UI exist.

```
app/                    Jetpack Compose UI (dashboard, benchmark runner, demos)
core/accelerator/       Capability detection + accelerator selection/fallback
core/inference/         LiteRT wrapper (CompiledModel, buffers, NPU/GPU/CPU options)
core/genai/             ML Kit GenAI / AICore (Gemini Nano) wrapper
core/benchmark/         Timing harness, stats, warm-up, thermal-state tracking
core/telemetry/         Power/thermal sampling, result export
models/                 Reference models + download/compile scripts
```

Here's roughly what running a model with NPU-first fallback looks like. It's a sketch, so check the current LiteRT Kotlin API before using it:

```kotlin
val model = CompiledModel.create(
    context.assets,
    "mobilenet_v3.tflite",
    CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
)
val inputs = model.createInputBuffers()
val outputs = model.createOutputBuffers()
inputs[0].writeFloat(pixels)
model.run(inputs, outputs)
val scores = outputs[0].readFloat()
```

## Tech stack

- **Language/UI:** Kotlin, Jetpack Compose, coroutines
- **Build:** Gradle 9.4 (Kotlin DSL), Android Gradle Plugin 9.2, compile/target SDK 37, min SDK 31, arm64 only
- **Agents:** Koog 1.3.0
- **ML runtimes:** LiteRT-LM 0.17.0 (Gemma 4); later LiteRT and ML Kit GenAI APIs
- **Profiling:** Perfetto (CPU/GPU/power rails), Android Studio Profiler
- **Min SDK:** chosen to match the LiteRT NPU and AICore requirements; target is the Pixel 11's shipping Android version

## Requirements

- A Pixel 11 (other Tensor-based Pixels should mostly work, but with different results)
- Android Studio (latest stable) with the Android SDK and NDK
- USB debugging enabled on the phone
- For Gemini Nano features: AICore up to date through the Play Store, and the device enrolled wherever Google requires it

## Getting started

See [Running it](#running-it) above.

## Roadmap

- [x] Gradle project skeleton + Compose app shell
- [x] Koog agent on Gemma 4 (LiteRT-LM) with NPU → GPU → CPU fallback and device tools
- [ ] Multi-turn chat memory for the agent
- [ ] Device/accelerator capability report screen
- [ ] LiteRT integration with CPU/GPU/NPU selection
- [ ] Benchmark harness (warm-up, repeated runs, percentile stats, thermal tracking)
- [ ] Reference model set + NPU compilation pipeline
- [ ] Gemini Nano (ML Kit GenAI) demo
- [ ] Power measurement via Perfetto power rails
- [ ] Result export and comparison view
- [ ] Bring-your-own-model runner with per-op fallback report

## Caveats

- **The NPU isn't always faster.** Small models, unsupported ops, or input/output copies can make the GPU or even the CPU win. The benchmarks exist to answer that for each model.
- **Thermal throttling** skews long runs. The harness should record thermal state and support cool-down between runs.
- **Quantization matters.** The TPU is optimized for int8/int16 (and some fp16) models; float32 models may fall back or run slowly.
- **Results will change** with OS, Play-system, and AICore updates, so record those versions with every run.

## References

- LiteRT: <https://ai.google.dev/edge/litert>
- ML Kit GenAI APIs: <https://developers.google.com/ml-kit/genai>
- Gemini Nano on Android: <https://developer.android.com/ai/gemini-nano>
- Google AI Edge: <https://ai.google.dev/edge>
- Perfetto: <https://perfetto.dev>
- Koog: <https://github.com/JetBrains/koog>
- LiteRT-LM: <https://github.com/google-ai-edge/LiteRT-LM>
- Gemma 4 E2B LiteRT-LM builds: <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>
