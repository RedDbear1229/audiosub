# AudioSub v1.0.0

Android 기기에서 시스템 오디오를 실시간으로 캡처하여 로컬 AI 모델로 전사·번역하고, 화면 위에 한국어 자막을 띄우는 앱입니다.

> **Claude Code 연습 프로젝트** — 이 프로젝트는 [Claude Code](https://claude.ai/code) (Anthropic의 AI 코딩 어시스턴트)를 활용하여 설계·개발하는 실험적 프로젝트입니다. 아이디어 구체화부터 코드 작성, 버그 수정까지 대부분의 작업을 Claude Code와 대화하며 진행합니다.

---

## 주요 기능

- **시스템 오디오 캡처** — `MediaProjection` API로 기기에서 재생되는 모든 소리를 캡처 (유튜브, 넷플릭스 등)
- **로컬 음성 인식(ASR)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Whisper 모델로 완전 온디바이스 전사 (클라우드 없음)
- **실시간 스트리밍 ASR** — Zipformer Transducer 기반 스트리밍 인식 (~0.3초 지연), 영어·한국어·중국어·일본어 지원
- **한국어 번역** — NLLB-200 모델로 200개 언어 → 한국어 실시간 번역
- **자막 오버레이** — `WindowManager`로 모든 앱 위에 실시간 자막 표시 (드래그 이동 가능)
- **이중 자막** — 원문을 상단에 작게, 한국어 번역을 하단에 크게 표시
- **에너지 기반 VAD** — 묵음 구간을 건너뛰어 Whisper 환각(hallucination) 억제
- **모델 관리** — 앱 내 다운로드 UI, ASR·번역·스트리밍 모델을 카테고리별 관리

---

## 파이프라인

### 배치 모드 (균형/빠름)

```
시스템 오디오 (44.1kHz)
    │
    ▼
AudioCaptureManager  ──  MediaProjection / 마이크 폴백
    │  리샘플링 → 16kHz Float32
    ▼
AudioChunker         ──  슬라이딩 윈도우 청크 (3s/1s 또는 2s/0.75s)
    │
    ▼
VAD (RMS 에너지)     ──  묵음 스킵
    │
    ▼
SherpaAsrEngine      ──  Whisper (sherpa-onnx OfflineRecognizer)
    │  텍스트 + 언어코드
    ▼
NllbTranslationEngine ── NLLB-200-distilled-600M ONNX (4-세션)
    │  한국어 텍스트
    ▼
SubtitleOverlayManager ─ WindowManager 오버레이
```

### 실시간 모드 (스트리밍)

```
시스템 오디오 (44.1kHz)
    │
    ▼
AudioCaptureManager  ──  직접 피딩 (청커 없음)
    │  리샘플링 → 16kHz Float32
    ▼
StreamingAsrEngine   ──  Zipformer Transducer (OnlineRecognizer)
    │  부분 결과 → 원문 상단 소형 표시
    │  최종 결과 → 번역 시작
    ▼
NllbTranslationEngine ── 비동기 번역
    │  한국어 텍스트
    ▼
SubtitleOverlayManager ─ 이중 자막 (원문 상단 + 번역 하단)
```

---

## 속도 모드

| 모드 | 엔진 | 지연 | 설명 |
|---|---|---|---|
| 균형 | Whisper (배치) | ~1.5-2초 | 3초 청크, 높은 정확도 |
| 빠름 | Whisper (배치) | ~1-1.5초 | 2초 청크, 빠른 응답 |
| 실시간 | Zipformer (스트리밍) | ~0.3초 | 언어별 전용 모델, 실시간 부분 결과 |

---

## 기술 스택

| 구성 요소 | 기술 |
|---|---|
| ASR (배치) | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Whisper medium.int8 |
| ASR (스트리밍) | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Zipformer Transducer |
| 번역 | NLLB-200-distilled-600M (ONNX int8, [RTranslator](https://github.com/niedev/RTranslator) 4-세션 split) |
| 토크나이저 | 직접 구현한 SentencePiece BPE 파서 (protobuf 의존성 없음) |
| 오디오 캡처 | `MediaProjection` + `AudioPlaybackCaptureConfiguration` |
| 오버레이 UI | `WindowManager TYPE_APPLICATION_OVERLAY` |
| 모델 다운로드 | `WorkManager` (재개 가능, 파일별 진행률 표시) |
| 비동기 처리 | Kotlin Coroutines |
| 최소 SDK | Android 10 (API 29) |
| 타깃 ABI | `arm64-v8a` |

---

## 모델

| 모델 | 크기 | 용도 |
|---|---|---|
| Whisper Medium (int8) | ~1.9 GB | 다국어 음성 인식 |
| NLLB-200 (600M, split int8) | ~950 MB | 200개 언어 → 한국어 번역 |
| 스트리밍 ASR 영어 | ~128 MB | 실시간 영어 인식 |
| 스트리밍 ASR 한국어 | ~133 MB | 실시간 한국어 인식 |
| 스트리밍 ASR 중국어 | ~198 MB | 실시간 중국어 인식 (중영 이중언어) |
| 스트리밍 ASR 일본어 | ~339 MB | 실시간 일본어 인식 (다국어 모델) |

APK에 모델이 포함되어 있지 않습니다. 앱 내 **모델 관리** 화면에서 필요한 모델만 다운로드합니다.

---

## 요구 권한

| 권한 | 용도 |
|---|---|
| `RECORD_AUDIO` | 마이크 모드 오디오 캡처 |
| `SYSTEM_ALERT_WINDOW` | 자막 오버레이 표시 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 시스템 오디오 캡처 서비스 |
| `FOREGROUND_SERVICE_MICROPHONE` | 마이크 모드 서비스 |
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
│   ├── AudioCaptureManager.kt     # AudioRecord 래퍼 (배치/스트리밍 겸용)
│   ├── AudioChunker.kt            # 슬라이딩 윈도우 청크
│   └── PcmConverter.kt            # PCM Int16 → Float32, 리샘플링
├── asr/
│   ├── AsrEngine.kt               # 인터페이스
│   ├── SherpaAsrEngine.kt         # sherpa-onnx OfflineRecognizer (배치)
│   ├── StreamingAsrEngine.kt      # sherpa-onnx OnlineRecognizer (스트리밍)
│   └── AsrResult.kt
├── translation/
│   ├── TranslationEngine.kt       # 인터페이스
│   ├── NllbTranslationEngine.kt   # NLLB ONNX 4-세션
│   ├── NllbBpeTokenizer.kt        # BPE 토크나이저
│   └── NllbSentencePieceModel.kt  # SPM 바이너리 파서
├── overlay/
│   ├── SubtitleOverlayManager.kt  # 오버레이 생명주기·이중 자막
│   └── PipelineState.kt           # 상태 sealed class
├── model/
│   ├── ModelDownloadManager.kt    # WorkManager 기반 다운로드
│   ├── ModelRegistry.kt           # 모델 URL, 크기, 카테고리
│   └── ModelManagerActivity.kt    # 모델 관리 화면 (카테고리 구분)
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

---

## 알려진 제한사항

- **번역 지연**: NLLB 추론에 문장당 수 초 소요 (기기 성능에 따라 다름)
- **DRM 콘텐츠**: Widevine L1 DRM 보호 콘텐츠는 `AudioPlaybackCapture` 불가
- **일부 앱 차단**: `android:allowAudioPlaybackCapture="false"` 선언 앱은 캡처 불가

---

## Claude Code와 함께한 개발 과정

이 프로젝트는 Claude Code를 통해 다음과 같은 작업들을 함께 해결했습니다:

- 시스템 오디오 캡처 → Whisper ASR → NLLB 번역 → 자막 표시 전체 파이프라인 설계·구현
- Android 14+ `MediaProjection` 순서 제약 디버깅
- sherpa-onnx / onnxruntime-android 버전 충돌 해결 (JNI 브릿지)
- protobuf 없이 SentencePiece BPE 바이너리 파서 직접 구현
- NLLB 4-세션 split 모델 추론 엔진 구축
- Zipformer Transducer 기반 스트리밍 ASR 통합
- 오버레이 자동 복구, WakeLock, 서비스 안정성 개선
- Termux 환경에서 ARM64 aapt2 캐시 이슈 해결

---

## 라이선스

MIT
