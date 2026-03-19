package com.example.bookhelper.perf

import com.example.bookhelper.contracts.OcrPage
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerformanceCoreTest {
    @Test
    fun pageHashComparatorUsesThreshold() {
        val comparator = PageHashComparator(maxDistance = 1)
        assertTrue(comparator.isSamePage(0b1010, 0b1011))
        assertFalse(comparator.isSamePage(0b1010, 0b0101))
    }

    @Test
    fun blurCalculatorSkipsLowScores() {
        val calculator = BlurScoreCalculator(threshold = 100.0)
        assertTrue(calculator.shouldSkip(90.0))
        assertFalse(calculator.shouldSkip(120.0))
    }

    @Test
    fun ocrCacheStoresAndReads() {
        val cache = OcrCache()
        val page = OcrPage.EMPTY
        cache.put(123L, page)
        val loaded = cache.get(123L)
        assertNotNull(loaded)
        assertEquals(page, loaded)
    }

    @Test
    fun ocrCacheRespectsMaxEntriesPolicy() {
        val cache = OcrCache(policy = OcrCachePolicy(maxEntries = 2))
        cache.put(1L, OcrPage.EMPTY)
        cache.put(2L, OcrPage.EMPTY)
        cache.put(3L, OcrPage.EMPTY)

        assertEquals(null, cache.get(1L))
        assertNotNull(cache.get(2L))
        assertNotNull(cache.get(3L))
    }

    @Test
    fun macrobenchmarkScenariosAreDefined() {
        val scenarios = MacrobenchmarkScenario.entries
        assertTrue(scenarios.isNotEmpty())
        assertTrue(scenarios.any { it.id == "dictionary_tap_lookup" })
    }

    @Test
    fun frameLumaHasherIsStableForEquivalentFrames() {
        val hasher = FrameLumaHasher()
        val frame = byteArrayOf(
            10, 10, 20, 20,
            10, 10, 20, 20,
            200.toByte(), 200.toByte(), 220.toByte(), 220.toByte(),
            200.toByte(), 200.toByte(), 220.toByte(), 220.toByte(),
        )

        val first = hasher.fromLumaPlane(ByteBuffer.wrap(frame), width = 4, height = 4, rowStride = 4, pixelStride = 1)
        val second = hasher.fromLumaPlane(ByteBuffer.wrap(frame.copyOf()), width = 4, height = 4, rowStride = 4, pixelStride = 1)

        assertEquals(first, second)
    }

    @Test
    fun frameLumaHasherSeparatesDifferentLayouts() {
        val hasher = FrameLumaHasher()
        val comparator = PageHashComparator(maxDistance = 0)
        val leftHeavy = byteArrayOf(
            220.toByte(), 220.toByte(), 20, 20,
            220.toByte(), 220.toByte(), 20, 20,
            220.toByte(), 220.toByte(), 20, 20,
            220.toByte(), 220.toByte(), 20, 20,
        )
        val rightHeavy = byteArrayOf(
            20, 20, 220.toByte(), 220.toByte(),
            20, 20, 220.toByte(), 220.toByte(),
            20, 20, 220.toByte(), 220.toByte(),
            20, 20, 220.toByte(), 220.toByte(),
        )

        val first = hasher.fromLumaPlane(ByteBuffer.wrap(leftHeavy), width = 4, height = 4, rowStride = 4, pixelStride = 1)
        val second = hasher.fromLumaPlane(ByteBuffer.wrap(rightHeavy), width = 4, height = 4, rowStride = 4, pixelStride = 1)

        assertFalse(comparator.isSamePage(first, second))
    }
}
