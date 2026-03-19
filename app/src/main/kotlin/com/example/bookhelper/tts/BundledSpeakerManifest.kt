package com.example.bookhelper.tts

import android.content.res.AssetManager

internal object BundledSpeakerManifest {
    const val FILE_NAME = "speaker-manifest.tsv"

    fun parse(content: String): List<LocalSpeakerProfile> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) {
            return emptyList()
        }

        return lines.drop(1).mapNotNull { line ->
            val columns = line.split('\t')
            if (columns.size < 5) {
                return@mapNotNull null
            }
            val speakerId = columns[0].toIntOrNull() ?: return@mapNotNull null
            val gender = runCatching { SpeakerGender.valueOf(columns[4]) }.getOrDefault(SpeakerGender.UNKNOWN)
            LocalSpeakerProfile(
                id = speakerId,
                code = columns[1],
                displayLabel = columns[2],
                accentLabel = columns[3],
                gender = gender,
            )
        }
    }

    fun loadFromAssets(assets: AssetManager, assetDirectory: String): List<LocalSpeakerProfile> {
        val assetPath = "$assetDirectory/$FILE_NAME"
        val content = runCatching {
            assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return parse(content)
    }
}
