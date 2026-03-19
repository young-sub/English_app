package com.example.bookhelper.camera

import com.example.bookhelper.contracts.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraCoreTest {
    @Test
    fun frameGateRespectsInterval() {
        val gate = FrameGate(minIntervalMs = 300)
        assertTrue(gate.shouldProcess(1000))
        assertFalse(gate.shouldProcess(1100))
        assertTrue(gate.shouldProcess(1400))
    }

    @Test
    fun roiMapperScalesToFrameSpace() {
        val mapper = RoiMapper()
        val roi = mapper.mapToFrame(
            previewWidth = 100,
            previewHeight = 200,
            frameWidth = 1000,
            frameHeight = 2000,
            previewRoi = BoundingBox(10, 20, 50, 100),
        )
        assertEquals(BoundingBox(100, 200, 500, 1000), roi)
    }

    @Test
    fun viewportTransformReducerAppliesZoomAndPan() {
        val reducer = ViewportTransformReducer(minScale = 1f, maxScale = 4f)
        val transformed = reducer.applyGesture(
            current = ViewportTransformState.Identity,
            zoomChange = 1.5f,
            panX = 24f,
            panY = -12f,
        )
        assertEquals(1.5f, transformed.scale)
        assertEquals(24f, transformed.offsetX)
        assertEquals(-12f, transformed.offsetY)
    }

    @Test
    fun viewportTransformReducerResetsPanWhenScaleReturnsToBase() {
        val reducer = ViewportTransformReducer(minScale = 1f, maxScale = 4f)
        val zoomed = reducer.applyGesture(
            current = ViewportTransformState.Identity,
            zoomChange = 2f,
            panX = 40f,
            panY = 20f,
        )

        val normalized = reducer.applyGesture(
            current = zoomed,
            zoomChange = 0.5f,
            panX = 0f,
            panY = 0f,
        )

        assertEquals(1f, normalized.scale)
        assertEquals(0f, normalized.offsetX)
        assertEquals(0f, normalized.offsetY)
    }
}
