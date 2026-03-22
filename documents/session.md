## 2026-03-22 세션 #8

### 오늘의 목표
- 스트리밍 ASR (방안 E) 구현 — sherpa-onnx OnlineRecognizer 기반 실시간 모드

### 완료한 것
- **Phase 11 스트리밍 ASR**:
  - `ModelRegistry` — 스트리밍 모델 번들 4개 추가 (EN ~128MB, KO ~133MB, ZH ~198MB, JA ~339MB)
  - `StreamingAsrEngine` 신규 — `OnlineRecognizer` + `OnlineStream`, feedAudio 콜백 패턴, endpoint 자동 감지
  - `AudioCaptureManager` — `chunker=null` + `onRawAudio` 콜백으로 스트리밍 직접 피딩 모드
  - `AudioCaptureService` — `SPEED_MODE_REALTIME`, `initStreamingAsr()`, `startStreamingPipeline()`, `launchTranslation()` 헬퍼
  - `MainActivity` — 실시간 RadioButton, 언어 선택 Spinner (영어/한국어/중국어/일본어), `PREF_STREAMING_LANG`
  - `activity_main.xml` — Spinner + 동적 visibility 토글
  - 일본어 — PengChengStarling 다국어 모델 사용 (`modelType = "zipformer2"`, WER 13.34%)
  - 중국어 — bilingual zh-en 모델 사용 (~198MB, 중국어+영어 이중언어)

### 발생한 문제
- 없음 (빌드 경고 4개: intent null-safe call, 기존 코드)

### 다음에 할 것
- 실기기에서 스트리밍 모델 다운로드 및 실시간 모드 테스트
- 자막 UI 설정 (폰트 크기·색상, 위치 저장)

### 오늘의 메모
- 일본어 전용 스트리밍 모델 미존재 → PengChengStarling 8개 언어 다국어 모델로 대체 (Whisper-Large-v3보다 정확도 높음)
- 중국어 전용 모델(14M, ~25MB)은 너무 작아서 bilingual zh-en 모델(~198MB) 채택
- sherpa-onnx EndpointConfig.Builder에 오타: `setRul3()` (e 누락) — 주의 필요
- `OnlineRecognizer.isReady(stream)` 호출 후 `decode(stream)` — while 루프로 모든 준비된 프레임 처리

---

## 2026-03-22 세션 #7

### 오늘의 목표
- 파이프라인 속도 향상 (Phase 9) 완료
- 속도 모드 B/C 선택 UI 구현 (Phase 10)

### 완료한 것
- **Phase 9 속도 향상** (이전 세션에서 구현):
  - `MAX_DECODE_STEPS` 200→50, 번역 비동기화, ASR 타이밍 로그, 번역 캐시, NLLB intraOp 2스레드
- **Phase 10 속도 모드 UI**:
  - `SherpaAsrEngine` 생성자에 `numThreads` 파라미터 추가
  - `AudioCaptureService`에 `SPEED_MODE_BALANCED`/`SPEED_MODE_FAST` 상수, extras 수신, AudioChunker/SherpaAsrEngine 파라미터 전달
  - `MainActivity`에 RadioGroup UI, SharedPreferences(`PREF_SPEED_MODE`), Intent extra(`EXTRA_SPEED_MODE`) 연결
  - `activity_main.xml` 설정 카드에 속도 모드 RadioGroup 추가
  - `strings.xml` 한국어 라벨 추가

### 발생한 문제
- 없음 (빌드 경고 3개: intent null-safe call, 기존 코드)

### 다음에 할 것
- 실기기에서 균형/빠름 모드 체감 속도 비교 테스트
- 스트리밍 ASR (방안 E) 조사 — sherpa-onnx OnlineRecognizer 지원 모델 확인

### 오늘의 메모
- Galaxy S24 기준 예상 지연: 현재 5초 → 균형 ~1.5-2초, 빠름 ~1-1.5초
- Whisper 언어 지정은 지연 단축 효과 미미 (~50ms) — 인코더 처리 시간이 대부분
- 속도 모드 선택은 `PREF_SPEED_MODE` SharedPreferences로 앱 재시작 후에도 유지됨

---

## 2026-03-20 세션 #1

### 오늘의 목표
- AudioSub 앱 초기 구현 — 시스템 오디오 캡처 + Whisper ASR + 플로팅 자막

### 완료한 것
- 전체 파이프라인 초기 구현 (MediaProjection → AudioChunker → SherpaAsrEngine → SubtitleOverlayManager)
- 시스템 오디오 캡처 (`AudioPlaybackCapture`) + 마이크 폴백
- 디버그 모드 토글 및 오디오 소스 선택 UI
- 파이프라인 진단 패널 및 자막 테스트 버튼
- `FOREGROUND_SERVICE_MICROPHONE` 퍼미션 추가
- 오디오 캡처 실패 시 서비스 크래시 방지
- 묵음 경고 + YouTube 오디오 캡처 차단 설명
- CLAUDE.md 초안 작성

### 발생한 문제
- 문제: 시스템 오디오가 캡처되지 않음
- 원인: `AudioPlaybackCapture` USAGE 설정 누락, 서비스 타입 미지정
- 해결: `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 3인수 `startForeground()` + USAGE 순차 시도
- 레벨: 2

### 다음에 할 것
- NLLB 번역 엔진 추가 (한국어 번역)

### 오늘의 메모
- Android 14+에서 MediaProjection은 반드시 서비스 내 `startForeground()` 이후에 호출해야 함 — MainActivity에서 호출하면 `SecurityException`
- `AudioPlaybackCapture`는 Widevine L1 DRM 및 `allowAudioPlaybackCapture="false"` 앱은 캡처 불가 (YouTube 포함)

---

## 2026-03-21 세션 #2

### 오늘의 목표
- NLLB-200 번역 엔진 구현 (Xenova HuggingFace ONNX 모델)
- 영어 음성 → 한국어 자막 출력 확인

### 완료한 것
- `NllbTranslationEngine` 초기 구현 (Xenova 2-세션: encoder + decoder_merged)
- `NllbBpeTokenizer` + `NllbSentencePieceModel` 구현 (protobuf 없이 SPM 직접 파싱)
- `ModelRegistry`에 `NLLB_600M` 번들 추가 (HuggingFace 개별 파일 다운로드)
- 오버레이 드래그 이동 기능 추가
- OOM/JNI 크래시 방지 — `catch (Exception)` → `catch (Throwable)` 수정

### 발생한 문제
- 문제: `NoClassDefFoundError: ai.onnxruntime.OrtEnvironment`
- 원인: onnxruntime-android 1.19.0과 sherpa-onnx의 libonnxruntime.so(1.17.1) 버전 불일치
- 해결: onnxruntime-android **1.17.1** 다운그레이드 + `pickFirst("**/libonnxruntime.so")`
- 레벨: 3

- 문제: `IllegalStateException: eng_Latn not in vocabulary`
- 원인: NLLB 언어 토큰이 sentencepiece.bpe.model에 없음 — HuggingFace 토크나이저가 동적으로 추가하는 구조
- 해결: `FAIRSEQ_LANGUAGE_CODES` 200개 하드코딩, ID = `spm_vocab_size + 1 + index` 공식으로 계산
- 레벨: 2

- 문제: `OrtException: Unknown input name past_key_values.0.key`
- 원인: Xenova merged decoder의 실제 입력명은 `past_key_values.{i}.decoder.key/value` + `use_cache_branch`
- 해결: 입력 스키마 수정 (decoder/encoder KV 분리 + use_cache_branch 텐서 추가)
- 레벨: 2

### 다음에 할 것
- 번역 출력 확인 — 한국어 자막이 실제로 나오는지 검증
- 추론이 너무 느리면 더 빠른 모델 구조 검토

### 오늘의 메모
- Xenova와 RTranslator는 같은 NLLB 모델이라도 ONNX 입력 스키마가 완전히 다름
- ONNX 세션 생성 후 `session.inputNames` 로그로 실제 이름 확인하는 것이 필수

---

## 2026-03-22 세션 #3

### 오늘의 목표
- 번역 출력이 없는 문제 진단 ("번역결과없음 토큰= [98, 122064, ...]")
- RTranslator 4-세션 모델로 전환 (4배 빠른 추론)
- 모델 관리 UI 개선 (파일별 진행률)

### 완료한 것
- 토큰 `[98, ...]` 역산으로 `pieces.size = 0` 진단 (SPM 어휘가 빈 상태)
- `NllbBpeTokenizer.vocabSize` 프로퍼티 추가
- `NllbTranslationEngine.loadModel()`에 `vocabSize < 10_000` 검증 추가 → 사용자에게 재다운로드 안내
- `ModelRegistry.NLLB_600M` → RTranslator v2.0.0 5파일 split 모델로 전환 (`nllb-600m-v2`)
- `NllbTranslationEngine` 완전 재작성 — 4세션 구조 (encoder → cache_init → greedy decode loop)
- `NllbBpeTokenizer.applyIdOffset` — RTranslator BPE +1 오프셋 구현
- `ModelDownloadManager` 파일별 진행률 (`KEY_FILE_INDEX`, `KEY_FILE_COUNT`)
- `ModelManagerActivity` 진행률 텍스트 개선 ("45% · NLLB_decoder.onnx (2/5)")
- `translationUnavailableReason` — 번역 불가 이유 자막 직접 표시
- CLAUDE.md, documents/ 문서 전체 업데이트

### 발생한 문제
- 문제: 번역 출력 없음, 토큰이 [98, 122064, ...] 이상한 값
- 원인: SPM 파일 로드는 성공했지만 `pieces.size = 0` (빈 어휘). 첫 토큰 98 = kor_Hang 언어 토큰 (spm_vocab_size=0일 때의 값)
- 해결: vocabSize 검증 추가, 파일 재다운로드 유도
- 레벨: 3

### 다음에 할 것
- APK 설치 후 NLLB 번역 엔드투엔드 검증 (한국어 자막 실제 출력 확인)
- sentencepiece.bpe.model 파일 정상 다운로드 확인 (4.85 MB)
- 번역 지연 개선 검토

### 오늘의 메모
- 출력 토큰 첫 값으로 내부 상태를 역산할 수 있음 — 디버깅 강력한 기법
- RTranslator 4-세션 구조가 Xenova merged 대비 4배 빠르고 RAM 1.9배 절약
- 번역 불가 이유를 자막에 직접 표시하는 것이 별도 알림보다 훨씬 효과적 (알림은 이미 다른 자막으로 덮어쓰임)

---

## 2026-03-22 세션 #4

### 오늘의 목표
- `ORT_INVALID_PROTOBUF` 에러 해결 — NLLB ONNX 파일 손상 원인 파악 및 수정
- CLAUDE.md 및 documents/ 문서 동기화

### 완료한 것
- 부분 다운로드 파일을 "완료"로 판단하던 버그 수정
- `isBundleReady()`: `IndividualFiles` 번들은 파일 크기까지 검증
- `downloadIndividualFiles()`: 크기 불일치 파일 삭제 후 재다운로드
- CLAUDE.md Models 섹션에 `isBundleReady` 크기 검증 동작 추가
- `documents/error.md`, `task.md`, `session.md` 동기화

### 발생한 문제
- 문제: `OrtException: ORT_INVALID_PROTOBUF` — ONNX 파일 파싱 실패
- 원인: `dest.length() > 0L` 조건만으로 스킵 → 부분 다운로드 파일(수 MB)을 완료로 판단
- 해결: 예상 크기와 실제 크기 비교, 불일치 시 재다운로드
- 레벨: 2

### 다음에 할 것
- 모델 관리에서 nllb-600m-v2 삭제 후 재다운로드 → NLLB 번역 엔드투엔드 검증

### 오늘의 메모
- 파일 크기 검증은 SHA-256보다 빠르고 대부분의 부분 다운로드를 잡아냄
- `ModelRegistry.FileEntry.sizeBytes`를 정확히 채우는 것이 이 방어막의 전제조건

---

## 2026-03-22 세션 #5

### 오늘의 목표
- "SPM 어휘 크기 이상" 오류 해결 — 재다운로드해도 동일 오류 반복
- NLLB 번역 엔드투엔드 검증

### 완료한 것
- `NllbSentencePieceModel` protobuf field 번호 오류 발견 및 수정
  - `fieldNum == 4` → `fieldNum == 1` (`ModelProto.pieces`는 field 1)
  - 파서가 SPM 파일 전체를 읽어도 pieces를 찾지 못하던 근본 원인 해결
- 한국어 번역 정상 동작 확인 (엔드투엔드 검증 완료)
- documents/ 전체 업데이트 (task, project, error, session)

### 발생한 문제
- 문제: 재다운로드해도 "SPM 어휘 크기 이상: 0" 반복
- 원인: 파일 문제가 아닌 파서 버그 — protobuf field 번호를 잘못 하드코딩 (4 → 실제는 1)
- 해결: sentencepiece.proto 원본 확인 후 `fieldNum == 1`로 수정
- 레벨: 3

### 다음에 할 것
- 번역 지연 개선 (저사양 기기 대응)
- 자막 UI 설정 (폰트 크기·색상, 위치 저장)
- Whisper Medium 전환 테스트

### 오늘의 메모
- protobuf field 번호는 반드시 원본 `.proto` 파일 확인 필수 — 추정으로 작성하면 파싱 결과가 조용히 비어버림
- `vocabSize` 검증 덕분에 버그를 조기에 감지할 수 있었음 — 없었다면 훨씬 긴 디버깅 필요
- **핵심 파이프라인 완성**: 시스템 오디오 캡처 → Whisper ASR → NLLB 한국어 번역 → 자막 표시 전 구간 정상 동작

---

## 2026-03-22 세션 #6

### 오늘의 목표
- 사용 중 안정성 문제 해결 (전체 화면 크래시, 홈화면 이동, 자막 사라짐)

### 완료한 것
- **오버레이 자동 복구**: `isAttached`를 `isAttachedToWindow` 기반으로 변경, `ensureAttached()` 추가하여 시스템이 제거한 오버레이를 자동 재부착
- **WindowManager 안전 래핑**: `safeUpdateLayout()` 헬퍼 도입, 모든 `updateViewLayout` 호출을 안전하게 처리
- **MediaProjection 종료 처리**: 콜백에서 `captureManager?.stop()` + 사용자 알림 추가 (기존에는 release만 하고 좀비 상태)
- **START_STICKY → START_NOT_STICKY**: intent=null 재시작 시 좀비 서비스 방지, null intent 방어 추가
- **AudioCaptureManager 스코프 주입**: 자체 `CoroutineScope` → 서비스에서 `serviceScope` 주입으로 변경, 서비스 종료 시 코루틴 확실히 취소
- **AudioRecord 에러 콜백**: `read()` 음수값 반환 시 서비스에 알림 + 캡처 루프 종료
- **WakeLock 추가**: `PARTIAL_WAKE_LOCK`으로 Doze 모드에서 CPU 유지
- **MainActivity 서비스 상태 동기화**: `onResume()`에서 `isServiceActuallyRunning()` 확인하여 UI 동기화

### 발생한 문제
- 문제: 전체 화면 전환 시 크래시, 자막 사라짐, 홈화면 강제 이동
- 원인 (복합): 오버레이 재부착 없음 + MP 콜백 미정리 + START_STICKY 좀비 + WakeLock 미사용 + 독립 스코프 누수
- 해결: 5개 Task로 분해하여 각각 수정
- 레벨: 2

### 다음에 할 것
- 번역 지연 개선 (저사양 기기 대응)
- 자막 UI 설정 (폰트 크기·색상, 위치 저장)
- Whisper Medium 전환 테스트

### 오늘의 메모
- `isAttachedToWindow`가 `overlayRoot != null`보다 정확한 오버레이 상태 확인 방법 — 시스템이 뷰를 제거해도 참조는 남아있음
- `START_STICKY` + MediaProjection은 위험한 조합 — 재시작 시 intent가 null이라 projection 복구 불가
- `AudioCaptureManager`가 독립 스코프를 가지면 서비스 종료 후에도 코루틴이 돌 수 있음 — 반드시 서비스 스코프를 주입할 것
