package com.example.bookhelper.camera

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class BookPageStillImagePreprocessor {
    fun preprocess(bitmap: Bitmap): Bitmap {
        if (!ensureOpenCvReady()) {
            return bitmap
        }

        return runCatching {
            preprocessInternal(bitmap)
        }.onFailure {
            Log.w(TAG, "Still image preprocessing failed. Falling back to original bitmap.", it)
        }.getOrDefault(bitmap)
    }

    private fun preprocessInternal(bitmap: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val warped = detectAndWarpPage(rgba) ?: rgba.clone()
        val grayscale = Mat()
        val claheInput = Mat()
        val claheOutput = Mat()
        val binary = Mat()

        val colorConversion = if (warped.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY
        Imgproc.cvtColor(warped, grayscale, colorConversion)
        Imgproc.GaussianBlur(grayscale, claheInput, Size(3.0, 3.0), 0.0)

        val clahe = Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, Size(CLAHE_TILE_GRID, CLAHE_TILE_GRID))
        clahe.apply(claheInput, claheOutput)

        Imgproc.adaptiveThreshold(
            claheOutput,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            ADAPTIVE_THRESHOLD_BLOCK_SIZE,
            ADAPTIVE_THRESHOLD_C,
        )

        val output = if (shouldUseBinaryOutput(claheOutput)) binary else claheOutput
        val outputBitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
        val outputRgba = Mat()
        Imgproc.cvtColor(output, outputRgba, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(outputRgba, outputBitmap)
        return outputBitmap
    }

    private fun detectAndWarpPage(source: Mat): Mat? {
        val originalWidth = source.width().toDouble()
        val originalHeight = source.height().toDouble()
        val scale = (CONTOUR_DETECTION_MAX_DIMENSION / maxOf(originalWidth, originalHeight)).coerceAtMost(1.0)

        val resized = Mat()
        val resizedSize = Size(originalWidth * scale, originalHeight * scale)
        Imgproc.resize(source, resized, resizedSize)

        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val channelsConversion = if (resized.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY
        Imgproc.cvtColor(resized, gray, channelsConversion)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blurred, edges, 60.0, 180.0)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = resized.width() * resized.height() * MIN_PAGE_AREA_RATIO

        val bestQuad = contours
            .asSequence()
            .mapNotNull { contour -> contour.toQuadrilateralOrNull(minArea) }
            .maxByOrNull { quad -> Imgproc.contourArea(MatOfPoint(*quad.toTypedArray())) }
            ?: return null

        val ordered = bestQuad
            .map { point -> Point(point.x / scale, point.y / scale) }
            .let(::orderCorners)

        val targetWidth = maxOf(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2]))
            .toInt()
            .coerceAtLeast(1)
        val targetHeight = maxOf(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
            .toInt()
            .coerceAtLeast(1)

        val destination = listOf(
            Point(0.0, 0.0),
            Point(targetWidth - 1.0, 0.0),
            Point(targetWidth - 1.0, targetHeight - 1.0),
            Point(0.0, targetHeight - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*ordered.toTypedArray()),
            MatOfPoint2f(*destination.toTypedArray()),
        )
        val warped = Mat(targetHeight, targetWidth, CvType.CV_8UC4)
        Imgproc.warpPerspective(source, warped, transform, Size(targetWidth.toDouble(), targetHeight.toDouble()))

        return trimImageBorder(warped)
    }

    private fun MatOfPoint.toQuadrilateralOrNull(minArea: Double): List<Point>? {
        val area = Imgproc.contourArea(this)
        if (area < minArea) {
            return null
        }

        val contour2f = MatOfPoint2f(*toArray())
        val perimeter = Imgproc.arcLength(contour2f, true)
        val approximation = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approximation, perimeter * 0.02, true)
        if (approximation.total() != 4L || !Imgproc.isContourConvex(MatOfPoint(*approximation.toArray()))) {
            return null
        }
        return approximation.toList()
    }

    private fun orderCorners(points: List<Point>): List<Point> {
        require(points.size == 4) { "Exactly four points are required" }
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }
        val topLeft = points[sums.indexOf(sums.min())]
        val bottomRight = points[sums.indexOf(sums.max())]
        val topRight = points[diffs.indexOf(diffs.max())]
        val bottomLeft = points[diffs.indexOf(diffs.min())]
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(first: Point, second: Point): Double {
        return hypot(first.x - second.x, first.y - second.y)
    }

    private fun shouldUseBinaryOutput(grayscale: Mat): Boolean {
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        org.opencv.core.Core.meanStdDev(grayscale, mean, stdDev)
        val contrast = stdDev.toArray().firstOrNull() ?: return false
        return contrast < LOW_CONTRAST_STDDEV_THRESHOLD
    }

    private fun trimImageBorder(source: Mat): Mat {
        val insetX = (source.width() * WARP_BORDER_TRIM_RATIO).toInt().coerceAtLeast(0)
        val insetY = (source.height() * WARP_BORDER_TRIM_RATIO).toInt().coerceAtLeast(0)
        val rect = Rect(
            insetX,
            insetY,
            (source.width() - insetX * 2).coerceAtLeast(1),
            (source.height() - insetY * 2).coerceAtLeast(1),
        )
        return Mat(source, rect).clone()
    }

    private fun ensureOpenCvReady(): Boolean {
        if (openCvReady) {
            return true
        }
        synchronized(lock) {
            if (!openCvReady) {
                openCvReady = OpenCVLoader.initLocal()
                if (!openCvReady) {
                    Log.e(TAG, "OpenCV initialization failed for still image preprocessing")
                }
            }
        }
        return openCvReady
    }

    private companion object {
        private const val TAG = "BookPageStillImagePreprocessor"
        private const val CONTOUR_DETECTION_MAX_DIMENSION = 1280.0
        private const val MIN_PAGE_AREA_RATIO = 0.18
        private const val CLAHE_CLIP_LIMIT = 2.0
        private const val CLAHE_TILE_GRID = 8.0
        private const val ADAPTIVE_THRESHOLD_BLOCK_SIZE = 31
        private const val ADAPTIVE_THRESHOLD_C = 11.0
        private const val LOW_CONTRAST_STDDEV_THRESHOLD = 52.0
        private const val WARP_BORDER_TRIM_RATIO = 0.01

        private val lock = Any()
        @Volatile
        private var openCvReady: Boolean = false
    }
}
