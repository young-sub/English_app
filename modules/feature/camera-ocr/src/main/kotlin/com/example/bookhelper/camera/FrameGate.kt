package com.example.bookhelper.camera

class FrameGate(
    private val minIntervalMs: Long = 300L,
) {
    private var lastAcceptedAtMs: Long = 0L

    fun shouldProcess(nowMs: Long): Boolean {
        if (nowMs - lastAcceptedAtMs < minIntervalMs) {
            return false
        }
        lastAcceptedAtMs = nowMs
        return true
    }
}
