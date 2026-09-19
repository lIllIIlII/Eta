package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Ballot
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolApprovalGate
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentToolApprovalDialogHost() {
    val request by AgentToolApprovalGate.pending.collectAsStateWithLifecycle()
    request?.let { current ->
        AgentToolApprovalDialog(
            request = current,
            onAllow = { AgentToolApprovalGate.respond(current.id, true) },
            onDeny = { AgentToolApprovalGate.respond(current.id, false) },
        )
    }
}

@Composable
private fun AgentToolApprovalDialog(
    request: AgentToolApprovalGate.Request,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        var appeared by remember(request.id) { mutableStateOf(false) }
        LaunchedEffect(request.id) { appeared = true }
        val scale by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.86f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "approval_glass_scale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(220),
            label = "approval_glass_alpha",
        )

        val colorScheme = MiuixTheme.colorScheme
        val dark = isApprovalDarkTheme()
        val glassShape = RoundedCornerShape(28.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .systemBarsPadding()
                .alpha(alpha)
                .scale(scale),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 36.dp)
                    .fillMaxWidth()
                    .clip(glassShape)
                    .squircleSurface(
                        color = colorScheme.surface.copy(alpha = if (dark) 0.60f else 0.80f),
                        cornerRadius = 28.dp,
                    )
                    .border(
                        width = 0.8.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.42f else 0.75f),
                                Color.White.copy(alpha = 0.10f),
                            ),
                        ),
                        shape = glassShape,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.04f),
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            colorScheme.primary,
                                            colorScheme.primary.copy(alpha = 0.72f),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = approvalToolIcon(request.toolName),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.tool_approval_title),
                                color = colorScheme.onSurface,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = approvalToolLabel(request.toolName),
                                color = colorScheme.primary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    val summary = request.summary.trim()
                    if (summary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorScheme.surfaceContainer.copy(alpha = 0.55f)),
                        ) {
                            Text(
                                text = summary,
                                color = colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.tool_approval_hint),
                        color = colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ApprovalGlassActionButton(
                            text = stringResource(R.string.tool_approval_deny),
                            onClick = onDeny,
                            modifier = Modifier.weight(1f),
                        )
                        ApprovalGlassActionButton(
                            text = stringResource(R.string.tool_approval_allow),
                            primary = true,
                            onClick = onAllow,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalGlassActionButton(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MiuixTheme.colorScheme
    val contentColor = if (primary) colorScheme.primary else colorScheme.onSurfaceVariantSummary
    val shape = RoundedCornerShape(16.dp)
    var base = modifier
        .clip(shape)
        .border(
            width = 0.8.dp,
            color = Color.White.copy(alpha = 0.35f),
            shape = shape,
        )
    if (primary) {
        base = base.background(colorScheme.primary.copy(alpha = 0.14f), shape)
    }
    Box(
        modifier = base
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun approvalToolLabel(toolName: String): String = when (toolName) {
    "launch_app" -> "打开应用"
    "open_uri" -> "打开链接"
    "open_system_panel" -> "打开系统面板"
    "press_key" -> "按下系统按键"
    "set_alarm" -> "设置闹钟"
    "set_timer" -> "设置计时器"
    "set_volume" -> "调整音量"
    "media_control" -> "控制媒体播放"
    "set_setting" -> "修改系统设置"
    "set_device_state" -> "更改设备状态"
    "app_state_control" -> "控制应用状态"
    else -> toolName
}

private fun approvalToolIcon(toolName: String): ImageVector = when (toolName) {
    "launch_app" -> Icons.Rounded.Apps
    "open_uri" -> Icons.Rounded.Link
    "open_system_panel" -> Icons.Rounded.Settings
    "press_key" -> Icons.Rounded.Mouse
    "set_alarm" -> Icons.Rounded.Alarm
    "set_timer" -> Icons.Rounded.Timer
    "set_volume" -> Icons.Rounded.VolumeUp
    "media_control" -> Icons.Rounded.PlayCircle
    "set_setting" -> Icons.Rounded.Tune
    "set_device_state" -> Icons.Rounded.Devices
    "app_state_control" -> Icons.Rounded.Ballot
    else -> Icons.Rounded.TouchApp
}

@Composable
private fun isApprovalDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue
    return luminance < 0.5f
}
