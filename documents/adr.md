## ADR-001: 번역 엔진 — 클라우드 API 대신 온디바이스 NLLB-200

### 날짜
2026-03-01

### 상태
확정

### 맥락
실시간 자막을 위해 번역 엔진이 필요. Google Translate API 등 클라우드 서비스와 온디바이스 모델 중 선택.

### 결정
온디바이스 NLLB-200-distilled-600M (ONNX) 사용

### 이유
- 개인정보: 오디오/텍스트가 외부 서버로 나가지 않음
- 비용: API 과금 없음
- 오프라인: 네트워크 없이도 동작
- NLLB-200은 200개 언어 지원, 600M distilled 모델은 모바일에서 실행 가능한 크기

### 결과
완전 온디바이스 추론 달성. 단, 저사양 기기에서 번역 지연 문제는 남아있음.

---

## ADR-002: ASR 엔진 — sherpa-onnx + Whisper

### 날짜
2026-03-01

### 상태
확정

### 맥락
온디바이스 음성 인식 엔진 선택. Vosk, Whisper.cpp, sherpa-onnx 등 후보.

### 결정
sherpa-onnx (k2-fsa) + Whisper ONNX INT8 모델

### 이유
- Android arm64 바이너리 및 Java API 공식 지원
- Whisper는 다국어 인식 + 언어 자동 감지 지원
- INT8 양자화로 모바일에서 실행 가능한 크기 (Tiny ~116MB)
- tar.bz2 릴리즈로 모델 다운로드 자동화 용이

### 결과
다국어 음성 인식 정상 동작. Tiny 모델 기준 실시간 처리 가능.

---

## ADR-003: onnxruntime-android 버전 — 1.17.1 고정

### 날짜
2026-03-10

### 상태
확정

### 맥락
NLLB ONNX 세션 실행을 위해 onnxruntime-android 추가 시 버전 선택 필요. sherpa-onnx도 내부적으로 `libonnxruntime.so`를 번들링함.

### 결정
`onnxruntime-android:1.17.1` 고정 + `pickFirst("**/libonnxruntime.so")`

### 이유
- 1.19.0 사용 시 `NoClassDefFoundError: ai.onnxruntime.OrtEnvironment` 런타임 크래시 발생
- sherpa-onnx가 번들링한 `libonnxruntime.so`가 1.17.1 기반이라 JNI 브릿지 버전이 맞아야 함
- `pickFirst`로 sherpa-onnx의 native lib을 우선 사용

### 결과
두 라이브러리가 같은 native lib을 공유하여 충돌 없이 동작.

---

## ADR-004: NLLB 모델 소스 — Xenova 2-세션 → RTranslator 4-세션으로 전환

### 날짜
2026-03-15

### 상태
확정

### 맥락
초기에 HuggingFace Xenova의 merged decoder 모델(2-세션)을 사용. 번역 동작은 했지만 추론이 느리고, decoder 입력 스키마 파악에 어려움이 있었음.

### 결정
RTranslator v2.0.0의 split INT8 모델(4-세션)로 전환

### 이유
- 4-세션 구조(encoder / cache_initializer / decoder / embed_and_lm_head)가 encoder KV 캐시를 1회만 계산 → **4배 빠른 추론**
- RAM 사용량 1.9배 감소
- RTranslator가 실제 Android 앱에서 검증한 모델
- Xenova merged decoder의 복잡한 입력 스키마(`past_key_values.{i}.decoder.key` 등) 대신 명확한 인터페이스

### 결과
추론 속도 개선. 단, BPE 토큰 +1 오프셋 등 RTranslator 고유 규칙을 별도 구현해야 했음.

---

## ADR-005: SentencePiece 파서 — 직접 구현

### 날짜
2026-03-15

### 상태
확정

### 맥락
NLLB 토크나이저는 SentencePiece BPE 모델(`sentencepiece.bpe.model`)이 필요. Android에서 사용할 수 있는 sentencepiece Java 라이브러리가 필요했음.

### 결정
protobuf 의존성 없이 SPM 바이너리 포맷을 직접 파싱하는 `NllbSentencePieceModel` 구현

### 이유
- Google sentencepiece Java 바인딩은 Android에서 native 빌드 필요 → Termux 빌드 환경에서 복잡
- protobuf 라이브러리 추가 시 APK 크기 증가 및 의존성 충돌 리스크
- NLLB용 BPE 디코딩만 필요하므로 전체 SPM 스펙 구현 불필요

### 결과
외부 의존성 없이 동작. `vocabSize` 검증으로 파일 손상 조기 감지 가능.

---

## ADR-006: NLLB 언어 토큰 — 동적 계산

### 날짜
2026-03-16

### 상태
확정

### 맥락
번역 시 `eng_Latn`, `kor_Hang` 등 언어 토큰 ID가 필요한데, `sentencepiece.bpe.model`에 언어 토큰이 없어 `IllegalStateException` 발생.

### 결정
언어 토큰 ID를 `spm_vocab_size + 1 + FLORES-200 인덱스`로 동적 계산. `FAIRSEQ_LANGUAGE_CODES` 목록(200개)을 HuggingFace 순서 그대로 하드코딩.

### 이유
- HuggingFace NLLB 토크나이저는 SPM 파일 로드 후 언어 토큰을 추가 어휘로 동적으로 붙임
- SPM 파일 자체에는 언어 토큰이 없는 것이 정상
- FLORES-200 순서는 HuggingFace 레포 기준으로 고정되어 있음

### 결과
`IllegalStateException` 해결. 200개 언어 토큰 ID 정상 계산.
