# AudioSub v1.0.0

## 한줄 요약
> 스마트폰에서 재생되는 모든 소리를 실시간으로 캡처해 한국어 자막으로 변환해주는 완전 온디바이스 Android 앱.

## 프로젝트 목적
- **누구를 위한 것인가**: 외국어 영상·유튜브·팟캐스트를 한국어로 보고 싶은 사람, 청각 보조가 필요한 사람
- **어떤 문제를 해결하는가**: 자막이 없는 외국어 콘텐츠를 실시간으로 이해할 수 없는 문제. 클라우드 API 없이 개인 기기에서만 동작해 개인정보·비용 문제도 없음
- **완성되면 어떤 모습인가**: 앱을 켜면 반투명 자막 오버레이가 화면 위에 뜨고, 다른 앱을 쓰면서도 실시간 한국어 자막이 표시됨

## 핵심 기능
1. **시스템 오디오 캡처** — MediaProjection으로 앱 재생음 전체를 16kHz PCM으로 수집
2. **온디바이스 음성 인식 (배치)** — sherpa-onnx + Whisper(medium)로 다국어 텍스트 변환, VAD로 묵음 구간 건너뜀
3. **온디바이스 음성 인식 (스트리밍)** — sherpa-onnx + Zipformer Transducer로 실시간 인식 (~0.3초), 영어·한국어·중국어·일본어
4. **온디바이스 한국어 번역** — NLLB-200-distilled-600M (RTranslator 4-세션 split INT8)으로 200개 언어 → 한국어
5. **이중 자막 오버레이** — 원문 상단 소형 + 번역 하단 대형, WindowManager TYPE_APPLICATION_OVERLAY, 드래그 이동 가능
6. **모델 관리** — 앱 내 다운로드 UI (파일별 진행률 표시), ASR·번역·스트리밍 카테고리 구분
7. **3단계 속도 모드** — 균형(~1.5-2초) / 빠름(~1-1.5초) / 실시간(~0.3초) 선택

## 기술 스택
- **프레임워크**: Android (Kotlin, API 29+, arm64-v8a)
- **음성 인식 (배치)**: sherpa-onnx (로컬 JAR + .so) — Whisper ONNX INT8
- **음성 인식 (스트리밍)**: sherpa-onnx — Zipformer Transducer (OnlineRecognizer)
- **번역**: ONNX Runtime 1.17.1 — NLLB-200 4-세션 split 모델 (RTranslator v2.0.0)
- **토크나이저**: 직접 구현한 SentencePiece BPE 파서 (`NllbSentencePieceModel` + `NllbBpeTokenizer`)
- **모델 다운로드**: WorkManager + OkHttp (tar.bz2 압축 해제: Apache Commons Compress)
- **빌드 환경**: Termux (Android 기기 내에서 직접 빌드)
- **선택 이유**: 클라우드 API 없이 완전 온디바이스 추론이 목표 — sherpa-onnx가 Android 최적화된 Whisper 바인딩을 제공하고, RTranslator split 모델이 동일 모델 대비 4배 빠른 추론·1.9배 낮은 RAM을 달성

## 미결정 사항
- [x] NLLB 번역 엔드투엔드 검증 완료 — 한국어 자막 정상 출력 확인
- [x] 안정성 개선 완료 — 오버레이 자동 복구, MediaProjection 종료 처리, WakeLock, 서비스 스코프 정리
- [x] 스트리밍 ASR 구현 완료 — 4개 언어 (EN/KO/ZH/JA)
- [x] UI/UX 개선 완료 — 이중 자막, 속도 모드 드롭다운, 모델 카테고리, 상태 인디케이터
- [ ] Whisper Medium 전환 시점 (기기 RAM 2GB 이상 확인 후)
- [ ] 번역 지연 개선 방법 (저사양 기기에서 NLLB 추론이 느림)

## 참고
- **비슷한 서비스**: RTranslator (Android 실시간 번역 앱, NLLB 모델 출처), Live Captions (Android 12+ 내장, 영어만 지원)
- **핵심 의존 프로젝트**: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [RTranslator](https://github.com/niedev/RTranslator) (NLLB split 모델 제공)
- **모델 출처**: Whisper → sherpa-onnx GitHub Releases / NLLB → RTranslator v2.0.0 Releases + HuggingFace(sentencepiece.bpe.model) / 스트리밍 → HuggingFace (csukuangfj, k2-fsa)
