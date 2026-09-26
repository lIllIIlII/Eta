package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderConfigSummary
import io.github.mangi.eta.data.repository.ProviderConfigTransfer
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    fun showTransferFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.provider_config_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferBusy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.provider_config_failed))
                val summary = output.use { ProviderConfigTransfer.export(context, it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.provider_config_exported,
                        summary.providerCount,
                        summary.modelCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showTransferFailure(throwable)
            } finally {
                transferBusy = false
            }
        }
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults()
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

    MiuixScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.ui_search_provider_74e049),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }

        item(key = "create_section") {
            ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                ArrowPreference(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )

                ArrowPreference(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
            }
        }

        item(key = "list_section") {
            ProviderSection(title = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size)) {
                if (filteredProviders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource(R.string.provider_empty)
                            } else {
                                stringResource(R.string.provider_no_matches)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    filteredProviders.forEach { provider ->
                        ProviderListItem(
                            provider = provider,
                            isSelected = provider.id == selectedProviderId,
                            onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                            onDelete = if (!provider.isBuiltIn) {
                                { providerToDelete = provider }
                            } else {
                                null
                            },
                            onSelect = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                    RuntimeConfigRepository.syncRuntimeConfig()
                                }
                            },
                        )
                    }
                }
            }
        }

        item(key = "transfer_section_title") {
            SmallTitle(stringResource(R.string.provider_config_transfer_title))
        }
        item(key = "transfer_section") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.provider_config_export),
                    summary = if (transferBusy) {
                        stringResource(R.string.provider_config_working)
                    } else {
                        stringResource(R.string.provider_config_export_summary)
                    },
                    enabled = !transferBusy,
                    startAction = {
                        TransferIcon(icon = Icons.Rounded.Download, loading = transferBusy)
                    },
                    onClick = {
                        exportConfigLauncher.launch(defaultProviderConfigFileName())
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.provider_config_import),
                    summary = stringResource(R.string.provider_config_import_summary),
                    enabled = !transferBusy,
                    startAction = {
                        TransferIcon(icon = Icons.Rounded.Upload, loading = false)
                    },
                    onClick = {
                        importConfigLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                )
            }
        }
    }

    if (providerToDelete != null) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, providerToDelete?.name.orEmpty()),
            onDismissRequest = { providerToDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onCancel = { providerToDelete = null },
                onConfirm = {
                    scope.launch {
                        providerToDelete?.let { provider ->
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncRuntimeConfig()
                        }
                        providerToDelete = null
                    }
                },
            )
        }
    }

    if (showImportDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.provider_config_import_confirm_title),
            summary = stringResource(R.string.provider_config_import_confirm_summary),
            onDismissRequest = {
                if (!transferBusy) {
                    showImportDialog = false
                    pendingImportUri = null
                }
            },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_import),
                destructive = true,
                cancelEnabled = !transferBusy,
                confirmEnabled = !transferBusy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        transferBusy = true
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error(context.getString(R.string.provider_config_failed))
                            val summary = input.use { ProviderConfigTransfer.import(context, it) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.provider_config_imported,
                                    summary.providerCount,
                                    summary.modelCount,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showTransferFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            transferBusy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TransferIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, loading: Boolean) {
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun defaultProviderConfigFileName(): String =
    "Eta-providers-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onDelete
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { alpha = opacity },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(provider)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.baseUrl,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = listOfNotNull(
                    provider.typeLabel,
                    pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
                    stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!provider.isEnabled) {
                Text(
                    text = stringResource(R.string.ui_disabled_0fe5a9),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onSelect) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (isSelected) {
                    stringResource(R.string.provider_selected)
                } else {
                    stringResource(R.string.provider_set_current)
                },
                tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
