package io.github.mangi.eta.ui.screens.cloud

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.cloud.CloudSyncService
import io.github.mangi.eta.data.repository.CloudSyncConfig
import io.github.mangi.eta.data.repository.CloudSyncRepository
import io.github.mangi.eta.data.repository.CloudSyncStatus
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.components.PreferenceIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(CloudSyncConfig()) }
    var status by remember { mutableStateOf(CloudSyncStatus()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        config = CloudSyncRepository.config(context)
        status = CloudSyncRepository.status(context)
        loaded = true
        CloudSyncRepository.statusFlow(context).collect { status = it }
    }

    fun persist(updated: CloudSyncConfig, restartService: Boolean) {
        config = updated
        if (!loaded) return
        scope.launch {
            runCatching { CloudSyncRepository.saveConfig(context, updated) }
            if (restartService) {
                if (updated.enabled && updated.backgroundSync) {
                    CloudSyncService.start(context)
                } else {
                    CloudSyncService.stop(context)
                }
            }
        }
    }

    fun runSync(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { action() }
            busy = false
            result.onSuccess { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    throwable.message ?: context.getString(R.string.cloud_sync_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    MiuixScaffold(
        title = stringResource(R.string.cloud_sync_title),
        onBack = onBack,
    ) { paddingValues, scrollBehavior, sidePadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding())
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
            overscrollEffect = null,
        ) {
            item(key = "section-basic") {
                SmallTitle(stringResource(R.string.cloud_sync_title))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.cloud_sync_enable),
                        summary = stringResource(R.string.cloud_sync_enable_summary),
                        checked = config.enabled,
                        onCheckedChange = { enabled ->
                            persist(config.copy(enabled = enabled), restartService = true)
                        },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.cloud_sync_background),
                        summary = stringResource(R.string.cloud_sync_background_summary),
                        checked = config.backgroundSync,
                        onCheckedChange = { background ->
                            persist(config.copy(backgroundSync = background), restartService = true)
                        },
                    )
                }
            }
            item(key = "section-server") {
                SmallTitle(stringResource(R.string.cloud_sync_server_section))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = config.serverUrl,
                            onValueChange = { value ->
                                persist(config.copy(serverUrl = value), restartService = false)
                            },
                            label = stringResource(R.string.cloud_sync_server_url),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TextField(
                            value = config.token,
                            onValueChange = { value ->
                                persist(config.copy(token = value), restartService = false)
                            },
                            label = stringResource(R.string.cloud_sync_token),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.cloud_sync_server_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }
            item(key = "section-actions") {
                SmallTitle(stringResource(R.string.cloud_sync_actions))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.cloud_sync_sync_now),
                        summary = stringResource(R.string.cloud_sync_sync_now_summary),
                        enabled = !busy && config.enabled,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.CloudSync)
                        },
                        onClick = {
                            runSync { CloudSyncRepository.syncNow(context, restoreMemory = false) }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.cloud_sync_restore),
                        summary = stringResource(R.string.cloud_sync_restore_summary),
                        enabled = !busy && config.enabled,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Restore)
                        },
                        onClick = {
                            runSync { CloudSyncRepository.syncNow(context, restoreMemory = true) }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.cloud_sync_push_all),
                        summary = stringResource(R.string.cloud_sync_push_all_summary),
                        enabled = !busy && config.enabled,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.CloudUpload)
                        },
                        onClick = {
                            runSync {
                                val count = CloudSyncRepository.pushAllConversations(context)
                                context.getString(R.string.cloud_sync_pushed_count, count)
                            }
                        },
                    )
                }
            }
            item(key = "section-status") {
                SmallTitle(stringResource(R.string.cloud_sync_status_section))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.cloud_sync_last_sync),
                        summary = if (status.lastSyncAt > 0L) {
                            SimpleDateFormat.getDateTimeInstance().format(Date(status.lastSyncAt))
                        } else {
                            stringResource(R.string.cloud_sync_never_synced)
                        },
                    )
                    BasicComponent(
                        title = stringResource(R.string.cloud_sync_pending_uploads),
                        summary = status.pendingUploads.toString(),
                    )
                }
            }
            item(key = "section-help") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.cloud_sync_help_title),
                            style = MiuixTheme.textStyles.body2,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.cloud_sync_help_body),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }
        }
    }
}
