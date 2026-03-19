package com.example.bookhelper.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraBinder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun bind(previewView: PreviewView, analyzer: ImageAnalysis.Analyzer) {
        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.getSurfaceProvider())
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, analyzer)
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        if (!cameraProviderFuture.isDone) {
            return
        }
        runCatching {
            cameraProviderFuture.get().unbindAll()
        }
    }

    fun release() {
        unbind()
        analysisExecutor.shutdownNow()
    }
}
