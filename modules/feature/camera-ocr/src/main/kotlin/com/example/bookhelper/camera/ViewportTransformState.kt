package com.example.bookhelper.camera

data class ViewportTransformState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    companion object {
        val Identity = ViewportTransformState()
    }
}

class ViewportTransformReducer(
    private val minScale: Float = 1f,
    private val maxScale: Float = 4f,
) {
    fun applyGesture(
        current: ViewportTransformState,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        focalX: Float? = null,
        focalY: Float? = null,
    ): ViewportTransformState {
        val currentScale = current.scale.coerceAtLeast(minScale)
        val nextScale = (current.scale * zoomChange).coerceIn(minScale, maxScale)
        if (nextScale <= minScale) {
            return ViewportTransformState.Identity
        }

        val scaleFactor = nextScale / currentScale
        val focalAdjustedOffsetX = if (focalX != null) {
            focalX - ((focalX - current.offsetX) * scaleFactor)
        } else {
            current.offsetX
        }
        val focalAdjustedOffsetY = if (focalY != null) {
            focalY - ((focalY - current.offsetY) * scaleFactor)
        } else {
            current.offsetY
        }
        return current.copy(
            scale = nextScale,
            offsetX = focalAdjustedOffsetX + panX,
            offsetY = focalAdjustedOffsetY + panY,
        )
    }

    fun reset(): ViewportTransformState = ViewportTransformState.Identity
}
