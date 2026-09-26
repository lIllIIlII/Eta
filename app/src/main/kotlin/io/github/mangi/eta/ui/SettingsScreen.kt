package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.model.AgentCustomLinuxTool
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ApkUpdateInstaller
import io.github.mangi.eta.data.repository.AppUpdateChecker
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.agent.localserver.LocalChatServer
import io.github.mangi.eta.agent.localserver.LocalChatServerService
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.agent.tool.AgentProtectedPathPolicy
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.formatReadableBytes
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var updateDialogSummary by remember { mutableStateOf("") }
    var updateDialogNewVersion by remember { mutableStateOf("") }
    var updateDownloadUrl by remember { mutableStateOf("") }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateDownloadBytes by remember { mutableStateOf(0L) }
    var updateDownloadTotalBytes by remember { mutableStateOf(0L) }
    var showBlockedPathsDialog by remember { mutableStateOf(false) }
    var blockedPathsDraft by remember { mutableStateOf(Prefs.localAgentString(Prefs.Keys.AGENT_BLOCKED_FILE_PATHS)) }
    var blockedPathsSaved by remember { mutableStateOf(Prefs.localAgentString(Prefs.Keys.AGENT_BLOCKED_FILE_PATHS)) }
    var blockFileAccessEnabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_BLOCK_FILE_ACCESS)) }
    var showCustomToolsDialog by remember { mutableStateOf(false) }
    var customToolsDraft by remember {
        mutableStateOf(AgentCustomLinuxTool.parseAll())
    }
    val currentVersionName = remember { AppUpdateChecker.currentVersion(context) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    val agentPrefs = remember { Prefs.localAgentPreferences() }
    DisposableEffect(agentPrefs) {
        val targetPrefs = agentPrefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_BLOCK_FILE_ACCESS) {
                blockFileAccessEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_BLOCK_FILE_ACCESS)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {

            item(key = "section_agent") {
                SmallTitle(stringResource(R.string.settings_llm_providers))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_model_provider_e8c7f5),
                        summary = providerSummary,
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Memory,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }

            item(key = "section_context_extensions") {
                SmallTitle(stringResource(R.string.settings_context_extensions))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.AutoMirrored.Rounded.MenuBook,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.route_skills),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Extension,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.route_mcp_servers),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AccountTree,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )

                    ArrowPreference(
                        title = "角色",
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.TheaterComedy,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Characters) },
                    )
                }
            }

            item(key = "section_tools") {
                SmallTitle(stringResource(R.string.ui_tool_a72ef1))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_tools_list),
                        startAction = { PreferenceIcon(Icons.Rounded.Dashboard) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = Icons.Rounded.Visibility,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = Icons.Rounded.GppMaybe,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_phone_control_requires_manual_approval_a1e4f2),
                        summary = stringResource(R.string.ui_phone_control_requires_manual_approval_summary_b7c3d9),
                        key = Prefs.Keys.AGENT_PHONE_CONTROL_APPROVAL,
                        icon = Icons.Rounded.TouchApp,
                    )

                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_block_ai_file_access_c5e8a1),
                        summary = stringResource(R.string.ui_block_ai_file_access_summary_d2f6b4),
                        key = Prefs.Keys.AGENT_BLOCK_FILE_ACCESS,
                        icon = Icons.Rounded.Block,
                    )

                    if (blockFileAccessEnabled) {
                        ArrowPreference(
                            title = stringResource(R.string.ui_blocked_paths_editor_e9a7c3),
                            summary = blockedPathsSummary(blockedPathsSaved),
                            startAction = {
                                PreferenceIcon(icon = Icons.Rounded.Lock)
                            },
                            onClick = {
                                blockedPathsDraft = blockedPathsSaved
                                showBlockedPathsDialog = true
                            },
                        )
                    }

                    ArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.ui_custom_linux_tools_title_9f4b12),
                        summary = if (customToolsDraft.isEmpty()) {
                            stringResource(R.string.ui_custom_linux_tools_summary_2a6d57)
                        } else {
                            stringResource(
                                R.string.ui_custom_linux_tools_count_71c3e8,
                                customToolsDraft.size,
                            )
                        },
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Extension,
                            )
                        },
                        onClick = {
                            customToolsDraft = AgentCustomLinuxTool.parseAll()
                            showCustomToolsDialog = true
                        },
                    )
                }
            }

            item(key = "system_enhancements") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        startAction = { PreferenceIcon(Icons.Rounded.Security) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }
            }

            item(key = "section_assistant_takeover") {
                SmallTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.SupportAgent,
                            )
                        },
                        onClick = openAssistantSettings,
                    )
                }
            }

            if (capabilities.root.isGranted || hasUsedSystemizer) {
                item(key = "section_gemini") {
                    SmallTitle("Gemini")
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        ArrowPreference(
                            title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                            startAction = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.Inventory,
                                )
                            },
                            summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                            enabled = !installingSystemizer,
                            holdDownState = showSystemizerDialog,
                            onClick = {
                                if (!capabilities.root.isGranted) {
                                    onNavigate(AppRoute.SystemEnhance)
                                } else if (!installingSystemizer) {
                                    showSystemizerDialog = true
                                }
                            },
                        )
                    }
                }
            }

            item(key = "section_general") {
                SmallTitle(stringResource(R.string.settings_general))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Palette,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )

                    LanguagePreference()

                    ArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Description,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.cloud_sync_title),
                        summary = stringResource(R.string.cloud_sync_settings_summary),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.CloudSync,
                            )
                        },
                        onClick = { onNavigate(AppRoute.CloudSync) },
                    )

                    val localServerEnabled by SettingsDataStore.localChatServerEnabledFlow()
                        .collectAsState(initial = true)
                    SwitchPreference(
                        title = stringResource(R.string.local_chat_server_title),
                        summary = if (localServerEnabled && LocalChatServer.isRunning && LocalChatServer.boundEndpoint.isNotBlank()) {
                            stringResource(R.string.local_chat_server_summary_active, LocalChatServer.boundEndpoint)
                        } else {
                            stringResource(R.string.local_chat_server_summary)
                        },
                        checked = localServerEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                SettingsDataStore.setLocalChatServerEnabled(enabled)
                                if (enabled) {
                                    LocalChatServerService.start(context)
                                } else {
                                    LocalChatServerService.stop(context)
                                }
                            }
                        },
                    )
                }
            }

            item(key = "section_permissions") {
                SmallTitle(stringResource(R.string.ui_permissions_560165))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_floating_window_permissions_076b77),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Layers,
                            )
                        },
                        endActions = {
                            Text(
                                text = stringResource(
                                    if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )

                    ArrowPreference(
                        title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AccessibilityNew,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = stringResource(
                                    if (enabled) R.string.status_enabled else R.string.status_disabled,
                                ),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    MiuixTheme.colorScheme.primary
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                }
            }

            item(key = "section_about") {
                SmallTitle(stringResource(R.string.ui_about_bed172))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_check_update),
                        summary = when {
                            updateChecking -> stringResource(R.string.settings_check_update_checking)
                            updateDownloading -> updateProgressSummary(
                                bytes = updateDownloadBytes,
                                totalBytes = updateDownloadTotalBytes,
                            )
                            else -> stringResource(R.string.settings_current_version, currentVersionName)
                        },
                        enabled = !updateChecking && !updateDownloading,
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.SystemUpdateAlt,
                            )
                        },
                        onClick = {
                            updateChecking = true
                            coroutineScope.launch {
                                val release = runCatching {
                                    withContext(Dispatchers.IO) { AppUpdateChecker.latestRelease() }
                                }.getOrNull()
                                updateChecking = false
                                updateDialogVisible = true
                                updateDialogNewVersion = release?.versionName.orEmpty()
                                updateDownloadUrl = release?.downloadUrl.orEmpty()
                                updateDialogSummary = when {
                                    release == null -> context.getString(R.string.settings_check_update_failed)
                                    AppUpdateChecker.isNewer(release.versionName, currentVersionName) ->
                                        context.getString(
                                            R.string.settings_update_available_summary,
                                            release.versionName,
                                        )

                                    else -> context.getString(R.string.settings_update_latest_summary)
                                }
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Code,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(AppUpdateChecker.REPO_URL),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }

        if (showBlockedPathsDialog) {
            WindowDialog(
                show = true,
                title = stringResource(R.string.ui_blocked_paths_editor_e9a7c3),
                summary = stringResource(R.string.ui_blocked_paths_editor_summary_f4b8d6),
                onDismissRequest = { showBlockedPathsDialog = false },
            ) {
                TextField(
                    value = blockedPathsDraft,
                    onValueChange = { blockedPathsDraft = it },
                    label = stringResource(R.string.ui_blocked_paths_input_label_a6d9e5),
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showBlockedPathsDialog = false },
                    onConfirm = {
                        if (Prefs.setLocalAgentString(Prefs.Keys.AGENT_BLOCKED_FILE_PATHS, blockedPathsDraft)) {
                            blockedPathsSaved = blockedPathsDraft
                            showBlockedPathsDialog = false
                        } else {
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.settings_write_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        if (showCustomToolsDialog) {
            WindowDialog(
                show = true,
                title = stringResource(R.string.ui_custom_linux_tools_title_9f4b12),
                summary = stringResource(R.string.ui_custom_linux_tools_summary_2a6d57),
                onDismissRequest = { showCustomToolsDialog = false },
            ) {
                if (customToolsDraft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_custom_linux_tools_none_45d0f1),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        customToolsDraft.forEachIndexed { index, tool ->
                            CustomLinuxToolDraftEditor(
                                tool = tool,
                                onNameChange = { value ->
                                    customToolsDraft = customToolsDraft.toMutableList().also { list ->
                                        list[index] = list[index].copy(name = value)
                                    }
                                },
                                onDescriptionChange = { value ->
                                    customToolsDraft = customToolsDraft.toMutableList().also { list ->
                                        list[index] = list[index].copy(description = value)
                                    }
                                },
                                onCommandChange = { value ->
                                    customToolsDraft = customToolsDraft.toMutableList().also { list ->
                                        list[index] = list[index].copy(command = value)
                                    }
                                },
                                onRemove = {
                                    customToolsDraft = customToolsDraft.toMutableList().also { list ->
                                        list.removeAt(index)
                                    }
                                },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.ui_custom_linux_tool_add_e83b62),
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable {
                            customToolsDraft = customToolsDraft + AgentCustomLinuxTool(
                                name = "",
                                description = "",
                                command = "",
                                timeoutMs = AgentCustomLinuxTool.DEFAULT_TIMEOUT_MS,
                            )
                        },
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_confirm),
                    onCancel = { showCustomToolsDialog = false },
                    onConfirm = {
                        val saved = Prefs.setLocalAgentString(
                            Prefs.Keys.AGENT_CUSTOM_LINUX_TOOLS,
                            customToolsJson(customToolsDraft),
                        )
                        if (saved) {
                            customToolsDraft = AgentCustomLinuxTool.parseAll()
                            showCustomToolsDialog = false
                        } else {
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.settings_write_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                if (!capabilities.root.isGranted) {
                    showSystemizerDialog = false
                    onNavigate(AppRoute.SystemEnhance)
                    return@SystemizerConfirmDialog
                }
                enhancementHistory.recordSystemizerUse()
                hasUsedSystemizer = true
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )

        if (updateDialogVisible) {
            WindowDialog(
                show = true,
                title = if (updateDialogNewVersion.isNotBlank() && updateDownloadUrl.isNotBlank()) {
                    stringResource(R.string.settings_update_available_title)
                } else {
                    stringResource(R.string.settings_check_update)
                },
                summary = updateDialogSummary,
                onDismissRequest = { if (!updateDownloading) updateDialogVisible = false },
            ) {
                MiuixDialogActions(
                    confirmText = if (updateDialogNewVersion.isNotBlank() && updateDownloadUrl.isNotBlank()) {
                        stringResource(R.string.settings_update_download)
                    } else {
                        stringResource(R.string.ui_knew_cb63c6)
                    },
                    onCancel = { if (!updateDownloading) updateDialogVisible = false },
                    onConfirm = {
                        val targetUrl = updateDownloadUrl
                        if (targetUrl.isBlank()) {
                            updateDialogVisible = false
                            return@MiuixDialogActions
                        }
                        if (updateDownloading) return@MiuixDialogActions
                        if (!ApkUpdateInstaller.canRequestInstall(context)) {
                            updateDialogVisible = false
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.settings_update_install_permission),
                                Toast.LENGTH_LONG,
                            ).show()
                            ApkUpdateInstaller.installPermissionSettings(context)
                            return@MiuixDialogActions
                        }
                        updateDownloading = true
                        updateDownloadBytes = 0
                        updateDownloadTotalBytes = 0
                        coroutineScope.launch {
                            val downloaded = runCatching {
                                ApkUpdateInstaller.download(context, targetUrl) { bytes, total ->
                                    updateDownloadBytes = bytes
                                    updateDownloadTotalBytes = total
                                }
                            }.getOrNull()
                            updateDownloading = false
                            if (downloaded != null) {
                                updateDialogVisible = false
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_update_downloaded),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                val installError = runCatching {
                                    ApkUpdateInstaller.install(context, downloaded)
                                }.exceptionOrNull()
                                if (installError != null) {
                                    updateDialogSummary = context.getString(R.string.settings_update_install_failed)
                                }
                            } else {
                                updateDialogSummary = context.getString(R.string.settings_update_download_failed)
                            }
                        }
                    },
                )
            }
        }
}

@Composable
private fun updateProgressSummary(bytes: Long, totalBytes: Long): String =
    if (totalBytes > 0) {
        stringResource(
            R.string.settings_update_downloading,
            ((bytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt(),
        )
    } else {
        stringResource(R.string.settings_update_downloading_bytes, formatReadableBytes(bytes))
    }

@Composable
private fun blockedPathsSummary(raw: String): String {
    val count = AgentProtectedPathPolicy.parseUserBlockedPaths(raw).size
    return if (count == 0) {
        stringResource(R.string.ui_blocked_paths_empty_bb21c7)
    } else {
        stringResource(R.string.ui_blocked_paths_count_cc37e9, count)
    }
}

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (installing) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !installing,
            confirmEnabled = !installing,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->

            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            PreferenceIcon(icon = icon, enabled = enabled)
        },
        enabled = enabled,
    )
}

private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }

@Composable
private fun CustomLinuxToolDraftEditor(
    tool: AgentCustomLinuxTool,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = tool.name,
                onValueChange = onNameChange,
                label = stringResource(R.string.ui_custom_linux_tool_name_b1d94c),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ui_custom_linux_tool_remove_f9a217),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp)
                    .clickable(onClick = onRemove),
            )
        }
        TextField(
            value = tool.description,
            onValueChange = onDescriptionChange,
            label = stringResource(R.string.ui_custom_linux_tool_desc_a57e30),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        TextField(
            value = tool.command,
            onValueChange = onCommandChange,
            label = stringResource(R.string.ui_custom_linux_tool_command_c2f68d),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

private fun customToolsJson(tools: List<AgentCustomLinuxTool>): String {
    if (tools.isEmpty()) return ""
    val array = org.json.JSONArray()
    tools.forEach { tool ->
        if (!tool.isValid()) return@forEach
        array.put(
            org.json.JSONObject()
                .put("name", tool.name.trim())
                .put("description", tool.description.trim())
                .put("command", tool.command.trim())
                .put("timeout_ms", tool.timeoutMs)
        )
    }
    return if (array.length() == 0) "" else array.toString()
}
