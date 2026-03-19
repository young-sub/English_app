package com.example.bookhelper.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.ocr.OcrFrameResult
import com.example.bookhelper.ocr.VisionMapper
import com.example.bookhelper.perf.FrameLumaHasher
import com.example.bookhelper.perf.OcrCache
import com.example.bookhelper.perf.OcrPageCache
import com.example.bookhelper.perf.PageHashComparator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BookPageAnalyzer(
    private val frameGate: FrameGate,
    private val pageHashComparator: PageHashComparator,
    private val onOcrResult: (OcrFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile
    private var isProcessing = false

    private var previousHash: Long? = null
    private val frameHasher = FrameLumaHasher()
    private val ocrCache: OcrPageCache = OcrCache()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (isProcessing || !frameGate.shouldProcess(now)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val plane = imageProxy.planes.firstOrNull()
        if (plane == null) {
            imageProxy.close()
            return
        }

        val hash = frameHasher.fromLumaPlane(
            buffer = plane.buffer,
            width = imageProxy.width,
            height = imageProxy.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
        if (pageHashComparator.isSamePage(previousHash, hash)) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val sourceWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val sourceHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

        ocrCache.get(hash)?.let { cachedPage ->
            previousHash = hash
            onOcrResult(
                OcrFrameResult(
                    page = cachedPage,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                ),
            )
            imageProxy.close()
            return
        }

        isProcessing = true

        runCatching {
            val image = InputImage.fromMediaImage(mediaImage, rotation)
            recognizer.process(image)
                .addOnSuccessListener(callbackExecutor) { text ->
                    val page = VisionMapper.toOcrPage(text)
                    val refinedPage = BookPageOcrPostProcessor.refine(page, sourceWidth, sourceHeight)
                    ocrCache.put(hash, refinedPage)
                    previousHash = hash
                    onOcrResult(
                        OcrFrameResult(
                            page = refinedPage,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                        ),
                    )
                }
                .addOnFailureListener(callbackExecutor) {
                    onOcrResult(
                        OcrFrameResult(
                            page = OcrPage.EMPTY,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                        ),
                    )
                }
                .addOnCompleteListener(callbackExecutor) {
                    isProcessing = false
                    imageProxy.close()
                }
        }.onFailure {
            isProcessing = false
            imageProxy.close()
            onOcrResult(
                OcrFrameResult(
                    page = OcrPage.EMPTY,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                ),
            )
        }
    }

    fun close() {
        callbackExecutor.shutdownNow()
        recognizer.close()
    }

    fun resetFrameDeduplication() {
        previousHash = null
    }
}
