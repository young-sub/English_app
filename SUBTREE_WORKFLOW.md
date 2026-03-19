# Git Worktree 병렬 작업 워크플로

이 문서는 이 저장소의 병렬 개발 방식을 `git worktree` 기준으로 정리한 운영 문서다.

기준 문서:

- 제품/검증 기준: `ANDROID_OFFLINE_READER_SPEC_AND_TEST_PLAN.md`
- 경로/모듈 기준: `gradle/repo-layout.properties`
- Gradle 포함 기준: `settings.gradle.kts`

핵심 전제는 다음과 같다.

- 승인 대상 MVP 범위는 오프라인 리더 스펙의 F1~F6 이다.
- 병렬 작업은 컴포넌트별 코드 소유 영역을 기준으로 나누고, worktree 로 작업 공간을 분리한다.
- 통합 검증은 항상 이 저장소의 `app` 과 `modules/integration/tests` 에서 수행한다.
- 실제 Gradle module 경로의 기준은 `gradle/repo-layout.properties` 이다.

## 현재 저장소 구성

### 병렬 작업 대상 영역

- `core-contracts` -> `modules/core/contracts`
- `f1-provisioning` -> `modules/feature/provisioning`
- `f2-camera-ocr` -> `modules/feature/camera-ocr`
- `f3-text-postprocess` -> `modules/feature/text-postprocess`
- `f4-dictionary` -> `modules/feature/dictionary`
- `f5-tts` -> `modules/feature/tts`
- `f6-performance` -> `modules/core/performance`

위 이름은 병렬 작업 단위를 설명하기 위한 논리 이름이다. 현재 운영 기준은 같은 저장소에서 브랜치를 나눠 여러 worktree 를 띄우는 방식이다.

### 통합 레이어

- `app`: Android 실제 앱 조립, Compose UI, CameraX/ML Kit/Room/TTS 자산 결합
- `modules/integration/tests`: F1~F6 조합 스모크 테스트 레이어
- `tools/dictionary_etl`: 오프라인 사전 자산 생성 파이프라인

주의:

- 현재 레포는 단일 저장소 + 다중 worktree 운영을 전제로 한다.
- 기능 모듈과 `app`, `modules/integration/tests`, 자산/ETL 도구를 하나의 저장소에서 함께 유지한다.
- 따라서 병렬 작업의 완료 조건은 개별 worktree 안의 구현 완료가 아니라, 최종 통합 레이어 정합성 확인까지 포함한다.

## 스펙 기준 작업 분해

`ANDROID_OFFLINE_READER_SPEC_AND_TEST_PLAN.md` 의 승인 대상 범위를 병렬 작업 영역에 매핑하면 다음과 같다.

- F1 초기 1회 준비 -> `f1-provisioning`
- F2 카메라 + OCR 파이프라인 -> `f2-camera-ocr`
- F3 텍스트 후처리 + 선택 로직 -> `f3-text-postprocess`
- F4 오프라인 사전 계층(Room) -> `f4-dictionary`
- F5 문장 TTS(오프라인) -> `f5-tts`
- F6 성능 최적화 -> `f6-performance`
- 공통 계약/모델 -> `core-contracts`

다만 실제 구현에서는 아래 의존 규칙을 함께 적용한다.

- 계약 변경이 필요하면 `core-contracts` 를 먼저 확정한다.
- F2/F3/F4/F5/F6 는 병렬 구현이 가능해 보여도, 공개 타입과 이벤트 계약은 `core-contracts` 에 먼저 반영되어야 한다.
- `app` 은 개별 작업 브랜치의 결과를 사용자 플로우로 묶는 통합 지점이다.
- `modules/integration/tests` 는 "준비 완료 -> OCR/후처리 -> 사전 조회 -> TTS -> 성능 가드" 흐름의 회귀 감시 지점이다.

## 병렬 작업 원칙

### 1. 스펙 선확정

- `ANDROID_OFFLINE_READER_SPEC_AND_TEST_PLAN.md` 가 검토/승인 전용 문서인 동안은 구현 범위를 임의로 넓히지 않는다.
- 승인되지 않은 기능, 최적화, 확장은 각 worktree 브랜치에서도 선구현하지 않는다.
- 작업 시작 전 담당 worktree 가 어떤 F 영역을 맡는지 명확히 적는다.

### 2. 계약 우선

- 교차 모듈에 영향이 있는 변경은 `core-contracts` 를 먼저 변경하고 공유한다.
- 다른 worktree 는 계약 변경 브랜치 또는 기준 commit 을 맞춘 뒤 진행한다.
- 계약이 미정이면 병렬 구현을 진행하지 말고 먼저 인터페이스를 고정한다.

### 3. 로컬 품질 게이트 우선

- 각 worktree 는 자신이 변경한 모듈의 단위 테스트/계약 테스트를 먼저 통과시킨다.
- `f5-tts` 는 번들 모델 경로와 런타임 준비 조건을 함께 확인한다.
- `f4-dictionary` 는 로컬 조회 원칙을 깨는 네트워크 의존을 추가하지 않는다.
- `f1-provisioning` 은 Ready 이후 비행기 모드 재진입 불변 조건을 깨지 않아야 한다.

### 4. 통합 worktree 에서 최종 확인

- 개별 worktree 변경을 통합 브랜치에 반영한 뒤에는 최소한 `modules/integration/tests` 와 관련 `app` 테스트를 다시 확인한다.
- 오프라인 계약은 "초기 1회 준비 완료 후 핵심 기능 네트워크 없이 동작" 이므로, 통합 검증은 이 조건을 기준으로 판단한다.
- 개별 worktree 테스트가 모두 초록이어도 통합 테스트가 깨지면 완료로 보지 않는다.

## 권장 작업 순서

1. 기준 스펙에서 해당 작업이 F1~F6 중 어디에 속하는지 확인한다.
2. 교차 모듈 영향이 있으면 `core-contracts` 변경 여부를 먼저 판단한다.
3. 작업별 브랜치를 만들고 별도 worktree 를 생성한다.
4. 각 worktree 에서 구현과 로컬 테스트를 완료한다.
5. 브랜치별 변경을 리뷰 가능한 단위로 정리한다.
6. 통합 브랜치 또는 메인 작업 트리에서 반영 후 모듈/앱 테스트를 수행한다.
7. 준비 완료 상태에서 오프라인 핵심 플로우를 재검증한다.

## 권장 worktree 명령

### 새 worktree 생성

```bash
git worktree add "..\\english_app-f3-text-postprocess" -b "feature/f3-text-postprocess" HEAD
```

예시는 F3 작업용 별도 worktree 를 하나 만드는 방식이다. 다른 작업도 동일하게 브랜치와 경로만 바꿔 사용한다.

### 활성 worktree 확인

```bash
git worktree list
```

### 작업 종료 후 worktree 정리

```bash
git worktree remove "..\\english_app-f3-text-postprocess"
```

## 통합 검증 체크리스트

### 필수 검증

- worktree 별 변경 모듈 단위 테스트 통과
- `modules/integration/tests` 스모크 테스트 통과
- 변경된 기능과 직접 연결된 `app/src/test/...` 테스트 통과
- `app` 조립 시 모듈 의존성과 자산 경로가 정상 해석됨

### 오프라인 계약 검증

다음 4개는 스펙 기준 핵심 오프라인 기능이다.

1. 카메라 OCR 인식
2. 단어 탭 -> 오프라인 사전 조회
3. 문장 탭 -> 오프라인 TTS 재생
4. 단어장 저장/조회

검증 조건:

- 초기 1회 준비 완료 후 비행기 모드 콜드 스타트 가능
- 핵심 기능 수행 중 필수 네트워크 호출 0건
- 네트워크 미연결로 인한 무한 로딩/블로킹/크래시 없음

### 현재 레포에서 확인 가능한 대표 테스트 지점

- `modules/feature/provisioning/src/test/kotlin/com/example/bookhelper/provisioning/ProvisioningStateMachineTest.kt`
- `modules/feature/camera-ocr/src/test/kotlin/com/example/bookhelper/camera/CameraCoreTest.kt`
- `modules/feature/text-postprocess/src/test/kotlin/com/example/bookhelper/text/TextProcessingTest.kt`
- `modules/feature/dictionary/src/test/kotlin/com/example/bookhelper/dictionary/DictionaryCoreTest.kt`
- `modules/feature/tts/src/test/kotlin/com/example/bookhelper/tts/TtsCoreTest.kt`
- `modules/core/performance/src/test/kotlin/com/example/bookhelper/perf/PerformanceCoreTest.kt`
- `modules/integration/tests/src/test/kotlin/com/example/bookhelper/integration/ReaderIntegrationTest.kt`
- `app/src/test/kotlin/com/example/bookhelper/integration/ReaderLookupPipelineTest.kt`
- `app/src/test/kotlin/com/example/bookhelper/ui/ReaderOverlayModelTest.kt`

위 테스트들은 현재 레포에 존재하는 검증 지점이다. 스펙 문서에 있는 계측 테스트/매크로벤치마크 계획은 아직 별도 구현 단계로 관리해야 한다.

## 운영 규칙

- 모듈 경로를 하드코딩하지 말고 항상 `gradle/repo-layout.properties` 기준으로 본다.
- 구조 변경 시 `settings.gradle.kts`, 관련 문서, 작업 스크립트를 함께 갱신한다.
- `modules/` 는 통합 저장소의 canonical Gradle 경로이므로, 문서에는 실제 경로 기준으로 적는다.
- `app` 또는 `modules/integration/tests` 변경이 필요한 작업은 개별 모듈 worktree 수정만으로 끝내지 말고 통합 반영까지 한 세트로 본다.

## 요약

이 저장소의 병렬 개발 워크플로는 "기능별 worktree 분리 -> 각 worktree 에서 구현/검증 -> 통합 브랜치에서 앱/통합 테스트/오프라인 계약 검증" 의 3단계로 운영한다. 현재 기준선은 `modules/...` 와 `app` 을 포함한 단일 저장소 + 다중 worktree 운영 모델이다.
