# Third-party notices

Bothy's own source code is licensed under the repository [MIT license](LICENSE). The agent runtime it builds on,
[Android Agent Runtime](https://github.com/NoHarry/android-agent-runtime), lists its own third-party files and
dependencies (A2A Java SDK sources, Zipline and others) in its `THIRD_PARTY_NOTICES.md`. Dependencies resolved by Gradle, and
the third-party files committed in this repository, remain under their respective upstream licenses and terms; Bothy's
MIT license does not replace them.

This file is informational and does not modify any upstream license. Use Gradle dependency reports or your
application's software-composition analysis tooling to produce the final, release-specific inventory, including
transitive dependencies and the versions your build actually packages.

## Committed third-party files

### sherpa-onnx (`bothy-voice`)

- **sherpa-onnx Kotlin API** (`bothy-voice/src/main/kotlin/com/k2fsa/sherpa/onnx/`) — Apache License 2.0, Copyright
  Xiaomi Corporation and the sherpa-onnx authors. Copied from tag v1.13.8; see the `NOTICE` file in that directory.
  Upstream: <https://github.com/k2-fsa/sherpa-onnx>
- **`libsherpa-onnx-jni.so`** (`bothy-voice/src/main/jniLibs/arm64-v8a/`) — Apache License 2.0, from the sherpa-onnx
  v1.13.8 Android release (`sherpa-onnx-v1.13.8-android.tar.bz2`, SHA-256
  `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44`).
- **`libonnxruntime.so`** (same directory) — MIT License, Copyright Microsoft Corporation, as bundled in the sherpa-onnx
  v1.13.8 Android release. Upstream: <https://github.com/microsoft/onnxruntime>

sherpa-onnx publishes no Android artifact on Maven Central, so these files are committed.

### llama.cpp (`bothy-llm`)

- **llama-server and its libraries** (`bothy-llm/src/main/jniLibs/arm64-v8a/`: `libllama_server.so` is the
  `llama-server` executable, plus `libllama-server-impl.so`, `libllama-common.so`, `libllama.so`, `libmtmd.so` and the
  `libggml*.so` backends, CPU, OpenCL and Hexagon, with the NPU-side `libggml-htp-v*.so`) — MIT License, Copyright the
  ggml authors. From the llama.cpp b12004 release (`llama-b12004-bin-android-arm64-snapdragon.tar.gz`, SHA-256
  `3e04487c32ee7f4eb2fbe6a0bd8359624904117addff431192a49c79a5cd0cb8`); the host-side libraries were stripped of
  debug symbols, the NPU-side ones are unchanged. Upstream: <https://github.com/ggml-org/llama.cpp>

llama.cpp publishes no Android artifact on Maven Central, so these files are committed. The model the on-device service
runs, Google's Gemma 4 E4B (`google/gemma-4-E4B-it-qat-q4_0-gguf`, Apache License 2.0), is not in the repository: the app
downloads it on request from ModelScope or Hugging Face and checks its published SHA-256.

### On-device speech models (`bothy-app`)

- **Streaming ASR model** `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16` and **keyword-spotting
  model** `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` (`bothy-app/src/main/assets/voice-models/`) — published
  by the k2-fsa project as GitHub release assets (<https://github.com/k2-fsa/sherpa-onnx/releases>). Upstream does not
  say what the Chinese–English keyword-spotting model was trained on. Check the upstream model pages and the dataset
  terms before using these models in a commercial product.

`workspace/apps/android/tools/voice/fetch_voice_assets.py` re-downloads the models and the native libraries from the pinned upstream archives and checks their SHA-256, so the committed
files can be verified or replaced.

### Wake-answer clips (`bothy-app/src/main/assets/voice-acks/`)

Short spoken answers (「我在」「在呢」…) synthesized with Xiaomi MiMo TTS preset voices by
`workspace/apps/android/tools/voice/make_wake_acks.py`. They are provided for the demo; review the MiMo service terms
before redistributing them in your own product, or regenerate them with a TTS service and voice you are licensed to use.
Without them the app synthesizes the answer on the device the first time it needs it.

## Notable Gradle dependencies

- **Android Agent Runtime** (`cc.noharry:agent-*`) — MIT License, with the notices in its repository.
- **A2UI rendering** (sample): AndroidX A2UI alpha artifacts — Apache License 2.0 (<https://github.com/androidx/androidx>).
- **AndroidX, Jetpack Compose, Material** — Apache License 2.0 (<https://github.com/androidx/androidx>).
- **Kotlin coroutines and serialization** — Apache License 2.0 (<https://github.com/Kotlin/kotlinx.coroutines>,
  <https://github.com/Kotlin/kotlinx.serialization>).
- **OkHttp** — Apache License 2.0 (<https://github.com/square/okhttp>).
- **Glide** (sample) — BSD, part MIT and Apache 2.0 (<https://github.com/bumptech/glide>).
- **ZXing core** (sample, QR codes) — Apache License 2.0 (<https://github.com/zxing/zxing>).
- **multiplatform-markdown-renderer** (sample) — Apache License 2.0 (<https://github.com/mikepenz/multiplatform-markdown-renderer>).

Applications that include the optional modules redistribute these libraries, including their native artifacts,
through their APK/AAB and should preserve all notices the exact resolved dependency graph requires.
