package app.glance.wallet

import androidx.room.withTransaction
import app.glance.wallet.core.crypto.parseSingleAddress
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.DerivedAddressEntity
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.LabelEntity
import app.glance.wallet.core.data.db.LabelReferenceType
import app.glance.wallet.core.data.db.ScriptType
import app.glance.wallet.core.data.db.ServerConfigEntity
import app.glance.wallet.core.data.db.WalletGroupEntity
import app.glance.wallet.core.data.db.WatchTargetType
import app.glance.wallet.core.data.db.WatchedKeyEntity
import app.glance.wallet.core.security.ExplorerPreset
import app.glance.wallet.core.security.FIAT_CURRENCIES
import app.glance.wallet.core.security.SecurityPreferences
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.UtxoView
import kotlinx.coroutines.flow.first

internal class BackupRestoreException : Exception("Backup cannot be restored")

/** Maps only explicitly exportable configuration. Security state and sync caches never cross this boundary. */
internal class BackupRepository(
    private val database: GlanceDatabase,
    private val preferences: SecurityPreferencesStore,
    private val writeSettings: suspend (BackupSettings) -> Unit = { settings ->
        preferences.update { current -> current.withBackupSettings(settings) }
    },
) {
    suspend fun canRestore(): Boolean = database.watchedKeyDao().observeAll().first().isEmpty() && database.walletGroupDao().all().isEmpty() && database.labelDao().all().isEmpty() && database.serverConfigDao().all().isEmpty()
    suspend fun snapshot(): BackupSnapshot {
        val snapshot = database.withTransaction {
            BackupSnapshot(
                walletGroups = database.walletGroupDao().all().map { BackupWalletGroup(it.id, it.label, it.utxoView, it.dustThresholdSats, it.preferredReceiveScriptType.name) },
                watchedKeys = database.watchedKeyDao().all().map { BackupWatchedKey(it.id, it.label, it.keyMaterial, it.scriptType.name, it.targetType.name, it.walletGroupId, it.utxoView ?: UtxoView.BUBBLES.name, it.dustThresholdSats) },
                labels = database.labelDao().all().map { BackupLabel(it.referenceType.name, it.referenceId, it.text) },
                serverConfigs = database.serverConfigDao().all().map { BackupServerConfig(it.id, it.protocol, it.host, it.port, it.useTls, it.isCustom) },
            )
        }
        return snapshot.copy(settings = preferences.data.first().toBackupSettings())
    }

    suspend fun restore(snapshot: BackupSnapshot, directConnectionConfirmed: Boolean = false) {
        validate(snapshot)
        if (!snapshot.settings.torEnabled && !directConnectionConfirmed) throw BackupRestoreException()
        database.withTransaction {
            if (database.watchedKeyDao().observeAll().first().isNotEmpty() || database.walletGroupDao().all().isNotEmpty() || database.labelDao().all().isNotEmpty() || database.serverConfigDao().all().isNotEmpty()) throw BackupRestoreException()
            val now = System.currentTimeMillis()
            snapshot.walletGroups.forEach { group -> database.walletGroupDao().insert(WalletGroupEntity(group.id, group.label, now, group.utxoView, group.dustThresholdSats, ScriptType.valueOf(group.preferredReceiveScriptType))) }
            snapshot.watchedKeys.forEach { key ->
                val target = WatchTargetType.valueOf(key.targetType)
                database.watchedKeyDao().upsert(WatchedKeyEntity(key.id, key.label, key.keyMaterial, ScriptType.valueOf(key.scriptType), now, targetType = target, utxoView = key.utxoView, dustThresholdSats = key.dustThresholdSats, walletGroupId = key.walletGroupId))
                if (target == WatchTargetType.SINGLE_ADDRESS) database.derivedAddressDao().upsert(DerivedAddressEntity(keyId = key.id, chain = AddressChain.EXTERNAL, derivationIndex = 0, address = key.keyMaterial, isUsed = false, isConfirmedUnused = false))
            }
            snapshot.labels.forEach { label -> database.labelDao().upsert(LabelEntity(LabelReferenceType.valueOf(label.referenceType), label.referenceId, label.text)) }
            snapshot.serverConfigs.forEach { config -> database.serverConfigDao().upsert(ServerConfigEntity(config.id, config.protocol, config.host, config.port, config.useTls, config.isCustom)) }
        }
        try {
            writeSettings(snapshot.settings)
        } catch (failure: Throwable) {
            database.withTransaction {
                snapshot.labels.forEach { label -> database.labelDao().delete(LabelReferenceType.valueOf(label.referenceType), label.referenceId) }
                snapshot.serverConfigs.forEach { config -> database.serverConfigDao().deleteById(config.id) }
                snapshot.watchedKeys.forEach { key -> database.watchedKeyDao().deleteWithOwnedData(key.id) }
                snapshot.walletGroups.forEach { group -> database.walletGroupDao().deleteById(group.id) }
            }
            throw failure
        }
    }

    private fun validate(snapshot: BackupSnapshot) {
        if (snapshot.watchedKeys.map { it.id }.toSet().size != snapshot.watchedKeys.size || snapshot.walletGroups.map { it.id }.toSet().size != snapshot.walletGroups.size || snapshot.serverConfigs.map { it.id }.toSet().size != snapshot.serverConfigs.size) throw BackupRestoreException()
        val groups = snapshot.walletGroups.map { it.id }.toSet()
        snapshot.walletGroups.forEach { require(it.label.length <= 200 && it.dustThresholdSats >= 0) { "invalid" }; ScriptType.valueOf(it.preferredReceiveScriptType); UtxoView.valueOf(it.utxoView) }
        val ownedAddresses = mutableSetOf<String>()
        snapshot.watchedKeys.forEach { key ->
            if (key.label.length > 200 || key.dustThresholdSats < 0 || (key.walletGroupId != null && key.walletGroupId !in groups)) throw BackupRestoreException()
            val target = WatchTargetType.valueOf(key.targetType); val script = ScriptType.valueOf(key.scriptType); UtxoView.valueOf(key.utxoView)
            if (target == WatchTargetType.SINGLE_ADDRESS) { val address = parseSingleAddress(key.keyMaterial); if (!ownedAddresses.add(address)) throw BackupRestoreException() }
            else parseWatchedKey(key.keyMaterial, script.toCryptoType())
        }
        snapshot.labels.forEach { if (it.text.length > 500 || it.referenceId.isBlank()) throw BackupRestoreException(); LabelReferenceType.valueOf(it.referenceType) }
        snapshot.serverConfigs.forEach { if (it.host.isBlank() || it.port !in 1..65535 || it.protocol.lowercase() !in setOf("electrum", "esplora")) throw BackupRestoreException() }
        val settings = snapshot.settings
        if (settings.fiatCurrency !in FIAT_CURRENCIES || runCatching { UtxoView.valueOf(settings.utxoView); ExplorerPreset.valueOf(settings.explorerPreset) }.isFailure) throw BackupRestoreException()
    }
}

private fun SecurityPreferences.toBackupSettings() = BackupSettings(showBalanceChart, utxoView.name, fiatCurrency, explorerPreset.name, torEnabled, offlineMode, streetModeEnabled)
private fun SecurityPreferences.withBackupSettings(settings: BackupSettings) = copy(
    showBalanceChart = settings.showBalanceChart,
    utxoView = UtxoView.valueOf(settings.utxoView),
    fiatCurrency = settings.fiatCurrency,
    explorerPreset = ExplorerPreset.valueOf(settings.explorerPreset),
    torEnabled = settings.torEnabled,
    offlineMode = settings.offlineMode,
    streetModeEnabled = settings.streetModeEnabled,
)
