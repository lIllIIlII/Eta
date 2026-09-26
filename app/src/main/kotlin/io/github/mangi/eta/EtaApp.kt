package io.github.mangi.eta

import android.app.Application
import io.github.mangi.eta.agent.cloud.CloudSyncService
import io.github.mangi.eta.agent.localserver.LocalChatServerService
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.CloudSyncRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EtaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(150) {
                AppearanceSettingsRepository.settings().predictiveBackEnabled
            } ?: true
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        applicationScope.launch {
            runCatching {
                if (SettingsDataStore.localChatServerEnabled()) {
                    LocalChatServerService.start(this@EtaApp)
                }
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
            runCatching {
                val cloudConfig = CloudSyncRepository.config(this@EtaApp)
                if (cloudConfig.enabled && cloudConfig.backgroundSync) {
                    CloudSyncService.start(this@EtaApp)
                }
            }
        }
    }
}
