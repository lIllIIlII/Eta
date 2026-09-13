package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

internal typealias AgentChatHomeUiState = AgentChatUiState

@Immutable
data class ActiveRunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val elapsedLabel: String,
    val currentStep: String,
)

@Immutable
data class RunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val timeLabel: String,
    val toolCount: Int,
    val durationLabel: String,
)

@Immutable
enum class RunStatusUi {
    Running,
    Success,
    Failed,
    Cancelled,
}
