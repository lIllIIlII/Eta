package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ProviderConfigDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
) {
    companion object {
        const val FORMAT = "eta-provider-config"
        const val SCHEMA_VERSION = 1
    }
}

internal data class ProviderConfigSummary(
    val providerCount: Int,
    val modelCount: Int,
)

internal class ProviderConfigTransferException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object ProviderConfigTransfer {
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    suspend fun export(context: Context, output: OutputStream): ProviderConfigSummary =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val database = EtaDatabase.get(appContext)
            val providers = database.providerDao().providers().map { seed ->
                EtaBackupProvider(provider = seed.provider, models = seed.models)
            }
            val settings = SettingsDataStore.settings()
            val document = ProviderConfigDocument(
                exportedAt = System.currentTimeMillis(),
                providers = providers,
                selectedProviderId = settings.selectedProviderId,
                selectedModelId = settings.selectedModelId,
            )
            val bytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                throw ProviderConfigTransferException("模型配置文件超过 16 MiB 限制")
            }
            output.write(bytes)
            output.flush()
            document.summary()
        }

    suspend fun import(context: Context, input: InputStream): ProviderConfigSummary =
        withContext(Dispatchers.IO) {
            val document = readDocument(input)
            validate(document)
            val appContext = context.applicationContext
            val database = EtaDatabase.get(appContext)
            database.withTransaction {
                document.providers.forEach { seed ->
                    database.providerDao().replaceProvider(
                        provider = seed.provider,
                        models = seed.models,
                    )
                }
            }
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.repairSelection()
            runCatching {
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
            document.summary()
        }

    private fun readDocument(input: InputStream): ProviderConfigDocument {
        val bytes = input.use { stream ->
            val read = stream.readBytes()
            if (read.size > MAX_FILE_BYTES) {
                throw ProviderConfigTransferException("模型配置文件超过 16 MiB 限制")
            }
            read
        }
        if (bytes.isEmpty()) throw ProviderConfigTransferException("模型配置文件为空")
        return runCatching {
            json.decodeFromString<ProviderConfigDocument>(bytes.toString(Charsets.UTF_8))
        }.getOrElse { failure ->
            throw ProviderConfigTransferException("模型配置文件格式无效", failure)
        }
    }

    private fun validate(document: ProviderConfigDocument) {
        if (document.format != ProviderConfigDocument.FORMAT) {
            throw ProviderConfigTransferException("文件不是 Eta 模型配置（format=${document.format}）")
        }
        if (document.providers.isEmpty()) {
            throw ProviderConfigTransferException("文件中没有可导入的模型配置")
        }
        val seen = mutableSetOf<String>()
        document.providers.forEach { seed ->
            val id = seed.provider.id
            if (id.isBlank()) {
                throw ProviderConfigTransferException("存在缺少 ID 的模型配置")
            }
            if (!seen.add(id)) {
                throw ProviderConfigTransferException("存在重复 ID 的模型配置：$id")
            }
        }
    }

    private fun ProviderConfigDocument.summary(): ProviderConfigSummary = ProviderConfigSummary(
        providerCount = providers.size,
        modelCount = providers.sumOf { it.models.size },
    )
}
