package com.example.bookhelper.perf

class BlurScoreCalculator(
    private val threshold: Double = 100.0,
) : BlurGate {
    override fun shouldSkip(score: Double): Boolean {
        return score < threshold
    }
}
