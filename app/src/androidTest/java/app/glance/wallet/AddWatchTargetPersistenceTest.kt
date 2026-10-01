package app.glance.wallet

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glance.wallet.core.crypto.ScriptType as CryptoScriptType
import app.glance.wallet.core.crypto.parseWatchedKey
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.ScriptType as DataScriptType
import app.glance.wallet.core.data.db.AddressChain
import app.glance.wallet.core.data.db.LabelEntity
import app.glance.wallet.core.data.db.LabelReferenceType
import app.glance.wallet.core.data.db.ServerConfigEntity
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.UtxoView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddWatchTargetPersistenceTest {
    @Test
    fun reimportingTheSameFormatsDoesNotCreateDuplicates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "hd", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))
            persistWatchTargets(database, "again", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))

            val keys = database.watchedKeyDao().observeAll().first()
            assertEquals(2, keys.size)
            assertEquals(
                setOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT),
                keys.map { it.scriptType }.toSet(),
            )
            assertFalse(keys.any { it.label == "again" })
        } finally {
            database.close()
        }
    }

    @Test
    fun aGroupedWalletCannotGainAnotherFormatAfterImport() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))

            val failure = runCatching {
                persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.SEGWIT_COMPAT))
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("formats can only be chosen during import") == true)
            assertEquals(2, database.watchedKeyDao().observeAll().first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun importedWalletsUseTheLatestChosenUtxoViewIndividually() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "hd", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT), UtxoView.LIST)

            val keys = database.watchedKeyDao().observeAll().first()
            assertTrue(keys.all { it.utxoView == UtxoView.LIST.name })
            database.watchedKeyDao().setUtxoView(keys.first().id, UtxoView.BUBBLES.name)
            assertEquals(UtxoView.BUBBLES.name, database.watchedKeyDao().findById(keys.first().id)?.utxoView)
            assertEquals(UtxoView.LIST.name, database.watchedKeyDao().findById(keys.last().id)?.utxoView)
        } finally {
            database.close()
        }
    }

    @Test
    fun importingMultipleDiscoveredFormatsCreatesOneSharedWalletGroup() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(
                database,
                "Savings",
                XPUB,
                listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT),
            )

            val groups = database.walletGroupDao().observeAll().first()
            val keys = database.watchedKeyDao().observeAll().first()
            assertEquals(1, groups.size)
            assertEquals("Savings", groups.single().label)
            assertEquals(DataScriptType.NATIVE_SEGWIT, groups.single().preferredReceiveScriptType)
            assertEquals(setOf(groups.single().id), keys.mapNotNull { it.walletGroupId }.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun groupPreferencesAndFormatRemovalDoNotAffectRemainingFormats() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            persistWatchTargets(database, "Savings", XPUB, listOf(DataScriptType.LEGACY, DataScriptType.NATIVE_SEGWIT))
            val group = database.walletGroupDao().observeAll().first().single()
            val keys = database.watchedKeyDao().forWalletGroup(group.id)

            database.walletGroupDao().setPreferredReceiveScriptType(group.id, DataScriptType.LEGACY)
            database.watchedKeyDao().deleteWithOwnedData(keys.first { it.scriptType == DataScriptType.NATIVE_SEGWIT }.id)

            assertEquals(DataScriptType.LEGACY, database.walletGroupDao().observeAll().first().single().preferredReceiveScriptType)
            assertEquals(listOf(DataScriptType.LEGACY), database.watchedKeyDao().forWalletGroup(group.id).map { it.scriptType })
        } finally {
            database.close()
        }
    }

    @Test
    fun importingAnHdKeyRejectsAnAddressAlreadyTrackedInItsInitialWindow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GlanceDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val existingAddress = parseWatchedKey(XPUB, CryptoScriptType.NATIVE_SEGWIT).derive(0, 0).address
            persistWatchTarget(database, "fixed", existingAddress, null)

            val failure = runCatching {
                persistWatchTarget(database, "hd", XPUB, DataScriptType.NATIVE_SEGWIT)
            }.exceptionOrNull()

            assertTrue(failure?.message?.contains("already being tracked") == true)
            assertEquals(1, database.watchedKeyDao().observeAll().first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun backupRoundTripRetainsASingleWatchedAddress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val destination = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val preferences = SecurityPreferencesStore.forTesting(context, "single-address-backup-round-trip")
        try {
            persistWatchTarget(source, "Cold storage", SINGLE_ADDRESS, null)

            val encrypted = BackupCodec.encrypt(
                BackupRepository(source, preferences).snapshot(),
                "backup passphrase".toCharArray(),
                DeterministicBackupRandom,
            )
            val restoredSnapshot = BackupCodec.decrypt(encrypted, "backup passphrase".toCharArray())
            assertEquals(1, restoredSnapshot.watchedKeys.size)
            assertEquals("SINGLE_ADDRESS", restoredSnapshot.watchedKeys.single().targetType)

            BackupRepository(destination, preferences).restore(restoredSnapshot)

            val restoredTarget = destination.watchedKeyDao().observeAll().first().single()
            assertEquals(SINGLE_ADDRESS, restoredTarget.keyMaterial)
            assertEquals(DataScriptType.NATIVE_SEGWIT, restoredTarget.scriptType)
            assertEquals(
                SINGLE_ADDRESS,
                destination.derivedAddressDao().find(restoredTarget.id, AddressChain.EXTERNAL, 0)?.address,
            )
        } finally {
            preferences.wipe()
            source.close()
            destination.close()
        }
    }

    @Test
    fun backupRoundTripRestoresWatchedKeysLabelsServersAndSettingsIntoACleanInstall() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val destination = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val sourcePreferences = SecurityPreferencesStore.forTesting(context, "backup-round-trip-source")
        val destinationPreferences = SecurityPreferencesStore.forTesting(context, "backup-round-trip-destination")
        try {
            persistWatchTarget(source, "Cold storage", SINGLE_ADDRESS, null)
            source.labelDao().upsert(LabelEntity(LabelReferenceType.ADDRESS, SINGLE_ADDRESS, "Savings"))
            source.serverConfigDao().upsert(ServerConfigEntity("custom-electrum", "electrum", "node.example", 50002, true, true))
            sourcePreferences.update {
                it.copy(torEnabled = false, fiatCurrency = "EUR", utxoView = UtxoView.LIST, showBalanceChart = false, offlineMode = true, streetModeEnabled = true)
            }
            val expected = BackupRepository(source, sourcePreferences).snapshot()

            BackupRepository(destination, destinationPreferences).restore(expected, directConnectionConfirmed = true)

            assertEquals(expected, BackupRepository(destination, destinationPreferences).snapshot())
        } finally {
            sourcePreferences.wipe()
            destinationPreferences.wipe()
            source.close()
            destination.close()
        }
    }

    @Test
    fun restoreRollsBackImportedDatabaseStateWhenSettingsWriteFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val preferences = SecurityPreferencesStore.forTesting(context, "backup-restore-write-failure")
        try {
            val snapshot = BackupSnapshot(watchedKeys = listOf(BackupWatchedKey("fixed", "Cold storage", SINGLE_ADDRESS, "NATIVE_SEGWIT", "SINGLE_ADDRESS", null)))

            val failure = runCatching {
                BackupRepository(database, preferences, writeSettings = { throw IllegalStateException("write failed") }).restore(snapshot)
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(BackupRepository(database, preferences).canRestore())
        } finally {
            preferences.wipe()
            database.close()
        }
    }

    @Test
    fun restoreRollsBackImportedDatabaseStateWhenSettingsWriteIsCancelled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java).allowMainThreadQueries().build()
        val preferences = SecurityPreferencesStore.forTesting(context, "backup-restore-write-cancelled")
        try {
            val snapshot = BackupSnapshot(watchedKeys = listOf(BackupWatchedKey("fixed", "Cold storage", SINGLE_ADDRESS, "NATIVE_SEGWIT", "SINGLE_ADDRESS", null)))
            val settingsWriteStarted = CompletableDeferred<Unit>()
            val restore = async {
                BackupRepository(database, preferences, writeSettings = {
                    settingsWriteStarted.complete(Unit)
                    awaitCancellation()
                }).restore(snapshot)
            }

            settingsWriteStarted.await()
            restore.cancelAndJoin()

            assertTrue(BackupRepository(database, preferences).canRestore())
        } finally {
            preferences.wipe()
            database.close()
        }
    }

    private companion object {
        const val XPUB = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
        const val SINGLE_ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    }
}
