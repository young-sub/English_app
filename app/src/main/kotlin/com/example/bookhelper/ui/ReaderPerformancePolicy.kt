package com.example.bookhelper.ui

internal fun shouldRunLiveAnalysis(
    cameraPermissionGranted: Boolean,
    settingsDialogVisible: Boolean,
): Boolean {
    return cameraPermissionGranted && !settingsDialogVisible
}

internal fun shouldAcceptLiveOcrResult(
    settingsDialogVisible: Boolean,
    snapshotMode: Boolean,
): Boolean {
    return !settingsDialogVisible && !snapshotMode
}
