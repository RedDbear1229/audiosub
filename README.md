# AudioSub

Android 기기에서 시스템 오디오를 실시간으로 캡처하여 로컬 AI 모델로 전사·번역하고, 화면 위에 한국어 자막을 띄우는 앱입니다.

> **Claude Code 연습 프로젝트** — 이 프로젝트는 [Claude Code](https://claude.ai/code) (Anthropic의 AI 코딩 어시스턴트)를 활용하여 설계·개발하는 실험적 프로젝트입니다. 아이디어 구체화부터 코드 작성, 버그 수정까지 대부분의 작업을 Claude Code와 대화하며 진행합니다.

---

## 주요 기능

- **시스템 오디오 캡처** — `MediaProjection` API로 기기에서 재생되는 모든 소리를 캡처 (유튜브, 넷플릭스 등)
- **로컬 음성 인식(ASR)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Whisper 모델로 완전 온디바이스 전사 (클라우드 없음)
- **한국어 번역** — NLLB-200 모델로 200개 언어 → 한국어 번역
- **자막 오버레이** — `WindowManager`로 모든 앱 위에 실시간 자막 표시
- **에너지 기반 VAD** — 묵음 구간을 건너뛰어 Whisper 환각(hallucination) 억제

---

## 파이프라인

```
시스템 오디오 (44.1kHz)
    │
    ▼
AudioCaptureManager  ──  MediaProjection / 마이크 폴백
    │  리샘플링 → 16kHz Float32
    ▼
AudioChunker         ──  슬라이딩 윈도우 청크
    │
    ▼
VAD (RMS 에너지)     ──  묵음 스킵
    │
    ▼
SherpaAsrEngine      ──  Whisper (sherpa-onnx)
    │  텍스트 + 언어코드
    ▼
NllbTranslationEngine ── NLLB-200-distilled-600M ONNX
    │  한국어 텍스트
    ▼
SubtitleOverlayManager ─ WindowManager 오버레이
```

---

## 기술 스택

| 구성 요소 | 기술 |
|---|---|
| ASR | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Whisper medium.int8 |
| 번역 | NLLB-200-distilled-600M (ONNX int8) |
| 오디오 캡처 | `MediaProjection` + `AudioPlaybackCaptureConfiguration` |
| 오버레이 UI | `WindowManager TYPE_APPLICATION_OVERLAY` |
| 모델 다운로드 | `WorkManager` (재개 가능, 진행률 표시) |
| 비동기 처리 | Kotlin Coroutines |
| 최소 SDK | Android 10 (API 29) |
| 타깃 ABI | `arm64-v8a` |

---

## 요구 권한

| 권한 | 용도 |
|---|---|
| `RECORD_AUDIO` | 마이크 폴백 캡처 |
| `SYSTEM_ALERT_WINDOW` | 자막 오버레이 표시 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 백그라운드 오디오 캡처 서비스 |
| `INTERNET` | 모델 최초 다운로드 |

시스템 오디오 캡처는 앱 실행 시 화면 공유 동의를 받습니다 (Android 10+).

---

## 프로젝트 구조

```
app/src/main/java/com/audiosub/
├── MainActivity.kt                # 권한 요청, MediaProjection 진입점
├── AudioSubApp.kt                 # Application 클래스, 크래시 핸들러
├── service/
│   ├── AudioCaptureService.kt     # ForegroundService — 파이프라인 총괄
│   └── MediaProjectionHolder.kt   # MediaProjection 싱글턴 보관
├── audio/
│   ├── AudioCaptureManager.kt     # AudioRecord 래퍼
│   ├── AudioChunker.kt            # 슬라이딩 윈도우 청크
│   └── PcmConverter.kt            # PCM Int16 → Float32, 리샘플링
├── asr/
│   ├── AsrEngine.kt               # 인터페이스
│   ├── SherpaAsrEngine.kt         # sherpa-onnx OfflineRecognizer
│   └── AsrResult.kt
├── translation/
│   ├── TranslationEngine.kt       # 인터페이스
│   └── NllbTranslationEngine.kt   # NLLB ONNX 세션
├── overlay/
│   ├── SubtitleOverlayManager.kt  # 오버레이 생명주기 관리
│   └── PipelineState.kt           # 상태 sealed class
├── model/
│   ├── ModelDownloadManager.kt    # WorkManager 기반 다운로드
│   ├── ModelRegistry.kt           # 모델 URL, SHA256, 메타데이터
│   └── ModelManagerActivity.kt    # 모델 관리 화면
└── util/
    ├── PermissionHelper.kt
    └── CoroutineDispatcherProvider.kt
```

---

## 빌드 및 설치

### 개발 환경 (Termux)

이 프로젝트는 Android 기기의 Termux에서 직접 빌드합니다.

```bash
# 의존성 설치
pkg install gradle openjdk-17

# 디버그 APK 빌드
./gradlew assembleDebug

# 설치
termux-open app/build/outputs/apk/debug/app-debug.apk
```

### 모델 파일

APK에 모델이 포함되어 있지 않습니다. 앱 내 **모델 관리** 화면에서 다운로드합니다.

| 모델 | 크기 | 용도 |
|---|---|---|
| Whisper medium.int8 (sherpa-onnx) | ~500MB | 음성 → 텍스트 |
| NLLB-200-distilled-600M | ~1.2GB | 다국어 → 한국어 번역 |

> 두 모델 합산 약 1.7GB의 저장 공간이 필요합니다.

---

## 알려진 제한사항

- **번역 지연**: NLLB 추론에 문장당 수 초 소요 (기기 성능에 따라 다름)
- **DRM 콘텐츠**: Widevine L1 DRM 보호 콘텐츠는 `AudioPlaybackCapture` 불가
- **일부 앱 차단**: `android:allowAudioPlaybackCapture="false"` 선언 앱은 캡처 불가

---

## Claude Code와 함께한 개발 과정

이 프로젝트는 Claude Code를 통해 다음과 같은 작업들을 함께 해결했습니다:

- Android 14+ `MediaProjection` 순서 제약 (`getMediaProjection()`은 `startForeground()` 이후 호출해야 함) 디버깅
- WorkManager `ForegroundInfo`에 서비스 타입 명시 필요 문제 해결
- Whisper 환각(hallucination) 억제를 위한 에너지 기반 VAD 설계
- Termux 환경에서 ARM64 aapt2 캐시 이슈 해결

---

## 라이선스

MIT
