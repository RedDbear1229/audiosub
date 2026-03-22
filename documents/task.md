# AudioSub — 구현 체크리스트

## Phase 1: 기본 파이프라인
- [x] MediaProjection 시스템 오디오 캡처 (`AudioCaptureManager`)
- [x] 16kHz Float32 PCM 변환 (`PcmConverter`)
- [x] 슬라이딩 윈도우 청크 생성 (`AudioChunker`, 3초/0.5초 오버랩)
- [x] sherpa-onnx Whisper ASR 엔진 (`SherpaAsrEngine`)
- [x] 플로팅 자막 오버레이 (`SubtitleOverlayManager`, 드래그 이동)
- [x] 파이프라인 상태 머신 (`PipelineState`: INITIALIZING → LISTENING → TRANSCRIBING → ...)
- [x] Android 14+ MediaProjection 순서 제약 준수 (서비스 내 `startForeground()` → `getMediaProjection()`)

## Phase 2: VAD & 할루시네이션 방어
- [x] RMS 에너지 VAD (0.008 임계값, ~−42 dBFS 이하 묵음 건너뜀)
- [x] 텍스트 필터 (`isHallucination()`) — "thank you", "♪" 등 위조 텍스트 제거
- [x] 연속 묵음 경고 (15청크 이상 묵음 시 오디오 차단 가능성 안내)

## Phase 3: 모델 관리
- [x] `ModelRegistry` — Whisper Tiny/Medium, NLLB 번들 정의
- [x] `ModelDownloadManager` — WorkManager 기반 다운로드, tar.bz2 압축 해제
- [x] `ModelManagerActivity` — 모델별 다운로드 UI
- [x] 개별 파일 진행률 표시 (`KEY_FILE_INDEX`, `KEY_FILE_COUNT` → "45% · NLLB_decoder.onnx (2/5)")
- [x] WorkManager `ForegroundInfo` 3인수 형식 (Android 14+ 필수)

## Phase 4: NLLB 번역 엔진 구축
- [x] Xenova HuggingFace 2-세션 모델 시도 (encoder + decoder_merged)
- [x] onnxruntime-android 버전 충돌 해결 — 1.19.0 → **1.17.1** 다운그레이드 (`NoClassDefFoundError` 수정)
- [x] `packagingOptions.pickFirst("**/libonnxruntime.so")` — sherpa-onnx native lib 우선
- [x] NLLB 언어 토큰 동적 계산 — SPM 파일에 없어 `spm_vocab_size + 1 + FLORES-200 index`로 계산 (`IllegalStateException` 수정)
- [x] Xenova decoder 입력명 수정 (`past_key_values.{i}.decoder.key/value` + `use_cache_branch`) (`OrtException` 수정)

## Phase 5: RTranslator 4-세션 모델로 전환
- [x] `ModelRegistry.NLLB_600M` — RTranslator v2.0.0 5파일 번들 (`nllb-600m-v2`)
- [x] `NllbSentencePieceModel` — protobuf 없이 직접 SPM 바이너리 파서 구현
- [x] `NllbBpeTokenizer` — BPE 인코딩/디코딩 + `applyIdOffset` (+1 오프셋, RTranslator 모델 요구사항)
- [x] `NllbTranslationEngine` 완전 재작성 — encoder → cache_init → greedy decode 루프 (4세션)
- [x] `embed_and_lm_head` 듀얼 모드 — `use_lm_head=false`(임베딩) / `true`(로짓 프로젝션)
- [x] `NLLB_600M_LEGACY` (Xenova) 유지 — fallback 및 마이그레이션용, UI 미노출
- [x] `AudioCaptureService.initEngines()` — v2 자동 감지 후 legacy 폴백

## Phase 6: 진단 & 안정성
- [x] SPM vocabSize 검증 (`pieces.size < 10_000` 시 `initError` 설정, 빈 SPM 묵시적 오류 방지)
- [x] `NllbBpeTokenizer.vocabSize` 프로퍼티 추가
- [x] `Throwable` 캐치로 OOM/JNI 크래시 방지 (기존 `Exception`만 캐치하던 문제)
- [x] `translationUnavailableReason` — 번역 불가 이유를 자막에 직접 표시 ("모델 미다운로드" / "SPM 이상" / 예외 메시지)
- [x] 시작 시 진단 메시지 오버레이 10초 표시 (ASR·번역 엔진 상태)
- [x] `isBundleReady()` 파일 크기 검증 추가 — `IndividualFiles` 번들은 존재 여부 + 크기 대조
- [x] `downloadIndividualFiles()` 부분 다운로드 감지 — 크기 불일치 시 자동 재다운로드 (`ORT_INVALID_PROTOBUF` 수정)

## Phase 7: SPM 파서 버그 수정 & 번역 검증
- [x] `NllbSentencePieceModel` field 번호 오류 수정 — `fieldNum == 4` → `fieldNum == 1` (`pieces`는 ModelProto field 1)
- [x] NLLB 번역 엔드투엔드 검증 완료 — 한국어 자막 실제 출력 확인

## Phase 8: 안정성 개선
- [x] 오버레이 자동 복구 — `isAttachedToWindow` 기반 `ensureAttached()` + `safeUpdateLayout()` 안전 래핑
- [x] MediaProjection 종료 처리 — 콜백에서 captureManager 정리 + 사용자 알림
- [x] `START_STICKY` → `START_NOT_STICKY` — 좀비 서비스 방지, null intent 방어
- [x] AudioCaptureManager 스코프 주입 — 독립 스코프 → 서비스 스코프, read 에러 콜백 추가
- [x] WakeLock 추가 — `PARTIAL_WAKE_LOCK`으로 Doze 모드 CPU 유지
- [x] MainActivity 서비스 상태 동기화 — `onResume()`에서 실제 서비스 상태 확인

## Phase 9: 속도 향상 (완료)
- [x] `MAX_DECODE_STEPS` 200→50 축소 — 비정상 케이스 최대 75% 번역 시간 절약
- [x] 번역 병렬화 (fire-and-forget) — ASR 완료 즉시 원문 표시, 번역은 `translation` 디스패처 비동기
- [x] ASR 타이밍 로그 — `SherpaAsrEngine.transcribe()` 소요 시간 측정
- [x] 번역 캐시 (LRU 20개) — 슬라이딩 윈도우 중복 번역 방지
- [x] NLLB `intraOpNumThreads=2` — 디코더 행렬 연산 병렬화

## Phase 10: 속도 모드 선택 UI
- [x] `AudioCaptureService` — `SPEED_MODE_BALANCED`/`SPEED_MODE_FAST` 상수 및 적용 로직
- [x] `SherpaAsrEngine` — `numThreads` 생성자 파라미터화
- [x] `AudioChunker` — 속도 모드별 청크/스트라이드 파라미터 전달
  - 균형(B): chunkSeconds=3.0 / stepSeconds=1.0 / numThreads=4 → ~1.5-2초
  - 빠름(C): chunkSeconds=2.0 / stepSeconds=0.75 / numThreads=4 → ~1-1.5초
- [x] `MainActivity` — RadioGroup UI, SharedPreferences 저장, Intent extra 전달
- [x] `activity_main.xml` — 속도 모드 RadioGroup 추가

## Phase 11: 스트리밍 ASR (실시간 모드)
- [x] `ModelRegistry` — 스트리밍 모델 번들 4개 추가 (EN/KO/ZH/JA)
- [x] `StreamingAsrEngine` — `OnlineRecognizer` 기반 스트리밍 엔진 (feedAudio 콜백 패턴)
- [x] `AudioCaptureManager` — 스트리밍 모드용 `onRawAudio` 직접 피딩 콜백
- [x] `AudioCaptureService` — `SPEED_MODE_REALTIME` + 스트리밍 파이프라인 분기
- [x] `MainActivity` — 실시간 모드 RadioButton + 언어 선택 Spinner
- [x] `activity_main.xml` — 언어 선택 UI
- [x] 일본어 — PengChengStarling 다국어 모델 (`modelType = "zipformer2"`)

## Phase 12: UI/UX 개선
- [x] 마이크 권한 제어 — `startForeground()` 서비스 타입을 오디오 소스에 따라 분기 (시스템=MEDIA_PROJECTION, 마이크=MICROPHONE)
- [x] 속도 모드 UI 개선 — RadioGroup → Spinner(드롭다운) 변경
- [x] 모델 관리 카테고리 구분 — `category` 필드 추가, 멀티 타입 어댑터로 ASR/번역/스트리밍 섹션 헤더 표시
- [x] 자막 상태 표시 개선 — 하단 상태바 제거 → 자막창 좌상단 6dp 도트 인디케이터 (녹색/파란색/빨간색)
- [x] 번역 자막 유지 — 이전 번역 유지 후 새 번역 완료 시 교체 (원문 미표시, 번역 실패 시 원문 폴백)
