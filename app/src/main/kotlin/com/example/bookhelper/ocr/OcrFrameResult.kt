package com.example.bookhelper.ocr

import com.example.bookhelper.contracts.OcrPage

data class OcrFrameResult(
    val page: OcrPage,
    val sourceWidth: Int,
    val sourceHeight: Int,
)
