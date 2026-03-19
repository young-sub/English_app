# 안드로이드 오프라인 독서 보조 앱

## 구현 전 상세 스펙 및 테스트 코드 계획서 (검토용)

- 문서 버전: v0.2
- 작성일: 2026-03-09
- 상태: 검토 전용(구현 금지)

---

## 0. 문서 목적

- 이 문서는 코드 구현 문서가 아니라, **구현 전에 합의할 상세 스펙 문서**다.
- 각 기능이 정확히 무엇을 요구하는지 명확히 정의한다.
- 각 요구사항을 어떻게 테스트 코드로 검증할지 사전에 계획한다.

### 명시적 구현 금지 정책

- 본 문서가 검토/승인되기 전에는 구현하지 않는다.
- 승인된 항목만 구현한다.
- 승인되지 않은 기능, 최적화, 확장 요구는 선구현하지 않는다.

---

## 1. 기본 타깃 디바이스 프로파일 (디폴트)

- 기본 타깃: **Galaxy S24 Plus**
- OS 기준: Android 14 이상
- 성능 기준: 플래그십급 CPU/GPU, 고용량 메모리 환경을 기본 가정
- 디스플레이 기준: 고해상도/고주사율 대화면 UX 기준
- 카메라 기준: 후면 카메라 중심, 자동초점 안정성 전제

주의:

- S24+는 기준선(최적화 우선 대상)이며, 유일 타깃은 아니다.
- 테스트 매트릭스에는 하위 성능 Android 기기 1종 이상을 포함한다.

---

## 2. 오프라인 계약(Contract)

본 프로젝트의 오프라인 정의:

- **초기 1회 모델/음성 준비가 끝난 뒤**, 핵심 기능은 네트워크 없이 동작해야 한다.

핵심 기능 범위:

1. 카메라 OCR 인식
2. 단어 탭 -> 영영/영한 오프라인 사전 조회
3. 문장 탭 -> 오프라인 TTS 재생
4. 단어장 저장/조회

### 2.1 초기 1회 준비(Provisioning)

- OCR 모델 준비 상태를 확인하고 앱 내부 상태로 저장한다.
- TTS 대상 언어 음성 리소스 준비 상태를 확인하고 저장한다.
- 준비 과정이 중단되어도 재시도/복구 가능한 상태머신을 사용한다.
- 준비 완료 후에는 핵심 기능 진입 시 추가 다운로드를 요구하지 않는다.

### 2.2 오프라인 불변 조건

- 준비 완료 후 비행기 모드 콜드 스타트 가능
- 핵심 기능 수행 중 네트워크 의존 동작 없음
- 네트워크 미연결로 인한 무한 로딩/블로킹/크래시 없음

---

## 3. 승인 대상 MVP 범위

1. CameraX `Preview + ImageAnalysis`
2. ROI 기반 OCR 파이프라인
3. OCR 후처리(단어/문장 선택 가능 구조)
4. 단어 탭 -> 사전 조회
5. 문장 탭 -> TTS
6. 단어장 저장
7. 성능 기본 세트(샘플링, 백프레셔, 캐시, 재분석 억제)

---

## 4. 기능별 상세 스펙 + 테스트 코드 계획

기술 형식:

- 요구사항 ID
- 수용 기준(AC)
- 테스트 코드 계획(추후 작성할 테스트 코드)

## F1. 초기 1회 준비(Provisioning)

### 요구사항

- P1: 준비 상태를 `NotReady/Preparing/Ready/FailedRecoverable/FailedFatal`로 관리
- P2: 준비 미완료 시 핵심 기능 진입 전 명확한 안내 제공
- P3: 앱 강제종료/재실행 시에도 준비 상태 무결성 보장
- P4: `Ready` 이후 비행기 모드에서 재준비 없이 핵심 기능 사용 가능

### 수용 기준

- AC-P1: 준비 완료 후 콜드 스타트에서도 `Ready` 유지
- AC-P2: 복구 가능 실패/치명 실패를 구분 표시
- AC-P3: 복구 가능 실패는 재시도로 `Ready` 전환 가능

### 테스트 코드 계획

- 단위 테스트
  - 대상: 준비 상태머신
  - 검증: 상태 전이, 실패/복구/재시도 시나리오
  - 예정 파일: `app/src/test/java/.../provisioning/ProvisioningStateMachineTest.kt`
- 계측 테스트
  - 대상: 첫 실행 준비 플로우 UI
  - 검증: 준비 미완료 차단, 준비 완료 후 진입 허용
  - 예정 파일: `app/src/androidTest/java/.../provisioning/ProvisioningFlowTest.kt`

## F2. 카메라 + OCR 파이프라인

### 요구사항

- C1: CameraX `Preview`와 `ImageAnalysis`를 동시 구성
- C2: 분석 백프레셔 latest-only 전략 사용
- C3: 프레임 샘플링(기본 250~400ms)
- C4: ROI 영역 중심 OCR 처리
- C5: block/line/word + bounding box 보존

### 수용 기준

- AC-C1: 분석 지연 시 프레임 적체 없이 최신 프레임 처리
- AC-C2: ROI 외 노이즈 환경에서 중심 텍스트 인식 품질 유지
- AC-C3: 단어/문장 선택에 필요한 bbox 누락 없음

### 테스트 코드 계획

- 단위 테스트
  - 대상: FrameGate, RoiMapper, OcrResultMapper
  - 검증: 샘플링 간격, 좌표 변환, bbox 무결성
  - 예정 파일:
    - `app/src/test/java/.../camera/FrameGateTest.kt`
    - `app/src/test/java/.../ocr/RoiMapperTest.kt`
    - `app/src/test/java/.../ocr/OcrResultMapperTest.kt`
- 계측 테스트
  - 대상: CameraScreen + Analyzer 연동
  - 검증: 권한 처리, 라이프사이클 재진입, 분석 중단/재개
  - 예정 파일: `app/src/androidTest/java/.../camera/CameraPipelineInstrumentedTest.kt`

## F3. 텍스트 후처리 + 선택 로직(단어/문장)

### 요구사항

- T1: 줄 병합 및 하이픈 분리 복원
- T2: 문장 경계 추정(종결부호 + 예외 규칙)
- T3: 단어 정규화(소문자화/구두점 제거/축약형 처리)
- T4: 탭 좌표 선택 해석(contains + 근접도)

### 수용 기준

- AC-T1: 대표 줄바꿈/하이픈 케이스 복원 성공
- AC-T2: 문장 선택 시 기대 범위와 일치
- AC-T3: 손가락 오차 허용 범위에서 의도 단어 선택 가능

### 테스트 코드 계획

- 단위 테스트
  - 대상: TextPostProcessor, SentenceSegmenter, SelectionResolver
  - 검증: 케이스 기반 입출력, 근접도 우선순위
  - 예정 파일:
    - `app/src/test/java/.../text/TextPostProcessorTest.kt`
    - `app/src/test/java/.../text/SentenceSegmenterTest.kt`
    - `app/src/test/java/.../text/SelectionResolverTest.kt`

## F4. 오프라인 사전 계층(Room)

### 요구사항

- D1: 조회는 로컬 DB만 사용
- D2: lemma 조회 + prefix 검색 지원
- D3: OCR 토큰은 정규화 + 표제어 후보를 거쳐 조회
- D4: 결과 정렬 정책(빈도/품사 우선순위) 지원

### 수용 기준

- AC-D1: 네트워크 차단 상태에서도 조회 성공
- AC-D2: 주요 굴절형 단어에서 lemma hit 달성
- AC-D3: prefix 검색 응답이 UX 허용 범위 내

### 테스트 코드 계획

- 단위 테스트
  - 대상: Lemmatizer, DictionaryRepository
  - 검증: 후보 생성 규칙, fallback 순서
  - 예정 파일:
    - `app/src/test/java/.../dictionary/LemmatizerTest.kt`
    - `app/src/test/java/.../dictionary/DictionaryRepositoryTest.kt`
- DB 테스트(계측/Robolectric)
  - 대상: DictionaryDao
  - 검증: lemma/prefix 정확성, 정렬, limit
  - 예정 파일: `app/src/androidTest/java/.../dictionary/DictionaryDaoTest.kt`

## F5. 문장 TTS(오프라인)

### 요구사항

- S1: 문장 단위 발화(기본 flush 정책)
- S2: 발화 상태(onStart/onDone/onError) 추적
- S3: 음성 리소스 미준비 시 명확한 안내/복구 동선 제공
- S4: 재생 상태와 문장 하이라이트 상태 동기화

### 수용 기준

- AC-S1: 비행기 모드에서 문장 재생 성공
- AC-S2: 시작/종료/에러 상태가 UI와 일치
- AC-S3: 음성 미준비 실패가 사용자 액션 가능한 형태로 안내됨

### 테스트 코드 계획

- 단위 테스트
  - 대상: TtsStateReducer, TtsOrchestrator
  - 검증: 이벤트 기반 상태 전이, 에러 처리
  - 예정 파일:
    - `app/src/test/java/.../tts/TtsStateReducerTest.kt`
    - `app/src/test/java/.../tts/TtsOrchestratorTest.kt`
- 계측 테스트
  - 대상: ReaderScreen TTS 플로우
  - 검증: 문장 탭 -> 발화 요청 -> 상태 반영
  - 예정 파일: `app/src/androidTest/java/.../tts/TtsFlowInstrumentedTest.kt`

## F6. 성능 최적화

### 요구사항

- O1: 프레임 샘플링 기본값 300ms
- O2: 동일 페이지 판정(hash/checksum)으로 재분석 억제
- O3: blur gate로 저품질 프레임 스킵
- O4: OCR/사전 조회 결과 캐시

### 수용 기준

- AC-O1: 장시간 사용 시 과도한 CPU 급등 억제
- AC-O2: 동일 페이지에서 불필요 OCR 호출 감소
- AC-O3: 블러 프레임 시 오탐 대신 가이드 메시지 제공

### 테스트 코드 계획

- 단위 테스트
  - 대상: PageHashComparator, BlurScoreCalculator, OcrCache
  - 검증: 임계값 판정, 캐시 hit/miss, 만료 정책
  - 예정 파일:
    - `app/src/test/java/.../perf/PageHashComparatorTest.kt`
    - `app/src/test/java/.../perf/BlurScoreCalculatorTest.kt`
    - `app/src/test/java/.../perf/OcrCacheTest.kt`
- 매크로벤치마크(계획)
  - 대상: 분석 루프 지연/안정성
  - 검증: 시나리오별 p95 지연 추적
  - 예정 파일: `benchmark/src/main/java/.../ReaderMacroBenchmark.kt`

---

## 5. 공통 테스트 전략

### 5.1 테스트 계층

- Unit: 순수 로직 검증
- Instrumented: Android 프레임워크 결합 검증
- Macrobenchmark: 성능 회귀 검증

### 5.2 오프라인 강제 검증

- 준비 완료 후 핵심 시나리오는 비행기 모드에서 수행
- 목표: 핵심 플로우에서 필수 네트워크 호출 0건

### 5.3 테스트 데이터 세트

- OCR 이미지: 선명/저조도/블러/기울어짐/작은 폰트
- 텍스트 케이스: 축약형/문장부호/줄바꿈+하이픈
- 사전 케이스: lemma hit/miss, prefix hit/miss, 동형이의어

---

## 6. 승인 게이트(구현 시작 조건)

아래 4개가 모두 승인되어야 구현 시작 가능:

1. F1~F6 범위 승인
2. AC(수용 기준) 승인
3. 테스트 코드 계획 승인
4. 오프라인 계약 승인(초기 1회 준비 후 완전 오프라인)

게이트 미승인 시 구현 금지.

---

## 7. 승인 후 구현 순서(사전 정의)

1. F1 준비 상태머신/오프라인 계약
2. F2 카메라/OCR 파이프라인
3. F3 후처리/선택
4. F4 사전 계층
5. F5 TTS 계층
6. F6 성능 최적화/벤치마크

본 문서는 계획 문서이며, 현재 시점 구현은 포함하지 않는다.
