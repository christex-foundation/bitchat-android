package com.bitchat.android

import android.app.Application
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.solana.BroadcastQueue
import com.bitchat.android.solana.BroadcastService
import com.bitchat.android.solana.NetworkMonitor
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.TorManager
import com.bitchat.android.solana.SolanaRpcService
import com.bitchat.android.solana.SolanaWalletService

/**
 * Main application class for bitchat Android.
 * Solana services provided via getters (Hilt deferred for Kotlin 2.2 compatibility).
 */
class BitchatApplication : Application() {

    val solanaRpcService: SolanaRpcService by lazy {
        SolanaRpcService(BuildConfig.SOLANA_DEVNET_RPC_URL)
    }
    val solanaWalletService: SolanaWalletService by lazy {
        SolanaWalletService(this)
    }
    val solanaDatabase: SolanaDatabase by lazy {
        SolanaDatabase(this)
    }
    val broadcastQueue: BroadcastQueue by lazy {
        BroadcastQueue(solanaDatabase.transactionDao())
    }
    private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }
    val broadcastService: BroadcastService by lazy {
        BroadcastService(solanaRpcService, broadcastQueue, networkMonitor)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Tor first so any early network goes over Tor
        try { TorManager.init(this) } catch (_: Exception) { }

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.bitchat.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)
        // Start Solana broadcast service (processes queued txs when online)
        try { broadcastService.start() } catch (_: Exception) { }

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
