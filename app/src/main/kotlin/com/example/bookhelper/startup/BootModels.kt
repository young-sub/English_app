package com.example.bookhelper.startup

enum class BootStage(
    val displayName: String,
    val hardGate: Boolean,
) {
    SETTINGS("설정 불러오는 중", true),
    BUNDLED_ASSETS("번들 음성 자산 확인 중", true),
    DICTIONARY("사전 데이터 준비 중", true),
    SYSTEM_TTS("시스템 TTS 준비 중", true),
    LOCAL_TTS_RUNTIME("로컬 TTS 구동 준비 중", true),
    PROVISIONING("기본 준비 상태 확인 중", true),
    LOCAL_TTS_BENCHMARK("로컬 TTS 성능 측정 중", false),
    CAMERA_WARMUP("카메라 준비 상태 점검 중", false),
}

enum class BootStageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class BootLogLevel {
    INFO,
    WARN,
    ERROR,
}

data class BootStageState(
    val stage: BootStage,
    val status: BootStageStatus = BootStageStatus.PENDING,
    val summary: String? = null,
    val durationMs: Long? = null,
    val isHardGate: Boolean = stage.hardGate,
)

data class BootStatusSnapshot(
    val sessionId: String,
    val stages: List<BootStageState>,
    val currentStage: BootStage? = null,
    val canEnterMain: Boolean = false,
) {
    val progressPercent: Int
        get() {
            if (stages.isEmpty()) return 0
            val completedCount = stages.count { it.status == BootStageStatus.COMPLETED || it.status == BootStageStatus.SKIPPED }
            return ((completedCount.toFloat() / stages.size.toFloat()) * 100f).toInt()
        }
}

data class BootLogEvent(
    val sessionId: String,
    val stage: BootStage,
    val level: BootLogLevel,
    val message: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val durationMs: Long? = null,
    val attempt: Int = 1,
    val errorClass: String? = null,
)

data class BootStepResult(
    val summary: String,
    val logMessage: String = summary,
)

data class BootExecutionResult(
    val finalSnapshot: BootStatusSnapshot,
    val failureStage: BootStage? = null,
)
