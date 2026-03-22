# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 문서 동기화 규칙

**이 파일(CLAUDE.md)을 업데이트할 때는 `documents/` 폴더의 관련 파일도 함께 갱신한다.**

| 파일 | 갱신 시점 |
|---|---|
| `documents/project.md` | 프로젝트 목적·기능·스택이 바뀔 때 |
| `documents/task.md` | Phase 완료 또는 새 Phase/태스크 추가 시 |
| `documents/adr.md` | 기술 의사결정(라이브러리 선택, 구조 변경 등) 이 있을 때 |
| `documents/error.md` | 레벨 2 이상의 에러를 해결했을 때 |
| `documents/session.md` | 세션 종료 또는 주요 작업 단위가 마무리될 때 |

---

## Project Overview

**AudioSub** — Android app that captures system audio via `MediaProjection`, transcribes with on-device Whisper (sherpa-onnx), translates to Korean with NLLB-200, and displays floating subtitles.

Key constraints:
- All inference runs **on-device** — no cloud APIs.
- Build tooling runs inside **Termux** on the Android device itself.
- Targets Android 10+ (API 29), primary ABI: `arm64-v8a`.

---

## Build (Termux)

```bash
# Build debug APK and copy to Download folder for installation
./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/audiosub.apk

# Lint
./gradlew lint
```

Install from `/sdcard/Download/audiosub.apk` via device file manager.

### aapt2 ARM64 issue
After adding any new Gradle dependency, the x86_64 `aapt2` binary gets re-downloaded (broken in Termux). Fix:
```bash
find ~/.gradle/caches -name "aapt2" -type f | while read f; do
    cp /data/data/com.termux/files/usr/bin/aapt2 "$f"
done
./gradlew clean assembleDebug
```

### APK install failure after dependency changes
If the installer auto-closes: **Settings → Apps → AudioSub → 제거** (uninstall), then reinstall the fresh APK.

---

## Architecture

```
SystemAudio (MediaProjection)
    │  AudioCaptureManager (AudioRecord + AudioPlaybackCapture)
    │  16kHz Float32 PCM
    ▼
AudioChunker  ──  sliding window chunks (3s / 0.5s overlap)
    │
    ▼
VAD (RMS energy)  ──  silence < 0.008 RMS → skip (prevent Whisper hallucinations)
    │
    ▼
SherpaAsrEngine  ──  Whisper (sherpa-onnx OfflineRecognizer)
    │  text + detected language code
    ▼
NllbTranslationEngine  ──  NLLB-200-distilled-600M (RTranslator split model)
    │  Korean text  (skipped if lang == "ko" or model not loaded)
    ▼
SubtitleOverlayManager  ──  WindowManager TYPE_APPLICATION_OVERLAY
```

The entire pipeline is orchestrated by `AudioCaptureService` (a `LifecycleService`). It initializes engines on a coroutine, processes audio chunks in a loop, and writes subtitle text to `SubtitleOverlayManager`.

### Streaming Mode (Realtime)

```
SystemAudio (MediaProjection)
    │  AudioCaptureManager (AudioRecord + AudioPlaybackCapture)
    │  16kHz Float32 PCM — direct feed (no chunker)
    ▼
StreamingAsrEngine  ──  Zipformer Transducer (sherpa-onnx OnlineRecognizer)
    │  partial results → subtitle updated in real-time
    │  final result (endpoint detected) → subtitle confirmed
    ▼
NllbTranslationEngine  ──  async translation on final result
    │  Korean text  (skipped if lang == "ko")
    ▼
SubtitleOverlayManager
```

Streaming mode bypasses `AudioChunker` and VAD — audio is fed directly to `StreamingAsrEngine.feedAudio()`. The engine handles endpoint detection internally via `EndpointConfig` rules.

---

## Models

| ID | Type | Source | Size |
|---|---|---|---|
| `whisper-tiny` | ASR | sherpa-onnx tar.bz2 (GitHub Releases) | ~116 MB |
| `whisper-medium` | ASR | sherpa-onnx tar.bz2 (GitHub Releases) | ~1.9 GB |
| `nllb-600m-v2` | Translation | RTranslator v2.0.0 GitHub Releases (5 files) | ~950 MB |
| `streaming-en` | Streaming ASR | HuggingFace (csukuangfj, Zipformer) | ~128 MB |
| `streaming-ko` | Streaming ASR | HuggingFace (k2-fsa, Zipformer) | ~133 MB |
| `streaming-zh` | Streaming ASR | HuggingFace (csukuangfj, bilingual zh-en) | ~198 MB |
| `streaming-ja` | Streaming ASR | HuggingFace (csukuangfj, PengChengStarling multilingual) | ~339 MB |

- Active ASR model: `ModelRegistry.ACTIVE_ASR = WHISPER_TINY`. Switch to `WHISPER_MEDIUM` after confirming device RAM.
- Models downloaded at runtime to `getExternalFilesDir(null)/models/<bundle-id>/`.
- `NLLB_600M_LEGACY` (id=`nllb-600m`, Xenova merged model) is kept in `ModelRegistry` for fallback/migration but is **not shown in the UI**.
- `AudioCaptureService.initEngines()` auto-detects v2 vs legacy: uses `nllb-600m-v2` if ready, else falls back to `nllb-600m`.
- `isBundleReady()` verifies **exact file sizes** for `IndividualFiles` bundles — a partially downloaded file (size mismatch) is treated as not ready. `downloadIndividualFiles()` also deletes and re-downloads files whose size doesn't match the expected value.

---

## NLLB Translation — 4-Session Architecture

`NllbTranslationEngine` uses **4 ONNX sessions** (RTranslator v2.0.0 split INT8 model):

| Session file | Role |
|---|---|
| `NLLB_encoder.onnx` | input_ids + attention_mask + embed_matrix → last_hidden_state |
| `NLLB_cache_initializer.onnx` | encoder_hidden_states → encoder KV cache (computed once) |
| `NLLB_decoder.onnx` | token + KV cache → pre_logits + updated decoder KV |
| `NLLB_embed_and_lm_head.onnx` | dual-mode: embedding lookup (use_lm_head=false) OR logits projection (use_lm_head=true) |

Inference flow: tokenize → embed encoder input → encoder → cache init → greedy decode loop (max 200 steps). Step 1 forces the target language token; subsequent steps use argmax.

**Token ID offset**: RTranslator split model requires BPE token IDs ≥ 4 to have **+1** applied during encode and **−1** during decode. Special tokens (0–3) and language tokens are unaffected. `NllbBpeTokenizer` accepts `applyIdOffset: Boolean` to toggle this.

**Language tokens**: NLLB-200 language tokens (e.g. `kor_Hang`, `eng_Latn`) are **not stored** in the sentencepiece.bpe.model file. They are computed dynamically as `spm_vocab_size + 1 + index` using the `FAIRSEQ_LANGUAGE_CODES` list in `NllbBpeTokenizer` (200 entries in exact HuggingFace FLORES-200 order).

**SPM validation**: On load, `NllbTranslationEngine` checks `tokenizer.vocabSize >= 10_000`. If SPM loaded as empty (pieces.size = 0), `initError` is set to a user-visible message instead of silently producing garbage output.

**onnxruntime-android version**: Pinned to **1.17.1** — must match sherpa-onnx's bundled `libonnxruntime.so`. Using 1.19.0 causes `NoClassDefFoundError`. `packagingOptions.pickFirst("**/libonnxruntime.so")` keeps sherpa-onnx's native lib.

---

## VAD & Hallucination Filtering

Whisper generates fake text ("suscle", "thank you", "♪") from silence. Two-layer defense in `AudioCaptureService`:

1. **Energy VAD** (`VAD_RMS_THRESHOLD = 0.008f`): RMS below threshold → silently skip chunk, no Whisper call (~−42 dBFS).
2. **Text filter** (`isHallucination()`): discards known hallucinated phrases post-transcription.

---

## Critical Android 14+ Constraints

### MediaProjection ordering
`getMediaProjection()` **must** be called inside the foreground service, **after** `startForeground()`. Calling it in `MainActivity` throws `SecurityException`.

Flow: `MainActivity` receives consent Intent → passes `resultCode` + `data` as extras to `AudioCaptureService` → service calls `startForeground()` first, then `acquireMediaProjection(intent)`.

### startForeground() service type
```kotlin
startForeground(NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
```

### WorkManager ForegroundInfo type
```kotlin
ForegroundInfo(DOWNLOAD_NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
```

---

## Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" tools:ignore="ProtectedPermissions"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.INTERNET"/>
```

---

## Service Stability

- **`START_NOT_STICKY`**: Service does not auto-restart when killed — MediaProjection data is one-time use and cannot be recovered. If `intent == null` in `onStartCommand()`, calls `stopSelf()`.
- **WakeLock**: `PARTIAL_WAKE_LOCK` acquired in `onCreate()`, released in `onDestroy()` — prevents CPU sleep during audio capture & inference.
- **MediaProjection callback** (Android 14+): On `onStop()`, stops `captureManager`, shows error overlay, sets `PipelineState.ERROR`. Previously only called `MediaProjectionHolder.release()`, leaving a zombie pipeline.
- **AudioCaptureManager scope**: Receives `serviceScope` from the service (not its own `CoroutineScope`). Ensures coroutines stop when service is destroyed.
- **AudioRecord error callback**: `onCaptureError` fires when `AudioRecord.read()` returns negative (e.g. `ERROR_DEAD_OBJECT`), breaking the read loop and notifying the service.
- **Overlay auto-recovery**: `SubtitleOverlayManager.ensureAttached()` checks `isAttachedToWindow` before every public call. If the system removed the overlay (fullscreen transition, permission revocation), it re-attaches automatically.
- **MainActivity state sync**: `onResume()` checks `isServiceActuallyRunning()` via `ActivityManager` and updates the toggle button.

---

## Known Issues / TODOs

- **Korean translation latency**: 4-session split model is 4× faster than Xenova merged, but still slow on low-end devices.
- **DRM content**: `AudioPlaybackCapture` cannot capture Widevine L1 DRM audio.
- **Apps blocking capture**: Apps with `android:allowAudioPlaybackCapture="false"` are excluded.

---

## Avoided Patterns

- Do **not** call `getMediaProjection()` in `MainActivity` — Android 14+ throws `SecurityException`.
- Do **not** add `com.google.mlkit:translate` — causes silent APK install failure (component conflict).
- Do **not** use 2-arg `ForegroundInfo(id, notification)` on Android 14+ for WorkManager — throws `MissingForegroundServiceTypeException`.
- Do **not** upgrade `onnxruntime-android` beyond **1.17.1** without also updating sherpa-onnx's native lib — version mismatch causes `NoClassDefFoundError` at runtime.
