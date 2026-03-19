package com.example.bookhelper.startup

import java.util.UUID

data class BootStep(
    val stage: BootStage,
    val run: suspend () -> BootStepResult,
)

interface BootDiagnosticsRecorder {
    fun record(event: BootLogEvent)
}

class InMemoryBootDiagnosticsRecorder : BootDiagnosticsRecorder {
    private val _events = mutableListOf<BootLogEvent>()
    val events: List<BootLogEvent>
        get() = _events.toList()

    override fun record(event: BootLogEvent) {
        _events += event
    }
}

class BootCoordinator(
    private val steps: List<BootStep>,
    private val recorder: BootDiagnosticsRecorder,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun run(onSnapshot: (BootStatusSnapshot) -> Unit): BootExecutionResult {
        val sessionId = sessionIdFactory()
        val stageStates = steps.map { BootStageState(stage = it.stage) }.toMutableList()

        fun emit(currentStage: BootStage? = null, canEnterMain: Boolean = false) {
            onSnapshot(
                BootStatusSnapshot(
                    sessionId = sessionId,
                    stages = stageStates.toList(),
                    currentStage = currentStage,
                    canEnterMain = canEnterMain,
                ),
            )
        }

        emit()

        steps.forEachIndexed { index, step ->
            val startedAt = nowMs()
            stageStates[index] = stageStates[index].copy(status = BootStageStatus.RUNNING)
            emit(currentStage = step.stage)
            recorder.record(
                BootLogEvent(
                    sessionId = sessionId,
                    stage = step.stage,
                    level = BootLogLevel.INFO,
                    message = "start",
                    startedAtMs = startedAt,
                ),
            )

            val result = runCatching { step.run() }
            val endedAt = nowMs()
            val durationMs = endedAt - startedAt
            if (result.isSuccess) {
                val success = result.getOrThrow()
                stageStates[index] = stageStates[index].copy(
                    status = BootStageStatus.COMPLETED,
                    summary = success.summary,
                    durationMs = durationMs,
                )
                recorder.record(
                    BootLogEvent(
                        sessionId = sessionId,
                        stage = step.stage,
                        level = BootLogLevel.INFO,
                        message = success.logMessage,
                        startedAtMs = startedAt,
                        endedAtMs = endedAt,
                        durationMs = durationMs,
                    ),
                )
                emit(currentStage = step.stage, canEnterMain = hardGatesSatisfied(stageStates))
                return@forEachIndexed
            }

            val failure = result.exceptionOrNull()!!
            stageStates[index] = stageStates[index].copy(
                status = BootStageStatus.FAILED,
                summary = failure.message ?: failure::class.java.simpleName,
                durationMs = durationMs,
            )
            recorder.record(
                BootLogEvent(
                    sessionId = sessionId,
                    stage = step.stage,
                    level = if (step.stage.hardGate) BootLogLevel.ERROR else BootLogLevel.WARN,
                    message = failure.message ?: "failed",
                    startedAtMs = startedAt,
                    endedAtMs = endedAt,
                    durationMs = durationMs,
                    errorClass = failure::class.java.simpleName,
                ),
            )
            emit(currentStage = step.stage, canEnterMain = hardGatesSatisfied(stageStates))

            if (step.stage.hardGate) {
                return BootExecutionResult(
                    finalSnapshot = BootStatusSnapshot(
                        sessionId = sessionId,
                        stages = stageStates.toList(),
                        currentStage = step.stage,
                        canEnterMain = false,
                    ),
                    failureStage = step.stage,
                )
            }
        }

        val finalSnapshot = BootStatusSnapshot(
            sessionId = sessionId,
            stages = stageStates.toList(),
            currentStage = null,
            canEnterMain = hardGatesSatisfied(stageStates),
        )
        emit(canEnterMain = finalSnapshot.canEnterMain)
        return BootExecutionResult(finalSnapshot = finalSnapshot)
    }

    private fun hardGatesSatisfied(states: List<BootStageState>): Boolean {
        return states.filter { it.isHardGate }.all { it.status == BootStageStatus.COMPLETED || it.status == BootStageStatus.SKIPPED }
    }
}
