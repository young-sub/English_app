package com.example.bookhelper.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.bookhelper.camera.BookPageAnalyzer
import com.example.bookhelper.camera.CameraBinder
import com.example.bookhelper.camera.FrameGate
import com.example.bookhelper.camera.StillImageAnalyzer
import com.example.bookhelper.camera.ViewportTransformReducer
import com.example.bookhelper.camera.ViewportTransformState
import com.example.bookhelper.contracts.BoundingBox
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.dictionary.DictionarySense
import com.example.bookhelper.ocr.OcrFrameResult
import com.example.bookhelper.perf.PageHashComparator
import com.example.bookhelper.tts.LocalSpeakerProfile
import com.example.bookhelper.tts.SpeakerGender
import com.example.bookhelper.tts.TtsEnginePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val DetectedWordColor = Color(0xFF38BDF8)
private val SelectedWordColor = Color(0xFFF97316)
private val SelectedSentenceColor = Color(0xFF14B8A6)
private val DictionaryReadyColor = Color(0xFF22C55E)
private val DictionaryMissingColor = Color(0xFFEF4444)
private val DictionaryUnknownColor = Color(0xFF9CA3AF)
private val CameraLetterboxColor = Color(0xFF0F1115)
private val CameraControlStripColor = Color(0xB3293342)
private const val PreviewVerticalBias = 0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    cameraPermissionGranted: Boolean,
    onFrameOcr: (OcrFrameResult) -> Unit,
    onTap: (Float, Float) -> Unit,
    onDragSelect: (Float, Float, Float, Float) -> Unit,
    onSaveWord: () -> Unit,
    onSetSpeechRate: (Float) -> Unit,
    onSetTapSelectionWindowMs: (Long) -> Unit,
    onSetDragSelectionMinDistancePx: (Float) -> Unit,
    onSetTtsEnginePreference: (TtsEnginePreference) -> Unit,
    onSetLocalModel: (String) -> Unit,
    onSetLocalSpeaker: (Int) -> Unit,
    onSetAutoSpeakEnabled: (Boolean) -> Unit,
    onSetSpeechTarget: (SpeechTarget) -> Unit,
    onCloseSettingsDialog: () -> Unit,
    onDismissDictionaryDialog: () -> Unit,
    onRevealKoreanDefinition: () -> Unit,
    onOpenSavedWordsDialog: () -> Unit,
    onDismissSavedWordsDialog: () -> Unit,
    onOpenSavedWord: (SavedWordItem) -> Unit,
    onDeleteSavedWord: (SavedWordItem) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val settingsScroll = rememberScrollState()
    val dictionaryScroll = rememberScrollState()
    val savedWordsScroll = rememberScrollState()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isSnapshotMode by remember { mutableStateOf(false) }
    var isSnapshotProcessing by remember { mutableStateOf(false) }
    var isGalleryPicking by remember { mutableStateOf(false) }
    var analysisToken by remember { mutableLongStateOf(0L) }
    var snapshotOriginLabel by remember { mutableStateOf("촬영") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    val liveAnalysisEnabled = shouldRunLiveAnalysis(
        cameraPermissionGranted = cameraPermissionGranted,
        settingsDialogVisible = showSettingsDialog,
    )

    val viewportReducer = remember { ViewportTransformReducer(minScale = 1f, maxScale = 4f) }
    var viewportTransform by remember { mutableStateOf(ViewportTransformState.Identity) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        viewportTransform = viewportReducer.applyGesture(
            current = viewportTransform,
            zoomChange = zoomChange,
            panX = panChange.x,
            panY = panChange.y,
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_START
        }
    }

    val onFrameOcrState by rememberUpdatedState(onFrameOcr)
    val onTapState by rememberUpdatedState(onTap)
    val onDragSelectState by rememberUpdatedState(onDragSelect)
    val latestViewportTransform by rememberUpdatedState(viewportTransform)
    val latestSourceWidth by rememberUpdatedState(uiState.sourceWidth)
    val latestSourceHeight by rememberUpdatedState(uiState.sourceHeight)
    val stillImageAnalyzer = remember { StillImageAnalyzer() }

    LaunchedEffect(uiState.sourceWidth, uiState.sourceHeight) {
        dragStart = null
        dragCurrent = null
    }

    val analyzer = remember {
        BookPageAnalyzer(
            frameGate = FrameGate(260L),
            pageHashComparator = PageHashComparator(5),
            onOcrResult = { frame ->
                if (shouldAcceptLiveOcrResult(settingsDialogVisible = showSettingsDialog, snapshotMode = isSnapshotMode)) {
                    onFrameOcrState(frame)
                }
            },
        )
    }
    val cameraBinder = remember(context, lifecycleOwner) {
        CameraBinder(context, lifecycleOwner)
    }
    val overlayModel = remember(
        uiState.ocrPage,
        uiState.selectedSentence,
        uiState.selectedWord,
        uiState.selectedWords,
        isSnapshotMode,
    ) {
        buildReaderOverlayModel(
            page = uiState.ocrPage,
            selectedSentence = uiState.selectedSentence,
            selectedWord = uiState.selectedWord,
            selectedWords = uiState.selectedWords,
            renderAllDetected = isSnapshotMode,
        )
    }

    DisposableEffect(liveAnalysisEnabled) {
        if (liveAnalysisEnabled) {
            cameraBinder.bind(previewView, analyzer)
        } else {
            cameraBinder.unbind()
        }
        onDispose { cameraBinder.unbind() }
    }

    DisposableEffect(Unit) {
        onDispose {
            stillImageAnalyzer.close()
            analyzer.close()
            cameraBinder.release()
        }
    }

    val resetAnalyzedFrame = {
        onFrameOcrState(
            OcrFrameResult(
                page = OcrPage.EMPTY,
                sourceWidth = 0,
                sourceHeight = 0,
            ),
        )
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        isGalleryPicking = false
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        isSnapshotMode = true
        isSnapshotProcessing = true
        snapshotOriginLabel = "갤러리"
        capturedBitmap = null
        analysisToken += 1L
        val activeToken = analysisToken
        resetAnalyzedFrame()

        coroutineScope.launch {
            val selectedBitmap = withContext(Dispatchers.IO) {
                loadBitmapFromUri(context, uri)
            }
            if (selectedBitmap == null) {
                if (analysisToken == activeToken) {
                    isSnapshotMode = false
                    isSnapshotProcessing = false
                    capturedBitmap = null
                }
                return@launch
            }
            capturedBitmap = selectedBitmap
            stillImageAnalyzer.analyze(selectedBitmap) { result ->
                if (analysisToken == activeToken && isSnapshotMode && capturedBitmap === selectedBitmap) {
                    onFrameOcrState(result)
                    isSnapshotProcessing = false
                }
            }
        }
    }

    val dictionaryStateText: String
    val dictionaryStateColor: Color
    when {
        !uiState.isDictionaryReady -> {
            dictionaryStateText = "사전 준비 중"
            dictionaryStateColor = DictionaryUnknownColor
        }
        uiState.dictionaryEntries.isNotEmpty() -> {
            dictionaryStateText = "사전 데이터가 있습니다"
            dictionaryStateColor = DictionaryReadyColor
        }
        uiState.message == "No dictionary match" -> {
            dictionaryStateText = "사전에 단어가 없습니다"
            dictionaryStateColor = DictionaryMissingColor
        }
        else -> {
            dictionaryStateText = "단어를 선택하세요"
            dictionaryStateColor = DictionaryUnknownColor
        }
    }

    val captureSnapshot = capture@{
        val snapshot = previewView.bitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return@capture
        capturedBitmap = snapshot
        isSnapshotMode = true
        isSnapshotProcessing = true
        snapshotOriginLabel = "촬영"
        analysisToken += 1L
        val activeToken = analysisToken
        resetAnalyzedFrame()
        stillImageAnalyzer.analyze(snapshot) { result ->
            if (analysisToken == activeToken && isSnapshotMode && capturedBitmap === snapshot) {
                onFrameOcrState(result)
                isSnapshotProcessing = false
            }
        }
    }

    val openGalleryAndAnalyze = {
        if (!isSnapshotProcessing && !isGalleryPicking) {
            isGalleryPicking = true
            galleryPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }

    val returnToLiveMode = {
        analysisToken += 1L
        isSnapshotMode = false
        isSnapshotProcessing = false
        isGalleryPicking = false
        capturedBitmap = null
        analyzer.resetFrameDeduplication()
        viewportTransform = viewportReducer.reset()
        dragStart = null
        dragCurrent = null
        resetAnalyzedFrame()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF3F4F6))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Book Helper", fontWeight = FontWeight.SemiBold)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(dictionaryStateText) } },
                    state = rememberTooltipState(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(dictionaryStateColor, CircleShape),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (abs(viewportTransform.scale - 1f) > 0.01f) {
                    OutlinedButton(
                        onClick = {
                            viewportTransform = viewportReducer.reset()
                            dragStart = null
                            dragCurrent = null
                        },
                    ) {
                        Text("배율 초기화")
                    }
                }
                OutlinedButton(onClick = onOpenSavedWordsDialog) {
                    Text("단어장")
                }
                Button(onClick = { showSettingsDialog = true }) {
                    Text("설정")
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(CameraLetterboxColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = viewportTransform.scale
                        scaleY = viewportTransform.scale
                        translationX = viewportTransform.offsetX
                        translationY = viewportTransform.offsetY
                    }
                    .transformable(state = transformableState),
            ) {
                if (cameraPermissionGranted) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { previewView },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF111827)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "카메라 권한 없이 갤러리 분석을 사용할 수 있습니다.",
                            color = Color(0xFFE5E7EB),
                        )
                    }
                }

                if (isSnapshotMode) {
                    capturedBitmap?.let { bitmap ->
                        Image(
                            modifier = Modifier.fillMaxSize(),
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured frame",
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.TopCenter,
                        )
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(latestSourceWidth, latestSourceHeight, latestViewportTransform) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragStart = start
                                    dragCurrent = start
                                },
                                onDragCancel = {
                                    dragStart = null
                                    dragCurrent = null
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragCurrent
                                    if (start != null && end != null) {
                                        val sourceStart = mapTransformedViewToSource(
                                            x = start.x,
                                            y = start.y,
                                            viewWidth = size.width.toFloat(),
                                            viewHeight = size.height.toFloat(),
                                            sourceWidth = latestSourceWidth,
                                            sourceHeight = latestSourceHeight,
                                            transform = latestViewportTransform,
                                        )
                                        val sourceEnd = mapTransformedViewToSource(
                                            x = end.x,
                                            y = end.y,
                                            viewWidth = size.width.toFloat(),
                                            viewHeight = size.height.toFloat(),
                                            sourceWidth = latestSourceWidth,
                                            sourceHeight = latestSourceHeight,
                                            transform = latestViewportTransform,
                                        )
                                        onDragSelectState(
                                            sourceStart.first,
                                            sourceStart.second,
                                            sourceEnd.first,
                                            sourceEnd.second,
                                        )
                                    }
                                    dragStart = null
                                    dragCurrent = null
                                },
                            ) { _, dragAmount ->
                                val previous = dragCurrent
                                val next = (previous ?: Offset.Zero) + dragAmount
                                if (
                                    previous == null ||
                                    abs(next.x - previous.x) >= 1f ||
                                    abs(next.y - previous.y) >= 1f
                                ) {
                                    dragCurrent = next
                                }
                            }
                        }
                        .pointerInput(latestSourceWidth, latestSourceHeight, latestViewportTransform) {
                            detectTapGestures { offset ->
                                val sourcePoint = mapTransformedViewToSource(
                                    x = offset.x,
                                    y = offset.y,
                                    viewWidth = size.width.toFloat(),
                                    viewHeight = size.height.toFloat(),
                                    sourceWidth = latestSourceWidth,
                                    sourceHeight = latestSourceHeight,
                                    transform = latestViewportTransform,
                                )
                                onTapState(sourcePoint.first, sourcePoint.second)
                            }
                        },
                ) {
                    drawOverlayBoxes(
                        overlayModel = overlayModel,
                        viewWidth = size.width,
                        viewHeight = size.height,
                        sourceWidth = uiState.sourceWidth,
                        sourceHeight = uiState.sourceHeight,
                    )

                    val dragBoxStart = dragStart
                    val dragBoxEnd = dragCurrent
                    if (dragBoxStart != null && dragBoxEnd != null) {
                        val left = minOf(dragBoxStart.x, dragBoxEnd.x)
                        val top = minOf(dragBoxStart.y, dragBoxEnd.y)
                        val width = abs(dragBoxStart.x - dragBoxEnd.x)
                        val height = abs(dragBoxStart.y - dragBoxEnd.y)
                        drawRect(
                            color = Color(0xFF38BDF8),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(width, height),
                            alpha = 0.2f,
                        )
                    }
                }
            }

            SnapshotSearchControlSubtree(
                isSnapshotMode = isSnapshotMode,
                isSnapshotProcessing = isSnapshotProcessing,
                isGalleryPicking = isGalleryPicking,
                cameraPermissionGranted = cameraPermissionGranted,
                onOpenGallery = openGalleryAndAnalyze,
                onCaptureSnapshot = captureSnapshot,
                onReturnToLiveMode = returnToLiveMode,
            )

            if (isSnapshotProcessing || isGalleryPicking) {
                val processingText = if (isGalleryPicking) {
                    "갤러리 열기 중..."
                } else {
                    "${snapshotOriginLabel} 이미지 분석 중..."
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xCC0F172A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = processingText, color = Color(0xFFF8FAFC))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 170.dp)
                .background(Color(0xFFF3F4F6), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("선택 단어: ${uiState.selectedWord?.text ?: "-"}")
            Text(
                text = "선택 문장: ${uiState.selectedSentence ?: "-"}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveWord) {
                    Text("저장")
                }
            }
        }
    }

    if (showSettingsDialog) {
        val dismissSettingsDialog = {
            showSettingsDialog = false
            onCloseSettingsDialog()
        }
        AlertDialog(
            onDismissRequest = dismissSettingsDialog,
            title = { Text("설정") },
            text = {
                val modelPickerScroll = rememberScrollState()
                val displayedModels = uiState.availableLocalModels.sortedWith(
                    compareByDescending<com.example.bookhelper.tts.BundledTtsModel> { it.id.contains("piper", ignoreCase = true) }
                        .thenBy { it.displayName },
                )
                val selectedModel = displayedModels.firstOrNull { it.id == uiState.selectedLocalModelId }
                    ?: uiState.availableLocalModels.firstOrNull { it.id == uiState.selectedLocalModelId }
                val groupedSpeakers = uiState.availableSpeakers.groupBy { speakerGroupLabel(it) }
                Column(
                    modifier = Modifier.verticalScroll(settingsScroll),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    uiState.message?.takeIf { it.isNotBlank() }?.let { statusMessage ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFFF8FAFC),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("현재 음성 설정", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                Text(statusMessage, color = Color(0xFF334155))
                                Text(
                                    text = when {
                                        uiState.ttsEnginePreference != TtsEnginePreference.ON_DEVICE -> "시스템 음성 엔진을 사용 중이에요."
                                        selectedModel?.id?.contains("piper", ignoreCase = true) == true -> "Piper 다화자 모델을 사용 중이에요. 화자는 preset 기반으로 선택됩니다."
                                        else -> "메타데이터가 있는 화자 모델을 사용 중이에요. 국가와 성별 기준으로 정리됩니다."
                                    },
                                    color = Color(0xFF64748B),
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFFFFFFFF),
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("자동 읽기", fontWeight = FontWeight.Bold)
                                    Text("선택 직후 바로 읽기를 시작할지 정해요.", color = Color(0xFF64748B))
                                }
                                Switch(
                                    checked = uiState.autoSpeakEnabled,
                                    onCheckedChange = onSetAutoSpeakEnabled,
                                )
                            }

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            Text("읽기 대상", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (uiState.speechTarget == SpeechTarget.WORD) {
                                    Button(onClick = { onSetSpeechTarget(SpeechTarget.WORD) }) {
                                        Text("단어")
                                    }
                                } else {
                                    OutlinedButton(onClick = { onSetSpeechTarget(SpeechTarget.WORD) }) {
                                        Text("단어")
                                    }
                                }

                                if (uiState.speechTarget == SpeechTarget.SENTENCE) {
                                    Button(onClick = { onSetSpeechTarget(SpeechTarget.SENTENCE) }) {
                                        Text("문장")
                                    }
                                } else {
                                    OutlinedButton(onClick = { onSetSpeechTarget(SpeechTarget.SENTENCE) }) {
                                        Text("문장")
                                    }
                                }
                            }

                            Text("읽기 속도 ${"%.2f".format(uiState.speechRate)}", fontWeight = FontWeight.Bold)
                            Slider(
                                value = uiState.speechRate,
                                onValueChange = onSetSpeechRate,
                                valueRange = 0.85f..1.15f,
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFFFFFFFF),
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("음성 엔진", fontWeight = FontWeight.Bold)
                            Text("시스템 음성과 온디바이스 음성 중 원하는 경로를 선택하세요.", color = Color(0xFF64748B))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val current = uiState.ttsEnginePreference
                                EngineOptionButton(
                                    label = "Google",
                                    selected = current == TtsEnginePreference.GOOGLE,
                                    onClick = { onSetTtsEnginePreference(TtsEnginePreference.GOOGLE) },
                                )
                                EngineOptionButton(
                                    label = "Samsung",
                                    selected = current == TtsEnginePreference.SAMSUNG,
                                    onClick = { onSetTtsEnginePreference(TtsEnginePreference.SAMSUNG) },
                                )
                                EngineOptionButton(
                                    label = "온디바이스",
                                    selected = current == TtsEnginePreference.ON_DEVICE,
                                    onClick = { onSetTtsEnginePreference(TtsEnginePreference.ON_DEVICE) },
                                )
                            }

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            Text("온디바이스 모델", fontWeight = FontWeight.Bold)
                            if (displayedModels.isEmpty()) {
                                Text("사용 가능한 온디바이스 음성이 없습니다.", color = Color(0xFF64748B))
                            } else {
                                Row(
                                    modifier = Modifier.horizontalScroll(modelPickerScroll),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    displayedModels.forEach { model ->
                                        VoiceModelCard(
                                            model = model,
                                            selected = model.id == uiState.selectedLocalModelId,
                                            onClick = { onSetLocalModel(model.id) },
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFF8FAFC),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = selectedModelSummary(uiState),
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = selectedSpeakerGuidance(selectedModel),
                                        color = Color(0xFF64748B),
                                    )
                                    Text(
                                        text = if (uiState.bundledModelReady) {
                                            if (uiState.effectiveLocalModelEnabled) "현재 온디바이스 경로가 활성화되어 있어요."
                                            else "모델은 준비되었지만 현재는 시스템 음성을 사용 중이에요."
                                        } else {
                                            "모델 파일이 아직 준비되지 않아 시스템 음성으로 동작해요."
                                        },
                                        color = if (uiState.bundledModelReady) DictionaryReadyColor else DictionaryMissingColor,
                                    )
                                    if (uiState.ttsEnginePreference == TtsEnginePreference.ON_DEVICE && uiState.localRuntimeChecking) {
                                        Text("온디바이스 음성을 확인하는 중이에요. 잠시만 기다려주세요.", color = Color(0xFFB45309))
                                    } else if (uiState.ttsEnginePreference == TtsEnginePreference.ON_DEVICE && !uiState.localRuntimeReady) {
                                        Text("지금은 시스템 음성으로 이어집니다. 상세 오류는 아래 문구를 확인하세요.", color = DictionaryMissingColor)
                                        uiState.localRuntimeLastError?.takeIf { it.isNotBlank() }?.let { failureReason ->
                                            Text(failureReason, color = DictionaryMissingColor, fontStyle = FontStyle.Italic)
                                        }
                                    }
                                }
                            }

                            if (uiState.ttsEnginePreference == TtsEnginePreference.ON_DEVICE && uiState.availableSpeakers.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFFE2E8F0))
                                Text("화자 선택", fontWeight = FontWeight.Bold)
                                Text(selectedSpeakerSectionDescription(selectedModel), color = Color(0xFF64748B))
                                groupedSpeakers.forEach { (groupLabel, speakers) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(groupLabel, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            speakers.forEach { speaker ->
                                                SpeakerChip(
                                                    speaker = speaker,
                                                    selected = speaker.id == uiState.selectedSpeakerId,
                                                    onClick = { onSetLocalSpeaker(speaker.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text("2단어 탭 간격: ${uiState.tapSelectionWindowMs}ms")
                    Slider(
                        value = uiState.tapSelectionWindowMs.toFloat(),
                        onValueChange = { onSetTapSelectionWindowMs(it.toLong()) },
                        valueRange = 300f..3000f,
                    )

                    Text("드래그 최소 거리: ${uiState.dragSelectionMinDistancePx.toInt()}px")
                    Slider(
                        value = uiState.dragSelectionMinDistancePx,
                        onValueChange = onSetDragSelectionMinDistancePx,
                        valueRange = 4f..200f,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = dismissSettingsDialog) {
                    Text("닫기")
                }
            },
        )
    }

    if (uiState.isSavedWordsDialogVisible) {
        AlertDialog(
            onDismissRequest = onDismissSavedWordsDialog,
            title = { Text("저장된 단어") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(savedWordsScroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uiState.savedWords.isEmpty()) {
                        Text("저장된 단어가 없습니다.")
                    } else {
                        uiState.savedWords.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenSavedWord(item) },
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start,
                                    ) {
                                        Text(item.word, fontWeight = FontWeight.SemiBold)
                                        Text("저장 횟수: ${item.saveCount}")
                                        if (!item.sentence.isNullOrBlank()) {
                                            Text(
                                                text = item.sentence,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(onClick = { onDeleteSavedWord(item) }) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissSavedWordsDialog) {
                    Text("닫기")
                }
            },
        )
    }

    if (uiState.isDictionaryDialogVisible && uiState.dictionaryEntries.isNotEmpty()) {
        val entry = uiState.dictionaryEntries.first()
        val englishSenses = entry.senses.ifEmpty {
            listOf(
                DictionarySense(
                    definitionEn = entry.definitionEn,
                    definitionKo = entry.definitionKo,
                ),
            )
        }
        val koreanSenses = uiState.dictionaryEntries
            .flatMap { it.senses }
            .filter { it.definitionKo.isNotBlank() || !it.exampleKo.isNullOrBlank() }
            .distinctBy { "${it.definitionKo}|${it.exampleKo.orEmpty()}" }
            .take(6)

        AlertDialog(
            onDismissRequest = onDismissDictionaryDialog,
            title = { Text("${entry.headword} (${entry.lemma})") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(dictionaryScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("영영 사전", fontWeight = FontWeight.Bold)
                    englishSenses.take(2).forEachIndexed { idx, sense ->
                        Text("${idx + 1}. ${sense.definitionEn}")
                        if (!sense.exampleEn.isNullOrBlank()) {
                            Text("• ${sense.exampleEn}", fontStyle = FontStyle.Italic)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text("영한 사전", fontWeight = FontWeight.Bold)
                    if (!uiState.isKoreanDefinitionVisible) {
                        Button(onClick = onRevealKoreanDefinition) {
                            Text("한글 뜻 보기")
                        }
                    } else {
                        if (koreanSenses.isEmpty()) {
                            Text("영한 사전 데이터가 아직 없습니다.")
                        } else {
                            koreanSenses.forEachIndexed { idx, sense ->
                                Text("${idx + 1}. ${sense.definitionKo.ifBlank { "(뜻 정보 없음)" }}")
                                if (!sense.exampleKo.isNullOrBlank()) {
                                    Text("• ${sense.exampleKo}", fontStyle = FontStyle.Italic)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissDictionaryDialog) {
                    Text("닫기")
                }
            },
        )
    }
}

@Composable
private fun BoxScope.SnapshotSearchControlSubtree(
    isSnapshotMode: Boolean,
    isSnapshotProcessing: Boolean,
    isGalleryPicking: Boolean,
    cameraPermissionGranted: Boolean,
    onOpenGallery: () -> Unit,
    onCaptureSnapshot: () -> Unit,
    onReturnToLiveMode: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(104.dp)
            .background(CameraControlStripColor),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x66E2E8F0)),
        )
    }

    if (isSnapshotMode) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cameraPermissionGranted) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color(0xE2F8FAFC), CircleShape)
                        .border(width = 2.dp, color = Color(0xFF1E293B), shape = CircleShape)
                        .clickable(
                            enabled = !isSnapshotProcessing && !isGalleryPicking,
                            role = Role.Button,
                            onClick = onCaptureSnapshot,
                        )
                        .semantics { contentDescription = "다시 촬영 검색" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(42.dp)) {
                        drawCircle(
                            color = Color(0xFF1E293B),
                            radius = size.minDimension * 0.5f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f),
                        )
                        drawCircle(
                            color = Color(0xFF334155),
                            radius = size.minDimension * 0.28f,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xDD374151), CircleShape)
                    .border(width = 2.dp, color = Color(0xFFF1F5F9), shape = CircleShape)
                    .clickable(
                        enabled = !isSnapshotProcessing,
                        role = Role.Button,
                        onClick = onReturnToLiveMode,
                    )
                    .semantics { contentDescription = "실시간 복귀" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val stroke = size.minDimension * 0.12f
                    drawLine(
                        color = Color.White,
                        start = Offset(stroke, stroke),
                        end = Offset(size.width - stroke, size.height - stroke),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width - stroke, stroke),
                        end = Offset(stroke, size.height - stroke),
                        strokeWidth = stroke,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color(0xE21F2937), CircleShape)
                    .border(width = 2.dp, color = Color(0xFFCBD5E1), shape = CircleShape)
                    .clickable(
                        enabled = !isSnapshotProcessing && !isGalleryPicking,
                        role = Role.Button,
                        onClick = onOpenGallery,
                    )
                    .semantics { contentDescription = "갤러리 분석" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(34.dp)) {
                    val stroke = size.minDimension * 0.1f
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(stroke, stroke * 1.2f),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2.2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                    )
                    drawCircle(
                        color = Color(0xFF94A3B8),
                        radius = size.minDimension * 0.11f,
                        center = Offset(size.width * 0.7f, size.height * 0.35f),
                    )
                    val baseY = size.height * 0.72f
                    drawLine(
                        color = Color(0xFF94A3B8),
                        start = Offset(size.width * 0.2f, baseY),
                        end = Offset(size.width * 0.43f, size.height * 0.5f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Color(0xFF94A3B8),
                        start = Offset(size.width * 0.43f, size.height * 0.5f),
                        end = Offset(size.width * 0.57f, size.height * 0.63f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = Color(0xFF94A3B8),
                        start = Offset(size.width * 0.57f, size.height * 0.63f),
                        end = Offset(size.width * 0.8f, size.height * 0.42f),
                        strokeWidth = stroke,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(
                        color = if (cameraPermissionGranted) Color(0xE2F8FAFC) else Color(0xAA94A3B8),
                        shape = CircleShape,
                    )
                    .border(width = 2.dp, color = Color(0xFF1E293B), shape = CircleShape)
                    .clickable(
                        enabled = cameraPermissionGranted && !isSnapshotProcessing && !isGalleryPicking,
                        role = Role.Button,
                        onClick = onCaptureSnapshot,
                    )
                    .semantics {
                        contentDescription = if (cameraPermissionGranted) {
                            "촬영 검색"
                        } else {
                            "카메라 권한 필요"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(48.dp)) {
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = size.minDimension * 0.5f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f),
                    )
                    drawCircle(
                        color = Color(0xFF334155),
                        radius = size.minDimension * 0.28f,
                    )
                }
            }
        }
    }
}

private fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return runCatching {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxDimension = 2200
                val sourceWidth = info.size.width
                val sourceHeight = info.size.height
                val sourceMax = maxOf(sourceWidth, sourceHeight)
                if (sourceMax > maxDimension) {
                    val scale = sourceMax.toFloat() / maxDimension.toFloat()
                    val targetWidth = (sourceWidth / scale).toInt().coerceAtLeast(1)
                    val targetHeight = (sourceHeight / scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
                decoder.isMutableRequired = false
            }.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(resolver, uri).copy(Bitmap.Config.ARGB_8888, false)
        }
    }.getOrNull()
}

private data class ViewBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private data class ViewMapping(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

private fun createViewMapping(
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
): ViewMapping {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return ViewMapping(scale = 1f, offsetX = 0f, offsetY = 0f)
    }
    val srcW = sourceWidth.toFloat()
    val srcH = sourceHeight.toFloat()
    val scale = minOf(viewWidth / srcW, viewHeight / srcH)
    val drawW = srcW * scale
    val drawH = srcH * scale
    val offsetX = (viewWidth - drawW) / 2f
    val offsetY = (viewHeight - drawH) * PreviewVerticalBias
    return ViewMapping(scale = scale, offsetX = offsetX, offsetY = offsetY)
}

private fun mapBoxToView(
    box: BoundingBox,
    mapping: ViewMapping,
): ViewBox {
    return ViewBox(
        left = mapping.offsetX + box.left * mapping.scale,
        top = mapping.offsetY + box.top * mapping.scale,
        right = mapping.offsetX + box.right * mapping.scale,
        bottom = mapping.offsetY + box.bottom * mapping.scale,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOverlayBoxes(
    overlayModel: ReaderOverlayModel,
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
) {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return
    }
    val mapping = createViewMapping(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )

    overlayModel.highlightedLines.forEach { lineBox ->
        val mappedLine = mapBoxToView(lineBox, mapping)
        drawRect(
            color = SelectedSentenceColor,
            topLeft = Offset(mappedLine.left, mappedLine.top),
            size = androidx.compose.ui.geometry.Size(
                mappedLine.right - mappedLine.left,
                mappedLine.bottom - mappedLine.top,
            ),
            alpha = 0.28f,
        )
    }

    overlayModel.words.forEach { wordBox ->
        val mapped = mapBoxToView(wordBox.box, mapping)
        drawRect(
            color = if (wordBox.isSelected) SelectedWordColor else DetectedWordColor,
            topLeft = Offset(mapped.left, mapped.top),
            size = androidx.compose.ui.geometry.Size(
                mapped.right - mapped.left,
                mapped.bottom - mapped.top,
            ),
            alpha = if (wordBox.isSelected) 0.56f else 0.24f,
        )
    }
}

private fun mapBoxToView(
    box: BoundingBox,
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
): ViewBox {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return ViewBox(
            left = box.left.toFloat(),
            top = box.top.toFloat(),
            right = box.right.toFloat(),
            bottom = box.bottom.toFloat(),
        )
    }

    val srcW = sourceWidth.toFloat()
    val srcH = sourceHeight.toFloat()
    val scale = minOf(viewWidth / srcW, viewHeight / srcH)
    val drawW = srcW * scale
    val drawH = srcH * scale
    val offsetX = (viewWidth - drawW) / 2f
    val offsetY = (viewHeight - drawH) * PreviewVerticalBias

    return ViewBox(
        left = offsetX + box.left * scale,
        top = offsetY + box.top * scale,
        right = offsetX + box.right * scale,
        bottom = offsetY + box.bottom * scale,
    )
}

private fun mapViewToSource(
    x: Float,
    y: Float,
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
): Pair<Float, Float> {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return x to y
    }

    val srcW = sourceWidth.toFloat()
    val srcH = sourceHeight.toFloat()
    val scale = minOf(viewWidth / srcW, viewHeight / srcH)
    val drawW = srcW * scale
    val drawH = srcH * scale
    val offsetX = (viewWidth - drawW) / 2f
    val offsetY = (viewHeight - drawH) * PreviewVerticalBias

    val srcX = ((x - offsetX) / scale).coerceIn(0f, srcW)
    val srcY = ((y - offsetY) / scale).coerceIn(0f, srcH)
    return srcX to srcY
}

private fun mapTransformedViewToSource(
    x: Float,
    y: Float,
    viewWidth: Float,
    viewHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    transform: ViewportTransformState,
): Pair<Float, Float> {
    val scale = transform.scale.coerceAtLeast(1f)
    val baseX = ((x - transform.offsetX) / scale).coerceIn(0f, viewWidth)
    val baseY = ((y - transform.offsetY) / scale).coerceIn(0f, viewHeight)
    return mapViewToSource(
        x = baseX,
        y = baseY,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
}

private fun speakerGroupLabel(speaker: LocalSpeakerProfile): String {
    if (speaker.accentLabel.startsWith("Preset")) {
        return speaker.accentLabel
    }
    val genderLabel = when (speaker.gender) {
        SpeakerGender.FEMALE -> "여성"
        SpeakerGender.MALE -> "남성"
        SpeakerGender.UNKNOWN -> "미분류"
    }
    val accentLabel = speaker.accentLabel.ifBlank { "기타" }
    return "$accentLabel / $genderLabel"
}

@Composable
private fun EngineOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun VoiceModelCard(
    model: com.example.bookhelper.tts.BundledTtsModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Color(0xFF2563EB) else Color(0xFFE2E8F0)
    val backgroundColor = if (selected) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)
    Surface(
        modifier = Modifier.width(168.dp),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        shadowElevation = if (selected) 10.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(model.shortLabel, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(model.displayName, color = Color(0xFF475569), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = when {
                    model.id.contains("piper", ignoreCase = true) -> "추천 · 다화자 preset"
                    else -> "기존 음색"
                },
                color = if (model.id.contains("piper", ignoreCase = true)) Color(0xFF1D4ED8) else Color(0xFF64748B),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SpeakerChip(
    speaker: LocalSpeakerProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = if (speaker.accentLabel.startsWith("Preset")) {
        "#${speaker.id}"
    } else {
        speaker.displayLabel
    }
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

private fun selectedModelSummary(uiState: ReaderUiState): String {
    val modelName = uiState.bundledModelName.ifBlank { "모델 정보 없음" }
    return if (uiState.selectedLocalModelId.contains("piper", ignoreCase = true)) {
        "현재 모델: $modelName · Piper 다화자 음성"
    } else {
        "현재 모델: $modelName"
    }
}

private fun selectedSpeakerGuidance(model: com.example.bookhelper.tts.BundledTtsModel?): String {
    return when {
        model == null -> "선택한 모델에 맞는 화자 정보를 불러옵니다."
        model.id.contains("piper", ignoreCase = true) -> "Piper는 모델이 제공하는 정확한 화자 ID preset으로 선택합니다. 국가/성별 메타데이터는 제공되지 않습니다."
        else -> "이 모델은 국가와 성별 기준으로 화자를 나눠서 고를 수 있습니다."
    }
}

private fun selectedSpeakerSectionDescription(model: com.example.bookhelper.tts.BundledTtsModel?): String {
    return when {
        model == null -> "사용 가능한 화자를 불러오는 중입니다."
        model.id.contains("piper", ignoreCase = true) -> "Piper는 speaker preset 단위로 선택합니다. 같은 그룹 안에서도 발음 성향이 달라질 수 있습니다."
        else -> "국가와 성별 흐름을 기준으로 원하는 화자를 고르세요."
    }
}
