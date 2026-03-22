## 2026-03-22 — SPM 어휘 크기 이상 — NllbSentencePieceModel field 번호 오류

### 레벨
3

### 증상
- 재다운로드해도 계속 "SPM 어휘 크기 이상: 0 (정상 ~256000). 파일 재다운로드 필요" 표시
- `tokenizer.vocabSize = 0` — 파일은 4.85 MB로 정상인데 파싱 결과가 비어있음

### 에러 메시지
```
[번역 미준비: SPM 어휘 크기 이상: 0 (정상 ~256000). 파일 재다운로드 필요]
```

### 원인
- `NllbSentencePieceModel.readPieces()`에서 `fieldNum == 4`로 pieces를 찾고 있었음
- 실제 `sentencepiece.proto`에서 `ModelProto.pieces`는 **field 1** (field 4가 아님)
- 따라서 파서가 SPM 파일 전체를 읽어도 pieces를 하나도 찾지 못하고 빈 리스트 반환

### 해결
- `NllbSentencePieceModel.kt`의 `if (fieldNum == 4)` → `if (fieldNum == 1)` 수정
- 주석도 `ModelProto.pieces (field 4)` → `(field 1)` 수정

### 교훈
- protobuf field 번호는 반드시 원본 `.proto` 파일에서 직접 확인 필요 — 주석이나 추정으로 작성하면 안 됨
- sentencepiece.proto: `repeated SentencePiece pieces = 1;` (field 1)
- `vocabSize = 0`으로 에러가 잡혔지만, 이 검증이 없었다면 빈 어휘로 묵시적으로 오작동했을 것

### 관련
- 관련 파일: `translation/NllbSentencePieceModel.kt`
- 관련 Phase: Phase 7 (SPM 파서 버그 수정)

---

## 2026-03-10 — NoClassDefFoundError: ai.onnxruntime.OrtEnvironment

### 레벨
3

### 증상
- NLLB 번역 엔진 초기화 시 앱이 즉시 크래시
- ASR은 정상 동작, 번역만 실패
- 자막에 "번역 실패: NoClassDefFoundError" 표시

### 에러 메시지
```
java.lang.NoClassDefFoundError: ai.onnxruntime.OrtEnvironment
```

### 원인
- `onnxruntime-android:1.19.0` 추가 시 sherpa-onnx가 번들링한 `libonnxruntime.so` (v1.17.1 기반)와 JNI 브릿지 버전 불일치
- Java 클래스는 1.19.0이지만 native lib은 1.17.1 → 클래스 로딩 실패

### 해결
- `onnxruntime-android` 버전을 **1.17.1**로 다운그레이드
- `packagingOptions { jniLibs { pickFirst("**/libonnxruntime.so") } }` 추가하여 sherpa-onnx의 native lib 우선 사용

### 교훈
- sherpa-onnx와 onnxruntime-android는 **반드시 같은 버전**을 써야 함
- 새 onnxruntime 버전으로 올리려면 sherpa-onnx도 함께 업그레이드해야 함
- `pickFirst`는 두 라이브러리가 같은 .so를 번들링할 때 필수

### 관련
- 관련 파일: `app/build.gradle.kts`
- 관련 Phase: Phase 4 (NLLB 번역 엔진 구축)

---

## 2026-03-12 — IllegalStateException: eng_Latn not in vocabulary

### 레벨
2

### 증상
- 번역 시도 시마다 예외 발생
- 자막에 "[번역 오류: IllegalStateException: eng_Latn not in vocabulary]" 표시

### 에러 메시지
```
java.lang.IllegalStateException: eng_Latn not in vocabulary
```

### 원인
- `sentencepiece.bpe.model`에는 일반 BPE 토큰만 있고 언어 토큰(`eng_Latn`, `kor_Hang` 등)은 없음
- HuggingFace NLLB 토크나이저는 Python에서 SPM 로드 후 언어 토큰을 동적으로 추가 어휘로 붙임
- 이 동작을 모르고 SPM 파일에서 언어 토큰을 찾으려 해서 실패

### 해결
- `FAIRSEQ_LANGUAGE_CODES` 목록(200개, HuggingFace FLORES-200 순서 그대로) 하드코딩
- 언어 토큰 ID = `spm_vocab_size + 1 + index` 공식으로 동적 계산
- `NllbBpeTokenizer.getLanguageId()` 메서드 구현

### 교훈
- NLLB 언어 토큰은 SPM 파일 외부에 존재 — HuggingFace 토크나이저 소스 확인 필수
- FLORES-200 순서가 고정되어 있으므로 목록 순서를 바꾸면 모든 언어 토큰 ID가 틀어짐

### 관련
- 관련 파일: `translation/NllbBpeTokenizer.kt`
- 관련 Phase: Phase 4 (NLLB 번역 엔진 구축)

---

## 2026-03-14 — OrtException: Unknown input name past_key_values.0.key

### 레벨
2

### 증상
- NLLB 디코더 세션 실행 시 예외 발생
- 번역이 전혀 안 되고 매 청크마다 오류 표시

### 에러 메시지
```
ai.onnxruntime.OrtException: Unknown input name past_key_values.0.key,
expected one of: past_key_values.0.decoder.key, past_key_values.0.decoder.value,
past_key_values.0.encoder.key, past_key_values.0.encoder.value, use_cache_branch, ...
```

### 원인
- Xenova merged decoder 모델의 실제 입력 이름은 `past_key_values.{i}.decoder.key/value` + `past_key_values.{i}.encoder.key/value` + `use_cache_branch`
- 단순히 `.key`, `.value`로 접근하는 코드를 작성해서 입력명 불일치 발생

### 해결
- Xenova 모델 스키마에 맞게 decoder/encoder 구분된 KV 입력 이름으로 수정
- `use_cache_branch` Boolean 텐서 추가
- 이후 RTranslator 4-세션 모델로 전환하면서 구조 자체가 단순화됨

### 교훈
- ONNX 모델 입력/출력 이름은 모델마다 다름 — 세션 생성 후 `session.inputNames`로 반드시 확인
- Xenova와 RTranslator는 같은 NLLB 모델이라도 입력 스키마가 완전히 다름

### 관련
- 관련 파일: `translation/NllbTranslationEngine.kt`
- 관련 Phase: Phase 4 (NLLB 번역 엔진 구축)

---

## 2026-03-18 — 번역 결과 없음 — 토큰 출력이 [98, 122064, ...] (SPM 어휘 0개)

### 레벨
3

### 증상
- 번역 엔진 로딩 성공, 추론도 실행되지만 한국어 텍스트가 나오지 않음
- 자막에 "|번역결과없음 토큰= [98, 122064, 388, 349, 69, ...]" 표시

### 에러 메시지
```
번역결과없음 토큰= [98, 122064, 388, 349, 69, 248222, 93, 1261, 63314, 248108, ...]
```

### 원인
- `NllbSentencePieceModel.load()`가 파일을 파싱했지만 `pieces.size = 0` (빈 어휘)
- 첫 번째 출력 토큰이 `98` = `kor_Hang` = `0 + 1 + 97` (spm_vocab_size=0일 때의 계산값)
- `spm_vocab_size = 0`이면 모든 언어 토큰이 0번대로 매핑되고, 디코딩 시 일치하는 BPE 조각이 없어 빈 문자열 반환
- 증상에서 역산: `kor_Hang = 98` → `spm_vocab_size + 1 + 97 = 98` → `spm_vocab_size = 0` 확인

### 해결
- `NllbBpeTokenizer.vocabSize` 프로퍼티 추가 (`pieces.size` 노출)
- `NllbTranslationEngine.loadModel()`에 검증 추가: `vocabSize < 10_000`이면 `initError` 설정하고 로딩 중단
- 사용자에게 "SPM 어휘 크기 이상: 0 (정상 ~256000). 파일 재다운로드 필요" 메시지 표시

### 교훈
- 파서가 예외 없이 완료되어도 실제 파싱 결과(piece 수)를 검증해야 함
- 출력 토큰 값으로 내부 상태를 역산할 수 있음 — 디버깅 시 첫 토큰 ID 확인이 유효한 방법
- 모델 로딩 성공 여부를 `isReady`로 추상화할 때 `initError` 메시지를 자막에 직접 표시해야 사용자가 원인을 알 수 있음

### 관련
- 관련 파일: `translation/NllbBpeTokenizer.kt`, `translation/NllbTranslationEngine.kt`, `translation/NllbSentencePieceModel.kt`
- 관련 Phase: Phase 5 (RTranslator 4-세션 모델), Phase 6 (진단 & 안정성)

---

## 2026-03-22 — OrtException: ORT_INVALID_PROTOBUF — ONNX 파일 손상

### 레벨
2

### 증상
- 번역 엔진 로딩 실패, 자막에 "[번역 미준비: OrtException: Error code - ORT_INVALID_PROTOBUF - message: Load model from storage/emulated/...]" 표시
- 모델 관리 화면에서 다운로드가 "완료"로 표시됨에도 불구하고 엔진 초기화 실패

### 에러 메시지
```
OrtException: Error code - ORT_INVALID_PROTOBUF - message: Load model from
storage/emulated/0/Android/data/com.audiosub.app/files/models/nllb-600m-v2/NLLB_*.onnx
```

### 원인
- `downloadIndividualFiles()`에서 `dest.exists() && dest.length() > 0L` 조건만 확인 → 부분 다운로드된 파일(예: 266MB 중 50MB만 저장된 상태)을 "완료"로 간주하고 건너뜀
- 잘린 ONNX 파일은 protobuf 포맷이 불완전하여 ORT가 파싱 실패

### 해결
- `downloadIndividualFiles()`: 파일이 있어도 크기(`dest.length() != size`)가 다르면 삭제 후 재다운로드
- `isBundleReady()`: `IndividualFiles` 번들은 파일 크기까지 대조 — 크기 불일치 시 `false` 반환
- 기존에 손상된 파일은 모델 관리에서 삭제 → 재다운로드로 해결

### 교훈
- 파일 존재 여부만으로 "완료"를 판단하면 안 됨 — 크기(또는 해시) 검증이 필수
- `ModelRegistry.FileEntry.sizeBytes`가 정확히 채워져 있어야 이 검증이 동작함 — 새 파일 추가 시 실제 크기 확인 필요

### 관련
- 관련 파일: `model/ModelDownloadManager.kt`, `model/ModelRegistry.kt`
- 관련 Phase: Phase 6 (진단 & 안정성)

---

## 2026-03-20 — OOM/JNI 크래시가 Exception으로 잡히지 않아 앱 종료

### 레벨
3

### 증상
- NLLB 또는 Whisper 모델 로딩 중 앱이 조용히 종료 (오버레이·알림 모두 사라짐)
- 로그에 크래시 기록 없음

### 에러 메시지
```
java.lang.OutOfMemoryError (또는 JNI SIGSEGV)
```

### 원인
- 모델 로딩 코드가 `catch (e: Exception)`만 사용
- `OutOfMemoryError`와 JNI 네이티브 크래시는 `Error`를 상속하므로 `Exception`으로 잡히지 않음

### 해결
- `catch (e: Exception)` → `catch (e: Throwable)`로 변경
- `NllbTranslationEngine.init`, `AudioCaptureService.initEngines()` 두 곳 모두 수정

### 교훈
- 대용량 모델(수백 MB) 로딩 시 OOM은 예외가 아닌 정상 케이스로 간주하고 `Throwable`로 처리
- native 코드(JNI)를 호출하는 구간은 `Error` 계열 크래시 가능성을 항상 고려

### 관련
- 관련 파일: `translation/NllbTranslationEngine.kt`, `service/AudioCaptureService.kt`
- 관련 Phase: Phase 6 (진단 & 안정성)
