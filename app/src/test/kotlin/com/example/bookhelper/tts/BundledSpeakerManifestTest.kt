package com.example.bookhelper.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledSpeakerManifestTest {
    @Test
    fun parseReadsSpeakerProfilesFromTsv() {
        val manifest = listOf(
            "speaker_id\tcode\tdisplay_label\taccent_label\tgender",
            "0\t3922\tSpeaker 0\tPreset 1\tUNKNOWN",
            "10\t3615\tSpeaker 10\tPreset 2\tUNKNOWN",
        ).joinToString("\n")

        val speakers = BundledSpeakerManifest.parse(manifest)

        assertEquals(2, speakers.size)
        assertEquals(0, speakers[0].id)
        assertEquals("3922", speakers[0].code)
        assertEquals("Speaker 10", speakers[1].displayLabel)
        assertEquals("Preset 2", speakers[1].accentLabel)
    }
}
