# Pixel NPU Lab

An Android app for running machine-learning workloads on the **TPU (NPU) inside the Google Tensor chip** of a Pixel 11, and measuring what that hardware can actually do compared with the CPU and GPU.

> **Status:** early planning. This README describes the goal and the intended design; no app code exists yet.

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
| **LiteRT-LM / MediaPipe LLM Inference** | Run open small LLMs (e.g. Gemma) on device | Usually GPU- or CPU-backed. NPU support varies by release. |
| ~~NNAPI~~ | — | Deprecated since Android 15. Don't build new code on it. |

What is supported changes between LiteRT and AICore releases, so the app detects capabilities at runtime instead of assuming them. Check the latest LiteRT and ML Kit docs for Tensor NPU support before relying on any path.

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
- **Build:** Gradle (Kotlin DSL), Android Gradle Plugin
- **ML runtimes:** LiteRT, ML Kit GenAI APIs, LiteRT-LM / MediaPipe (optional)
- **Profiling:** Perfetto (CPU/GPU/power rails), Android Studio Profiler
- **Min SDK:** chosen to match the LiteRT NPU and AICore requirements; target is the Pixel 11's shipping Android version

## Requirements

- A Pixel 11 (other Tensor-based Pixels should mostly work, but with different results)
- Android Studio (latest stable) with the Android SDK and NDK
- USB debugging enabled on the phone
- For Gemini Nano features: AICore up to date through the Play Store, and the device enrolled wherever Google requires it

## Getting started

_Build instructions will be added once the Gradle project exists._ The planned flow:

```bash
git clone https://github.com/vermasrijan19/AI-tools.git
cd AI-tools
./gradlew :app:installDebug      # build and install on a connected Pixel
```

## Roadmap

- [ ] Gradle project skeleton + Compose app shell
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
