package app.glance.wallet

/** Applies the restored network policy before starting its first reconciliation. */
internal suspend fun requestRestoredWalletSync(
    settings: BackupSettings,
    configureNetwork: suspend (BackupSettings) -> Unit,
    routeState: () -> TorState,
    requestSync: suspend (torEnabled: Boolean, torState: TorState, offlineMode: Boolean) -> Unit,
) {
    configureNetwork(settings)
    requestSync(settings.torEnabled, routeState(), settings.offlineMode)
}
