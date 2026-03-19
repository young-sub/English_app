package com.example.bookhelper.startup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val BootBackground = Brush.verticalGradient(
    colors = listOf(Color(0xFF07111F), Color(0xFF0D1B2E), Color(0xFF13263B)),
)
private val BootCard = Color(0xCCF8FAFC)
private val BootMuted = Color(0xFF5B6B7F)
private val BootAccent = Color(0xFF1D4ED8)
private val BootAccentSoft = Color(0xFFBFDBFE)
private val BootFailure = Color(0xFFB91C1C)

@Composable
fun BootScreen(
    uiState: BootUiState,
    onRetry: () -> Unit,
) {
    var showDiagnostics by remember(uiState.failureMessage) { mutableStateOf(uiState.failureMessage != null) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BootBackground)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = BootCard,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(color = BootAccentSoft, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("B", color = BootAccent, fontWeight = FontWeight.Black)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "책을 읽기 좋은 상태로 준비하고 있어요",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = userFacingBootSummary(uiState.latestSummary),
                        color = BootMuted,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 3.dp,
                            color = BootAccent,
                            trackColor = BootAccentSoft,
                        )
                        Text("준비 중", color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "${uiState.snapshot.progressPercent}%",
                        color = BootAccent,
                        fontWeight = FontWeight.Bold,
                    )
                }

                LinearProgressIndicator(
                    progress = { uiState.snapshot.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = BootAccent,
                    trackColor = Color(0xFFE2E8F0),
                )

                Text(
                    text = "초기 준비가 끝나면 바로 본문 화면으로 이동합니다.",
                    color = BootMuted,
                )

                uiState.failureMessage?.let { failure ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFEE2E2),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("준비 과정에서 문제가 발생했어요", color = BootFailure, fontWeight = FontWeight.Bold)
                            Text(
                                text = "잠시 후 다시 시도하면 대부분 정상적으로 이어집니다.",
                                color = Color(0xFF7F1D1D),
                            )
                            Button(onClick = onRetry) {
                                Text("다시 시도")
                            }
                            AnimatedVisibility(showDiagnostics) {
                                Text("세부 오류: $failure", color = Color(0xFF7F1D1D))
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Text(if (showDiagnostics) "기술 정보 숨기기" else "기술 정보 보기")
                }

                AnimatedVisibility(visible = showDiagnostics) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        uiState.snapshot.stages.forEach { stage ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stage.stage.displayName, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                                    stage.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                        Text(summary, color = BootMuted)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stage.status.name, color = BootAccent, fontWeight = FontWeight.Medium)
                            }
                        }

                        if (uiState.detailedLogs.isNotEmpty()) {
                            HorizontalDivider(color = Color(0xFFE2E8F0))
                            uiState.detailedLogs.takeLast(6).forEach { event ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = event.stage.displayName,
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(event.message, color = BootMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun userFacingBootSummary(summary: String): String {
    val normalized = summary.trim()
    return when {
        normalized.isBlank() -> "기기와 사전, 음성 설정을 확인하고 있어요."
        normalized.contains("성능 측정") || normalized.contains("벤치마크") -> "온디바이스 음성을 마지막으로 확인하고 있어요."
        normalized.contains("로컬 TTS") -> "온디바이스 음성 준비를 마무리하고 있어요."
        normalized.contains("시스템 TTS") -> "기기 음성 엔진 연결을 확인하고 있어요."
        normalized.contains("사전") -> "사전과 저장된 단어를 불러오고 있어요."
        normalized.contains("설정") -> "이전 사용 설정을 불러오고 있어요."
        normalized.contains("준비 완료") -> "이제 바로 읽기를 시작할 수 있어요."
        else -> normalized
    }
}
