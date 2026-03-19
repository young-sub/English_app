package com.example.bookhelper.perf

import com.example.bookhelper.contracts.OcrPage

interface SamePageDetector {
    fun isSamePage(previousHash: Long?, currentHash: Long): Boolean
}

interface BlurGate {
    fun shouldSkip(score: Double): Boolean
}

data class OcrCachePolicy(
    val maxEntries: Int = 8,
)

interface OcrPageCache {
    fun put(hash: Long, page: OcrPage)
    fun get(hash: Long): OcrPage?
    fun clear()
}

enum class MacrobenchmarkScenario(
    val id: String,
    val description: String,
) {
    LIVE_CAMERA_STABLE_PAGE(
        id = "live_camera_stable_page",
        description = "Live camera preview with mostly static text to validate same-page skipping.",
    ),
    LIVE_CAMERA_PAGE_TURN(
        id = "live_camera_page_turn",
        description = "Live camera with page turn events to validate cache invalidation and new OCR runs.",
    ),
    SNAPSHOT_CAPTURE_ANALYZE(
        id = "snapshot_capture_analyze",
        description = "Capture snapshot and run one-shot OCR analysis with overlay rendering.",
    ),
    GALLERY_IMPORT_ANALYZE(
        id = "gallery_import_analyze",
        description = "Import an image from gallery and complete OCR + dictionary pipeline.",
    ),
    DICTIONARY_TAP_LOOKUP(
        id = "dictionary_tap_lookup",
        description = "Tap-to-select word and open dictionary result dialog.",
    ),
    DRAG_SELECTION_TTS(
        id = "drag_selection_tts",
        description = "Drag-select sentence region and trigger text-to-speech playback.",
    ),
}
