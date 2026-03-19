package com.example.bookhelper.startup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootCoordinatorTest {
    @Test
    fun runExecutesStagesInOrderAndUnlocksAfterHardGates() = runBlocking {
        val executionOrder = mutableListOf<BootStage>()
        val recorder = InMemoryBootDiagnosticsRecorder()
        var fakeTime = 100L
        val coordinator = BootCoordinator(
            steps = listOf(
                BootStep(BootStage.SETTINGS) {
                    executionOrder += BootStage.SETTINGS
                    fakeTime += 25L
                    BootStepResult("settings ready")
                },
                BootStep(BootStage.DICTIONARY) {
                    executionOrder += BootStage.DICTIONARY
                    fakeTime += 40L
                    BootStepResult("dictionary ready")
                },
                BootStep(BootStage.LOCAL_TTS_RUNTIME) {
                    executionOrder += BootStage.LOCAL_TTS_RUNTIME
                    fakeTime += 15L
                    BootStepResult("local tts ready")
                },
                BootStep(BootStage.LOCAL_TTS_BENCHMARK) {
                    executionOrder += BootStage.LOCAL_TTS_BENCHMARK
                    fakeTime += 10L
                    BootStepResult("benchmark queued")
                },
            ),
            recorder = recorder,
            nowMs = { fakeTime },
            sessionIdFactory = { "boot-session" },
        )

        val snapshots = mutableListOf<BootStatusSnapshot>()
        val result = coordinator.run { snapshots += it }

        assertEquals(
            listOf(BootStage.SETTINGS, BootStage.DICTIONARY, BootStage.LOCAL_TTS_RUNTIME, BootStage.LOCAL_TTS_BENCHMARK),
            executionOrder,
        )
        assertTrue(result.finalSnapshot.canEnterMain)
        assertEquals(BootStageStatus.COMPLETED, result.finalSnapshot.stages.first { it.stage == BootStage.SETTINGS }.status)
        assertEquals(BootStageStatus.COMPLETED, result.finalSnapshot.stages.first { it.stage == BootStage.LOCAL_TTS_BENCHMARK }.status)
        assertEquals(8, recorder.events.size)
        assertTrue(snapshots.any { it.currentStage == BootStage.SETTINGS })
        assertEquals(100, result.finalSnapshot.progressPercent)
    }

    @Test
    fun runStopsOnHardGateFailure() = runBlocking {
        val executionOrder = mutableListOf<BootStage>()
        val coordinator = BootCoordinator(
            steps = listOf(
                BootStep(BootStage.SETTINGS) {
                    executionOrder += BootStage.SETTINGS
                    BootStepResult("settings ready")
                },
                BootStep(BootStage.SYSTEM_TTS) {
                    executionOrder += BootStage.SYSTEM_TTS
                    error("tts blocked")
                },
                BootStep(BootStage.CAMERA_WARMUP) {
                    executionOrder += BootStage.CAMERA_WARMUP
                    BootStepResult("camera warmed")
                },
            ),
            recorder = InMemoryBootDiagnosticsRecorder(),
            sessionIdFactory = { "boot-session" },
        )

        val result = coordinator.run { }

        assertEquals(listOf(BootStage.SETTINGS, BootStage.SYSTEM_TTS), executionOrder)
        assertFalse(result.finalSnapshot.canEnterMain)
        assertEquals(BootStage.SYSTEM_TTS, result.failureStage)
        assertEquals(BootStageStatus.FAILED, result.finalSnapshot.stages.first { it.stage == BootStage.SYSTEM_TTS }.status)
        assertEquals(BootStageStatus.PENDING, result.finalSnapshot.stages.first { it.stage == BootStage.CAMERA_WARMUP }.status)
    }

    @Test
    fun runContinuesAfterSoftGateFailure() = runBlocking {
        val executionOrder = mutableListOf<BootStage>()
        val coordinator = BootCoordinator(
            steps = listOf(
                BootStep(BootStage.SETTINGS) {
                    executionOrder += BootStage.SETTINGS
                    BootStepResult("settings ready")
                },
                BootStep(BootStage.LOCAL_TTS_RUNTIME) {
                    executionOrder += BootStage.LOCAL_TTS_RUNTIME
                    BootStepResult("local tts ready")
                },
                BootStep(BootStage.LOCAL_TTS_BENCHMARK) {
                    executionOrder += BootStage.LOCAL_TTS_BENCHMARK
                    error("slow benchmark")
                },
                BootStep(BootStage.CAMERA_WARMUP) {
                    executionOrder += BootStage.CAMERA_WARMUP
                    BootStepResult("camera ready")
                },
            ),
            recorder = InMemoryBootDiagnosticsRecorder(),
            sessionIdFactory = { "boot-session" },
        )

        val result = coordinator.run { }

        assertEquals(
            listOf(BootStage.SETTINGS, BootStage.LOCAL_TTS_RUNTIME, BootStage.LOCAL_TTS_BENCHMARK, BootStage.CAMERA_WARMUP),
            executionOrder,
        )
        assertTrue(result.finalSnapshot.canEnterMain)
        assertEquals(BootStageStatus.FAILED, result.finalSnapshot.stages.first { it.stage == BootStage.LOCAL_TTS_BENCHMARK }.status)
        assertEquals(BootStageStatus.COMPLETED, result.finalSnapshot.stages.first { it.stage == BootStage.CAMERA_WARMUP }.status)
    }
}
