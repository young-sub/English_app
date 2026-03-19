package com.example.bookhelper.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BootScreen(
    uiState: BootUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Book Helper", color = Color.White, fontWeight = FontWeight.Bold)
        Text("${uiState.snapshot.progressPercent}%", color = Color(0xFFBAE6FD), fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { uiState.snapshot.progressPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(uiState.latestSummary, color = Color(0xFFE2E8F0))
        uiState.failureMessage?.let { failure ->
            Text("실패: $failure", color = Color(0xFFFCA5A5))
            Button(onClick = onRetry) {
                Text("다시 시도")
            }
        }

        Text("진행 단계", color = Color.White, fontWeight = FontWeight.SemiBold)
        uiState.snapshot.stages.forEach { stage ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stage.stage.displayName, color = Color(0xFFE2E8F0))
                Text(stage.status.name, color = Color(0xFF93C5FD))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("상세 로그", color = Color.White, fontWeight = FontWeight.SemiBold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.detailedLogs.takeLast(12)) { event ->
                Column {
                    Text("${event.stage.name} · ${event.level.name}", color = Color(0xFFF8FAFC), fontWeight = FontWeight.Medium)
                    Text(event.message, color = Color(0xFFCBD5E1))
                }
            }
        }
        if (uiState.failureMessage == null) {
            TextButton(onClick = {}, enabled = false) {
                Text("필수 단계가 끝나면 메인 화면으로 이동합니다")
            }
        }
    }
}
