package io.github.mangi.eta.ui.components

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ApkUpdateInstaller
import io.github.mangi.eta.data.repository.AppUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class LaunchUpdateInfo(
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
)

@Composable
internal fun LaunchUpdateDialogHost() {
    var info by remember { mutableStateOf<LaunchUpdateInfo?>(null) }
    var checkedOnce by rememberSaveable { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(0L) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (!checkedOnce) {
        LaunchedEffect(Unit) {
            delay(1_600)
            val release = withContext(Dispatchers.IO) {
                runCatching { AppUpdateChecker.latestRelease() }.getOrNull()
            }
            checkedOnce = true
            val currentVersion = AppUpdateChecker.currentVersion(context)
            val candidate = release
                ?.takeIf {
                    it.downloadUrl.isNotBlank() &&
                        AppUpdateChecker.isNewer(it.versionName, currentVersion)
                }
                ?.let { LaunchUpdateInfo(it.versionName, it.notes, it.downloadUrl) }
            if (candidate != null) info = candidate
        }
    }

    info?.let { current ->
        LaunchUpdateDialog(
            info = current,
            downloading = downloading,
            downloadedBytes = downloadBytes,
            totalBytes = downloadTotalBytes,
            onDismiss = {
                if (downloading) {
                    downloadJob?.cancel()
                    downloadJob = null
                    downloading = false
                }
                info = null
            },
            onUpdate = {
                if (downloading) return@LaunchUpdateDialog
                if (!ApkUpdateInstaller.canRequestInstall(context)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_update_install_permission),
                        Toast.LENGTH_LONG,
                    ).show()
                    ApkUpdateInstaller.installPermissionSettings(context)
                    return@LaunchUpdateDialog
                }
                downloading = true
                downloadBytes = 0
                downloadTotalBytes = 0
                downloadJob = coroutineScope.launch {
                    var cancelled = false
                    val downloaded = try {
                        runCatching {
                            ApkUpdateInstaller.download(context, current.downloadUrl) { bytes, total ->
                                downloadBytes = bytes
                                downloadTotalBytes = total
                            }
                        }.getOrElse { throwable ->
                            if (throwable is kotlinx.coroutines.CancellationException) {
                                cancelled = true
                            }
                            null
                        }
                    } catch (throwable: Throwable) {
                        if (throwable is kotlinx.coroutines.CancellationException) cancelled = true
                        null
                    }
                    downloading = false
                    downloadJob = null
                    when {
                        downloaded != null -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_update_downloaded),
                                Toast.LENGTH_SHORT,
                            ).show()
                            val installError = runCatching {
                                ApkUpdateInstaller.install(context, downloaded)
                            }.exceptionOrNull()
                            if (installError != null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_update_install_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            info = null
                        }
                        cancelled -> Unit
                        else -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_update_download_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LaunchUpdateDialog(
    info: LaunchUpdateInfo,
    downloading: Boolean,
    downloadedBytes: Long,
    totalBytes: Long,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !downloading,
        ),
    ) {
        var appeared by remember(info.versionName) { mutableStateOf(false) }
        LaunchedEffect(info.versionName) { appeared = true }
        val scale by animateFloatAsState(
            targetValue = if (appeared) 1f else 0.86f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "glass_scale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(220),
            label = "glass_alpha",
        )

        val colorScheme = MiuixTheme.colorScheme
        val dark = isDarkTheme()
        val glassShape = RoundedCornerShape(30.dp)
        val progressFraction = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            null
        }
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
                        cornerRadius = 30.dp,
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
                            Text(
                                text = "↑",
                                color = Color.White,
                                fontSize = 22.sp,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_update_available_title),
                                color = colorScheme.onSurface,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Eta ${info.versionName}",
                                color = colorScheme.primary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_launch_subtitle, info.versionName),
                        color = colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                    val notes = info.notes.trim()
                    if (notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_launch_whats_new),
                            color = colorScheme.onSurface,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 168.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorScheme.surfaceContainer.copy(alpha = 0.55f))
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = notes,
                                color = colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                    if (downloading) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(colorScheme.surfaceContainerHigh),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction ?: 1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (progressFraction != null) {
                                            colorScheme.primary
                                        } else {
                                            colorScheme.primary.copy(alpha = 0.5f)
                                        },
                                    ),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (totalBytes > 0) {
                                stringResource(
                                    R.string.settings_update_downloading,
                                    ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt(),
                                )
                            } else {
                                stringResource(
                                    R.string.settings_update_downloading_bytes,
                                    formatReadableBytes(downloadedBytes),
                                )
                            },
                            color = colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GlassActionButton(
                            text = if (downloading) {
                                stringResource(R.string.update_launch_cancel_download)
                            } else {
                                stringResource(R.string.update_launch_later)
                            },
                            enabled = true,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        GlassActionButton(
                            text = if (downloading) {
                                stringResource(R.string.update_launch_downloading_short)
                            } else {
                                stringResource(R.string.settings_update_download)
                            },
                            enabled = !downloading,
                            primary = !downloading,
                            filled = downloading,
                            onClick = onUpdate,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal fun formatReadableBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toFloat())
    else -> "$bytes B"
}

@Composable
private fun GlassActionButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MiuixTheme.colorScheme
    val contentColor = when {
        filled -> colorScheme.onSurfaceVariantSummary
        primary -> colorScheme.primary
        else -> colorScheme.onSurfaceVariantSummary
    }
    val shape = RoundedCornerShape(16.dp)
    var base = modifier
        .clip(shape)
        .then(
            if (filled) {
                Modifier.background(colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            } else {
                Modifier.border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = 0.35f),
                    shape = shape,
                )
            }
        )
    if (primary && !filled) {
        base = base.background(colorScheme.primary.copy(alpha = 0.14f), shape)
    }
    base = if (enabled) {
        base.clickable(onClick = onClick)
    } else {
        base.alpha(0.55f)
    }
    Box(
        modifier = base.padding(vertical = 12.dp),
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

@Composable
private fun isDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue
    return luminance < 0.5f
}
