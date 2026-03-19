package com.example.bookhelper.perf

class PageHashComparator(
    private val maxDistance: Int = 6,
) : SamePageDetector {
    override fun isSamePage(previousHash: Long?, currentHash: Long): Boolean {
        if (previousHash == null) {
            return false
        }
        return hammingDistance(previousHash, currentHash) <= maxDistance
    }

    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }
}
