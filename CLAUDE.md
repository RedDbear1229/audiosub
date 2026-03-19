# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**AudioSub** — an Android application that captures system audio in real time, transcribes it using a local speech recognition model, and displays Korean subtitles on screen.

Key constraints:
- All inference (ASR + translation) runs **on-device** (no cloud APIs).
- Targets Android (Termux environment for tooling; app itself targets Android SDK).

## Architecture

```
Audio Capture → ASR (local model) → Translation (local model) → Subtitle Overlay UI
```

### Components

| Layer | Responsibility |
|---|---|
| **Audio Capture** | Record system/microphone audio via `AudioRecord` or `MediaProjection` API |
| **ASR** | Convert audio to text — planned: Whisper (via whisper.cpp / sherpa-onnx) |
| **Translation** | Korean translation of transcribed text — planned: OPUS-MT or similar local NMT model |
| **Subtitle UI** | Overlay `TextView` drawn via `WindowManager` (requires `SYSTEM_ALERT_WINDOW` permission) |

### Local Model Stack (planned)

- **ASR**: [whisper.cpp](https://github.com/ggerganov/whisper.cpp) or [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — runs Whisper models on-device via JNI/NDK or prebuilt binaries.
- **Translation**: Helsinki-NLP OPUS-MT models converted to ONNX, run via ONNX Runtime for Android.
- Alternatively, a multilingual Whisper model (e.g., `whisper-small` with `--language auto`) can transcribe non-Korean audio directly; a separate NMT model handles translation to Korean.

## Android Permissions Required

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>   <!-- subtitle overlay -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT"   <!-- system audio; requires privileged/root or MediaProjection -->
    tools:ignore="ProtectedPermissions"/>
```

System audio capture (`MediaProjection` + `AudioPlaybackCapture`) requires the user to grant screen capture permission at runtime (Android 10+).

## Development Environment (Termux)

Build tooling runs inside Termux on the Android device itself.

```bash
# Install build dependencies
pkg install gradle openjdk-17 cmake

# Build debug APK
./gradlew assembleDebug

# Install to connected device (via adb)
adb install app/build/outputs/apk/debug/app-debug.apk

# Run unit tests (JVM)
./gradlew test

# Run instrumented tests (device)
./gradlew connectedAndroidTest

# Lint
./gradlew lint
```

## Key Design Decisions

- **Foreground Service**: Audio capture and inference run in a `ForegroundService` so the overlay persists when the user switches apps.
- **JNI boundary**: Native ASR libraries (whisper.cpp / sherpa-onnx) are called via JNI; keep the JNI wrapper thin — pass PCM byte arrays in, get text strings out.
- **Threading**: Audio capture runs on a dedicated thread; inference is dispatched to a `CoroutineDispatcher` backed by a single-thread executor to avoid out-of-order results.
- **Streaming vs. batch**: Prefer streaming / sliding-window inference (e.g., 3-second chunks with 0.5 s overlap) over whole-utterance batching to keep subtitle latency low.
