# 실시간/카메라/갤러리 독서 모드 요구사항 및 현재 구현 평가

- 문서 목적: 요청된 사용자 동작을 먼저 명세로 고정하고, 현재 레포 구현이 그 명세를 얼마나 충족하는지 코드 기준으로 평가한다.
- 평가 기준 시점: 현재 워킹트리의 소스 파일 기준
- 평가 범위: `app`, `modules/feature/*`, `modules/core/*`, 관련 테스트

## 1. 이번 문서에서 고정하는 목표 동작

### 1.1 실시간 모드

#### A. 단어/문장 읽기

- 단어 1개를 탭하면 해당 단어를 즉시 TTS로 읽는다.
- 단어 2개를 연속 선택하거나 드래그로 여러 단어를 선택하면 선택된 단어 범위 또는 문장을 TTS로 읽는다.
- 다중 선택 읽기에서는 사전 팝업이 열리면 안 된다.

#### B. 단어 조회

- 단어 1개를 탭한 경우에만 사전을 띄운다.
- 사전은 영영/영한 조회를 함께 지원해야 한다.
- 단어 2개 탭, 다중 단어 선택, 드래그 선택에서는 사전 조회가 실행되면 안 된다.

#### C. TTS 정책

- `autoSpeakEnabled` 기본값은 off 이다.
- `autoSpeakEnabled` 가 on 이면 단어 1개 탭 시 읽기와 사전 표시가 함께 동작해야 한다.
- 기본 TTS 경로는 로컬 모델이다.
- 로컬 모델을 사용할 수 없을 때만 시스템 내장 TTS로 fallback 한다.
- 로컬 모델은 여러 화자 모드를 지원해야 한다.
- 엔진 선택의 개념은 로컬 TTS 모델 / 안드로이드 기본 TTS / 삼성 TTS 우선순위로 해석한다.

### 1.2 카메라 기능

- 카메라 버튼으로 현재 화면을 정지(freeze)한다.
- 정지된 화면에서는 실시간 모드와 동일한 탭/다중 선택/드래그/TTS/사전 동작을 제공해야 한다.

### 1.3 갤러리 기능

- 갤러리에서 사진을 선택해 정지 화면으로 진입한다.
- 갤러리 정지 화면에서도 실시간 모드와 동일한 탭/다중 선택/드래그/TTS/사전 동작을 제공해야 한다.

## 2. 현재 구현 매핑

### 2.1 핵심 UI 및 상태

- 메인 화면 진입: `app/src/main/kotlin/com/example/bookhelper/MainActivity.kt`
- 화면 UI 및 실시간/스냅샷/갤러리 제어: `app/src/main/kotlin/com/example/bookhelper/ui/ReaderScreen.kt`
- 탭/드래그/사전/TTS 상태 전이: `app/src/main/kotlin/com/example/bookhelper/ui/ReaderViewModel.kt`
- 화면 상태 모델: `app/src/main/kotlin/com/example/bookhelper/ui/ReaderUiState.kt`
- 오버레이 표시 로직: `app/src/main/kotlin/com/example/bookhelper/ui/ReaderOverlayModel.kt`

### 2.2 선택 로직

- 단일 단어 히트 테스트, 범위 선택, 드래그 영역 선택: `modules/feature/text-postprocess/src/main/kotlin/com/example/bookhelper/text/SelectionResolver.kt`
- 두 번 탭 기반 다중 선택 윈도우: `modules/feature/text-postprocess/src/main/kotlin/com/example/bookhelper/text/TimedTapSelectionEngine.kt`

### 2.3 TTS

- 시스템/로컬 TTS 라우팅: `app/src/main/kotlin/com/example/bookhelper/tts/AndroidTtsManager.kt`
- 로컬 Kokoro 재생 엔진: `app/src/main/kotlin/com/example/bookhelper/tts/LocalModelTtsEngine.kt`
- 번들 모델/화자 목록: `modules/feature/tts/src/main/kotlin/com/example/bookhelper/tts/BundledTtsModel.kt`
- 로컬 TTS 활성화 정책: `modules/feature/tts/src/main/kotlin/com/example/bookhelper/tts/LocalTtsSelectionPolicy.kt`
- 런타임 상태/route 계산: `modules/feature/tts/src/main/kotlin/com/example/bookhelper/tts/LocalTtsRuntimeStatus.kt`

### 2.4 사전

- Room 기반 조회 데이터 소스: `app/src/main/kotlin/com/example/bookhelper/dictionary/RoomDictionaryLookupDataSource.kt`
- Room DAO: `app/src/main/kotlin/com/example/bookhelper/data/local/DictionaryDao.kt`
- 사전 DB 설치/오픈: `app/src/main/kotlin/com/example/bookhelper/data/local/DictionaryDatabase.kt`

### 2.5 실시간 OCR / 정지 화면 OCR

- 실시간 CameraX + ML Kit 분석: `app/src/main/kotlin/com/example/bookhelper/camera/BookPageAnalyzer.kt`
- 정지 이미지 ML Kit 분석: `app/src/main/kotlin/com/example/bookhelper/camera/StillImageAnalyzer.kt`

## 3. 요구사항별 구현 평가

평가 상태 정의:

- `Implemented`: 요청 의미와 현재 구현이 거의 일치한다.
- `Mostly Implemented`: 핵심 사용자 플로우는 맞지만 구현 방식 또는 검증 수준에 남는 리스크가 있다.
- `Partial`: 일부는 맞지만 기본 동작, 조건, 기본값, 예외 처리 중 중요한 차이가 있다.
- `Missing`: 핵심 동작이 없거나 사실상 다른 기능이다.

### R1. 단어 1개 탭 시 즉시 읽기 + 사전 동시 표시

- 상태: `Mostly Implemented`
- 근거:
  - 단일 탭은 `ReaderViewModel.onTap()` 에서 `scheduleTapDictionaryLookup()` 으로 들어간다.
  - 실제 사전 조회는 `tapSelectionWindowMs` 대기 후 실행된다.
  - 읽기는 `scheduleDictionaryLookup()` 안에서 `autoSpeakEnabled == true` 일 때 실행된다.
  - 따라서 `autoSpeakEnabled` 기본값이 off 인 것은 기능 미구현이 아니라 설정 기본값으로 볼 수 있다.
  - 다만 현재 코드에서는 읽기 대상이 `SpeechTarget.WORD` 또는 `SpeechTarget.SENTENCE` 로 갈린다.
- 해석:
  - `autoSpeakEnabled` 가 on 이면 단일 탭에서 사전과 읽기가 함께 동작하는 경로가 구현돼 있다.
  - 따라서 이 항목의 핵심 기능은 현재 코드상 존재한다.
  - 다만 읽기가 탭 직후 즉시 실행되기보다는 tap window 와 dictionary debounce 이후 실행된다.
- 추가 차이:
  - 사전 준비가 안 된 상태에서는 `onTap()` 초반에 바로 return 하므로, 단어 읽기조차 수행되지 않는다.

### R2. 단어 2개 연속 선택 시 다중 선택 읽기, 사전 미표시

- 상태: `Mostly Implemented`
- 근거:
  - `TimedTapSelectionEngine.onTap()` 이 시간 윈도우 안의 2회 탭을 다중 선택으로 해석한다.
  - `ReaderViewModel.onTap()` 은 timed selection 이 잡히면 `speakWithCurrentTts()` 를 즉시 호출한다.
  - 같은 분기에서 `isDictionaryDialogVisible = false` 로 사전 팝업을 닫는다.
- 해석:
  - 현재 구현은 "단어 두 개 탭 -> 선택 범위 읽기"와 "사전 미표시"를 충족한다.
  - 다만 용어상 "문장" 전체를 읽는 것이 아니라, 두 탭 사이의 단어 범위를 읽는 구현이다.

### R3. 드래그 선택 시 선택 범위 읽기, 사전 미표시

- 상태: `Mostly Implemented`
- 근거:
  - `ReaderScreen.kt` 의 `detectDragGestures` 가 선택 박스를 만든다.
  - 드래그 종료 시 `onDragSelect()` 로 source 좌표를 전달한다.
  - `ReaderViewModel.onDragSelect()` 는 `SelectionResolver.resolveWordsInRegion()` 으로 단어들을 모은 뒤 TTS를 호출한다.
  - 같은 분기에서 `isDictionaryDialogVisible = false` 로 사전을 닫는다.
- 해석:
  - 요청한 "드래그 선택 읽기"와 "사전 비활성"은 구현돼 있다.
  - 현재 방식은 텍스트 드래그 핸들이 아니라 사각형 영역 선택이다.

### R4. 단어 1개 탭일 때만 사전 조회 허용

- 상태: `Mostly Implemented`
- 근거:
  - 단일 탭 분기만 `scheduleTapDictionaryLookup()` 으로 이어진다.
  - 다중 탭/드래그 분기에서는 dictionary lookup 을 취소하고 TTS만 실행한다.
- 해석:
  - 요청한 사전 진입 조건은 현재 코드와 맞다.

### R5. 기본 TTS는 로컬 모델, 실패 시 시스템 TTS fallback

- 상태: `Partial`
- 근거:
  - fallback 자체는 `AndroidTtsManager.speak()` 에 구현돼 있다. 로컬 실패 시 시스템 TTS로 넘긴다.
  - 그러나 현재 코드의 기본값은 로컬 우선이 아니다.
  - `ReaderSettingsStore.load()` 의 기본 `localModelEnabled` 값은 `false` 이다.
  - `LocalTtsSelectionPolicyTest` 도 로컬 TTS를 "사용자 토글이 켜져야만" 활성화되는 정책으로 검증한다.
- 해석:
  - 현재 구현은 "로컬 TTS 지원 + fallback" 은 맞다.
  - 하지만 사용자 의도인 "기본 경로는 로컬 TTS 모델" 과는 아직 다르다.

### R6. 로컬 TTS 화자 모드 지원

- 상태: `Mostly Implemented`
- 근거:
  - `BundledTtsModels.KokoroEnV019` 에 11개 speaker profile 이 정의돼 있다.
  - `ReaderScreen.kt` 설정 다이얼로그에서 화자 버튼 목록을 렌더링한다.
  - `ReaderViewModel.setLocalSpeaker()` 가 speaker id 를 저장하고 엔진에 반영한다.
- 해석:
  - 여러 화자 모드 지원은 구현돼 있다.

### R7. 카메라 버튼으로 화면 정지 후 실시간 모드와 같은 기능 제공

- 상태: `Mostly Implemented`
- 근거:
  - `ReaderScreen.kt` 의 `captureSnapshot` 이 `previewView.bitmap` 을 복사해 스냅샷 모드로 전환한다.
  - 스냅샷은 `StillImageAnalyzer.analyze()` 로 OCR 처리된다.
  - 탭/드래그 입력은 실시간/스냅샷 공통으로 같은 Canvas, 같은 `onTap`, 같은 `onDragSelect` 를 사용한다.
  - 오버레이도 동일 상태를 기반으로 렌더링된다.
- 해석:
  - 사용자 관점 기능은 대부분 동일하게 동작하도록 연결돼 있다.
  - 다만 구현은 고해상도 정지 캡처가 아니라 `PreviewView` bitmap 스냅샷 기반이다.
  - 전용 자동화 테스트는 아직 확인되지 않았다.

### R8. 갤러리 이미지 선택 후 정지 화면에서 실시간 모드와 같은 기능 제공

- 상태: `Mostly Implemented`
- 근거:
  - `ReaderScreen.kt` 가 `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia())` 로 이미지를 받는다.
  - 선택된 이미지는 `loadBitmapFromUri()` -> `StillImageAnalyzer.analyze()` 경로로 OCR 처리된다.
  - 이후 상호작용은 카메라 스냅샷과 동일한 `onTap`/`onDragSelect`/overlay 경로를 사용한다.
- 해석:
  - 갤러리 정지 화면의 기능 패리티는 구조상 확보돼 있다.
  - 역시 전용 UI/통합 테스트는 보이지 않는다.

## 4. 현재 구현의 핵심 불일치

### 4.1 `autoSpeakEnabled` 기본값 off 는 현재 요구와 충돌하지 않음

- 사용자 설명 기준으로 `autoSpeakEnabled` 는 기본 off 가 맞다.
- 따라서 earlier mismatch 로 잡았던 "항상 읽어야 한다"는 평가는 취소한다.
- 평가 기준은 `autoSpeakEnabled == true` 일 때 단일 탭에서 읽기와 사전이 함께 동작하는지로 보는 것이 맞다.

### 4.2 발화 모델 선택 축과 읽기 대상 축은 별도다

- `SpeechTarget` 은 현재 코드처럼 `WORD` / `SENTENCE` 읽기 대상 의미로 해석하는 것이 맞다.
- 별도로 발화 모델 선택 축은 로컬 TTS 모델 / 안드로이드 기본 TTS / 삼성 TTS 쪽으로 존재한다.
- 현재 구현에서 발화 모델 선택은 `localModelEnabled` 와 `TtsEnginePreference` 조합으로 나뉘어 있다.
- 따라서 이 영역의 점검 포인트는 `SpeechTarget` 이름 자체가 아니라, 발화 모델 기본값과 UI 설명이 사용자 의도와 맞는지 여부다.

### 4.3 로컬 TTS가 기본값이 아님

- 현재 로컬 TTS는 사용자 opt-in 이 필요하다.
- 요청사항은 로컬 모델이 디폴트다.

### 4.4 사전 미준비 상태에서 단어 읽기까지 차단됨

- `ReaderViewModel.onTap()` 은 사전이 준비되지 않으면 전체 탭 처리를 중단한다.
- 요청사항 기준으로는 사전이 실패해도 읽기 자체는 분리해서 제공할 여지가 크다.

## 5. 테스트 커버리지 평가

### 현재 확인된 테스트

- OCR 후처리 + 조회 파이프라인: `app/src/test/kotlin/com/example/bookhelper/integration/ReaderLookupPipelineTest.kt`
- 오버레이 선택 렌더링: `app/src/test/kotlin/com/example/bookhelper/ui/ReaderOverlayModelTest.kt`
- 로컬 TTS 재생/지연: `app/src/androidTest/kotlin/com/example/bookhelper/tts/LocalTtsPlaybackInstrumentedTest.kt`, `app/src/androidTest/kotlin/com/example/bookhelper/tts/LocalTtsLatencyInstrumentedTest.kt`
- 로컬 TTS 활성화 정책: `modules/feature/tts/src/test/kotlin/com/example/bookhelper/tts/LocalTtsSelectionPolicyTest.kt`

### 아직 비어 있는 검증 영역

- `autoSpeakEnabled == true` 인 상태에서 단일 탭 읽기와 사전이 함께 동작하는지 여부
- 엔진 선택 기본값이 사용자 의도(로컬 / 안드로이드 / 삼성)와 일치하는지 여부
- 다중 선택 시 사전이 절대 열리지 않는지 여부
- 카메라 스냅샷 모드와 갤러리 모드가 실시간 모드와 동일 동작을 보이는지 여부
- `previewView.bitmap` 기반 스냅샷이 실제 카메라 freeze 요구사항에 충분한지 여부

## 6. 현재 결론

### 구현 완료에 가까운 부분

- `autoSpeakEnabled == true` 조건에서의 단일 탭 읽기 + 사전 표시 경로
- 두 번 탭 기반 다중 선택 읽기
- 드래그 기반 다중 선택 읽기
- 단일 단어 사전 조회
- 로컬/시스템 TTS 이중 경로와 fallback
- 다중 화자 로컬 TTS
- 카메라 스냅샷/갤러리 이미지에서 공통 선택 로직 재사용

### 아직 수정이 필요한 부분

- 발화 모델 선택 기본값과 UI 설명을 사용자 의도와 일치시킴
- 로컬 TTS를 기본 경로로 전환
- 사전 준비 상태와 TTS 읽기 가능 여부를 분리
- 카메라/갤러리 freeze 패리티에 대한 자동화 테스트 추가

## 7. 다음 문서화/구현 기준

이 문서를 기준으로 후속 작업을 진행할 경우, 우선순위는 아래 순서가 자연스럽다.

1. 단일 탭 UX 규칙 고정: "단어 읽기 + 사전 동시 표시"
2. 발화 모델 선택 기본값과 UI 설명(`localModelEnabled` / `TtsEnginePreference`)을 정리
3. 로컬 TTS default 정책 반영
4. 카메라 freeze / 갤러리 freeze 패리티 테스트 추가
