package com.example.bookhelper.perf

import java.nio.ByteBuffer

class FrameLumaHasher(
    private val sampleColumns: Int = 8,
    private val sampleRows: Int = 8,
) {
    fun fromLumaPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Long {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) {
            return 0L
        }

        val source = buffer.duplicate()
        if (!source.hasRemaining()) {
            return 0L
        }

        val totalSamples = sampleColumns * sampleRows
        if (totalSamples <= 0) {
            return 0L
        }

        val maxIndex = source.limit() - 1
        if (maxIndex < 0) {
            return 0L
        }

        val samples = IntArray(totalSamples)
        var writeIndex = 0
        for (row in 0 until sampleRows) {
            val y = sampleCoordinate(row, sampleRows, height)
            for (column in 0 until sampleColumns) {
                val x = sampleCoordinate(column, sampleColumns, width)
                val bufferIndex = (y * rowStride + x * pixelStride).coerceIn(0, maxIndex)
                samples[writeIndex] = source.get(bufferIndex).toInt() and 0xFF
                writeIndex += 1
            }
        }

        val threshold = samples.average()
        var hash = 0L
        samples.forEachIndexed { index, value ->
            if (value >= threshold) {
                hash = hash or (1L shl index)
            }
        }
        return hash
    }

    private fun sampleCoordinate(index: Int, divisions: Int, size: Int): Int {
        if (divisions <= 1 || size <= 1) {
            return 0
        }
        return ((index.toLong() * (size - 1)) / (divisions - 1)).toInt()
    }
}
