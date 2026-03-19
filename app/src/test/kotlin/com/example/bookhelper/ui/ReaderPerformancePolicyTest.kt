package com.example.bookhelper.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPerformancePolicyTest {
    @Test
    fun liveAnalysisStopsWhileSettingsDialogIsVisible() {
        assertTrue(shouldRunLiveAnalysis(cameraPermissionGranted = true, settingsDialogVisible = false))
        assertFalse(shouldRunLiveAnalysis(cameraPermissionGranted = true, settingsDialogVisible = true))
        assertFalse(shouldRunLiveAnalysis(cameraPermissionGranted = false, settingsDialogVisible = false))
    }

    @Test
    fun liveOcrUpdatesAreIgnoredWhileSettingsDialogIsVisible() {
        assertTrue(shouldAcceptLiveOcrResult(settingsDialogVisible = false, snapshotMode = false))
        assertFalse(shouldAcceptLiveOcrResult(settingsDialogVisible = true, snapshotMode = false))
        assertFalse(shouldAcceptLiveOcrResult(settingsDialogVisible = false, snapshotMode = true))
    }
}
