package com.example.bookhelper.tts

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class HuggingFaceModelDownloader(context: Context) {
    private val modelDir: File = File(context.applicationContext.filesDir, "tts-models")

    fun downloadFromUrl(
        sourceUrl: String,
        onProgress: (Int) -> Unit,
    ): Result<String> {
        val normalized = sourceUrl.trim()
        if (!HuggingFaceModelReference.isHuggingFaceUrl(normalized)) {
            return Result.failure(IllegalArgumentException("Only huggingface.co URL is supported."))
        }

        return runCatching {
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val url = URL(normalized)
            val fileName = url.path.substringAfterLast('/').ifBlank { "model.bin" }
            val destination = File(modelDir, fileName)
            val temp = File(modelDir, "$fileName.part")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                connect()
            }
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                error("Download failed with HTTP ${connection.responseCode}")
            }

            val totalLength = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    var downloaded = 0L
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()
                        if (totalLength > 0) {
                            val progress = ((downloaded * 100L) / totalLength).toInt().coerceIn(0, 100)
                            onProgress(progress)
                        }
                        read = input.read(buffer)
                    }
                }
            }
            connection.disconnect()

            if (destination.exists()) {
                destination.delete()
            }
            temp.renameTo(destination)

            val resolvedPath = if (destination.extension.equals("zip", ignoreCase = true)) {
                val extractDir = File(modelDir, destination.nameWithoutExtension)
                unzip(destination, extractDir)
                extractDir.absolutePath
            } else {
                destination.absolutePath
            }
            onProgress(100)
            resolvedPath
        }
    }

    private fun unzip(zipFile: File, outputDir: File) {
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        FileInputStream(zipFile).use { fileInput ->
            ZipInputStream(fileInput).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.contains("..")) {
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                        continue
                    }

                    val outFile = File(outputDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read = zipInput.read(buffer)
                            while (read >= 0) {
                                output.write(buffer, 0, read)
                                read = zipInput.read(buffer)
                            }
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
        }
    }
}
