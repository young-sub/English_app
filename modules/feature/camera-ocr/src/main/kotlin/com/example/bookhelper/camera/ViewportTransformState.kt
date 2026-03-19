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
    ): ViewportTransformState {
        val nextScale = (current.scale * zoomChange).coerceIn(minScale, maxScale)
        val normalizedPanX = if (nextScale <= minScale) 0f else current.offsetX + panX
        val normalizedPanY = if (nextScale <= minScale) 0f else current.offsetY + panY
        return current.copy(
            scale = nextScale,
            offsetX = normalizedPanX,
            offsetY = normalizedPanY,
        )
    }

    fun reset(): ViewportTransformState = ViewportTransformState.Identity
}
