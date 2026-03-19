package com.example.bookhelper.camera

import com.example.bookhelper.contracts.BoundingBox

class RoiMapper {
    fun mapToFrame(
        previewWidth: Int,
        previewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        previewRoi: BoundingBox,
    ): BoundingBox {
        require(previewWidth > 0 && previewHeight > 0)
        require(frameWidth > 0 && frameHeight > 0)

        val scaleX = frameWidth.toFloat() / previewWidth.toFloat()
        val scaleY = frameHeight.toFloat() / previewHeight.toFloat()

        return BoundingBox(
            left = (previewRoi.left * scaleX).toInt(),
            top = (previewRoi.top * scaleY).toInt(),
            right = (previewRoi.right * scaleX).toInt(),
            bottom = (previewRoi.bottom * scaleY).toInt(),
        )
    }
}
