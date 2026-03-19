# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**AudioSub** — Android app that captures system audio via `MediaProjection`, transcribes with on-device Whisper (sherpa-onnx), optionally translates to Korean with NLLB-200, and displays floating subtitles.

Key constraints:
- All inference runs **on-device** — no cloud APIs.
- Build tooling runs inside **Termux** on the Android device itself.
- Targets Android 10+ (API 29), primary ABI: `arm64-v8a`.

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
NllbTranslationEngine  ──  NLLB-200-distilled-600M ONNX
    │  Korean text  (skipped if lang == "ko" or model not loaded)
    ▼
SubtitleOverlayManager  ──  WindowManager TYPE_APPLICATION_OVERLAY
```

---

## Source Layout

```
app/src/main/java/com/audiosub/
├── AudioSubApp.kt                 # Application class; crash handler → getExternalFilesDir/crash_log.txt
├── MainActivity.kt                # Permission requests; passes MediaProjection consent to service
├── service/
│   ├── AudioCaptureService.kt     # LifecycleService orchestrating the full pipeline
│   └── MediaProjectionHolder.kt   # Singleton holding the active MediaProjection
├── audio/
│   ├── AudioCaptureManager.kt     # AudioRecord wrapper; system audio or mic fallback
│   ├── AudioChunker.kt            # Produces FloatArray chunks via Channel
│   └── PcmConverter.kt            # Int16 PCM → Float32; resampling to 16 kHz
├── asr/
│   ├── AsrEngine.kt               # Interface
│   ├── SherpaAsrEngine.kt         # sherpa-onnx OfflineRecognizer (Whisper)
│   └── AsrResult.kt               # data class: text, language, isEmpty
├── translation/
│   ├── TranslationEngine.kt       # Interface
│   ├── NllbTranslationEngine.kt   # ONNX Runtime session for NLLB-200
│   └── TokenizerWrapper.kt        # SentencePiece tokenizer wrapper
├── overlay/
│   ├── SubtitleOverlayManager.kt  # WindowManager overlay lifecycle
│   ├── SubtitleView.kt            # Custom view for subtitle rendering
│   └── PipelineState.kt           # Sealed class: INITIALIZING / DOWNLOADING / LISTENING / TRANSCRIBING / TRANSLATING / ERROR
├── model/
│   ├── ModelRegistry.kt           # All model bundles (Whisper Tiny/Medium, NLLB-600M)
│   ├── ModelDownloadManager.kt    # WorkManager-based download with progress
│   └── ModelManagerActivity.kt    # UI for model download/status
└── util/
    ├── PermissionHelper.kt
    └── CoroutineDispatcherProvider.kt  # io / asr (single-thread executor) dispatchers
```

---

## Models

| ID | Type | Source | Size |
|---|---|---|---|
| `whisper-tiny` | ASR | sherpa-onnx tar.bz2 (GitHub Releases) | ~116 MB |
| `whisper-medium` | ASR | sherpa-onnx tar.bz2 (GitHub Releases) | ~1.9 GB |
| `nllb-600m` | Translation | HuggingFace (Xenova ONNX, individual files) | ~900 MB |

Active ASR model: `WHISPER_TINY` (set `ModelRegistry.ACTIVE_ASR` to switch).
Models are downloaded to `getExternalFilesDir(null)/models/<bundle-id>/` at runtime.
APK does **not** bundle model files.

---

## Critical Android 14+ Constraints

### MediaProjection ordering (MOST IMPORTANT)
`getMediaProjection()` **must** be called inside the foreground service, **after** `startForeground()`.
Calling it in `MainActivity` before the service starts throws `SecurityException`.

Flow:
1. `MainActivity` launches `MediaProjection` consent dialog via `ActivityResultContracts`
2. On `RESULT_OK`, passes `resultCode` + `data` Intent as extras to `AudioCaptureService`
3. `AudioCaptureService.onStartCommand()` calls `startForeground()` first, then `acquireMediaProjection(intent)`

### startForeground() service type
Must use 3-arg form on API 29+:
```kotlin
startForeground(NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
```
Without the type argument, Android 14+ rejects `getMediaProjection()`.

### WorkManager ForegroundInfo type
```kotlin
ForegroundInfo(DOWNLOAD_NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)   // Android 14+ requires this
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
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.INTERNET"/>
```

---

## VAD & Hallucination Filtering

Whisper generates fake text ("suscle", "thank you", "♪") from silence. Two-layer defense:

1. **Energy VAD** (`VAD_RMS_THRESHOLD = 0.008f` in `AudioCaptureService`):
   RMS of a chunk below this value → silently skip, no Whisper call.
   ~0.008 ≈ -42 dBFS. Raise threshold if real speech is being skipped.

2. **Text filter** (`isHallucination()`): discards known hallucinated phrases.

---

## Build (Termux)

```bash
# Build debug APK
./gradlew assembleDebug

# Install (manual — Termux needs "Install unknown apps" permission in Settings)
cp app/build/outputs/apk/debug/app-debug.apk ~/audiosub.apk
termux-open ~/audiosub.apk
# Or copy to /sdcard/Download/ and install from device file manager

# Lint
./gradlew lint
```

### aapt2 ARM64 issue
If Gradle downloads a new dependency, it re-downloads the x86_64 aapt2 binary (broken in Termux).
Fix after any new dependency addition:
```bash
find ~/.gradle/caches -name "aapt2" -type f | while read f; do
    cp /data/data/com.termux/files/usr/bin/aapt2 "$f"
done
./gradlew clean assembleDebug
```

### APK install failure after adding dependencies
If the APK installs but immediately fails or auto-closes the installer:
1. Uninstall existing app manually: **Settings → Apps → AudioSub → 제거**
2. Reinstall fresh APK — do **not** attempt to update over a build with different components (e.g., after removing ML Kit).

---

## Known Issues / TODOs

- **Korean translation latency**: NLLB-200 inference is slow on low-end devices. Consider batching or async display.
- **DRM content**: `AudioPlaybackCapture` cannot capture Widevine L1 DRM audio.
- **Apps blocking capture**: Apps declaring `android:allowAudioPlaybackCapture="false"` are excluded.
- **NLLB model not tested end-to-end**: Download + tokenizer + ONNX session flow needs on-device validation.
- **ML Kit was removed**: `com.google.mlkit:translate` caused silent APK install failure (component conflict with existing install). Do not re-add without first cleanly uninstalling the app.

---

## Avoided Patterns

- Do **not** call `getMediaProjection()` in `MainActivity` — Android 14+ will throw `SecurityException`.
- Do **not** add `com.google.mlkit:translate` — causes APK install conflict.
- Do **not** use `ForegroundInfo(id, notification)` (2-arg) on Android 14+ for WorkManager — throws `MissingForegroundServiceTypeException`.
