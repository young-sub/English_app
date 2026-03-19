package com.example.bookhelper.camera

import android.graphics.Bitmap
import com.example.bookhelper.contracts.OcrPage
import com.example.bookhelper.ocr.OcrFrameResult
import com.example.bookhelper.ocr.VisionMapper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StillImageAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun analyze(bitmap: Bitmap, onResult: (OcrFrameResult) -> Unit) {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                val page = VisionMapper.toOcrPage(text)
                onResult(
                    OcrFrameResult(
                        page = BookPageOcrPostProcessor.refine(page, sourceWidth, sourceHeight),
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                    ),
                )
            }
            .addOnFailureListener(callbackExecutor) {
                onResult(
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
}
