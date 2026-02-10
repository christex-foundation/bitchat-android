# BitChat + Solana Android MVP — Implementation Plan

**Version:** 1.0  
**Date:** February 2026  
**Platform:** Android (Kotlin + Jetpack Compose)  
**Approach:** Offline Transaction Creation + Delayed Broadcasting (Approach 1)  
**Base:** [bitchat-android](https://github.com/permissionlesstech/bitchat-android)  
**Task checklist:** [TASK_COMPLETION.md](TASK_COMPLETION.md)

---

## Executive Summary

Integrate Solana transfers into BitChat Android so users can:

- Create and sign Solana transactions **offline**
- Transfer signed transactions via **Bluetooth mesh**
- **Queue** transactions and broadcast when internet is available
- Confirm on-chain settlement and notify users

**Value:** Only Android solution for offline Solana transaction creation with P2P transfer over Bluetooth mesh.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Technology Stack](#technology-stack)
3. [Implementation Phases](#implementation-phases)
4. [File Structure](#file-structure)
5. [Success Criteria by Phase](#success-criteria-by-phase)
6. [Risks & Mitigations](#risks--mitigations)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Jetpack Compose)                │
│  WalletScreen │ SendTransactionScreen │ ReceiveScreen       │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                  ViewModel Layer (MVVM)                      │
│  WalletViewModel │ SendTransactionViewModel │ ChatViewModel  │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                           │
│  SolanaWalletService │ TransactionProtocolService │ BroadcastService │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│              Existing BitChat Infrastructure                 │
│  Bluetooth Mesh │ EncryptionService │ Binary Protocol       │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                    External Systems                          │
│  Solana RPC │ EncryptedSharedPreferences │ Bluetooth LE      │
└─────────────────────────────────────────────────────────────┘
```

**Principles:** MVVM, Kotlin Coroutines + Flow, Hilt DI, protocol extension (no breaking changes), security-first (Keystore + EncryptedSharedPreferences).

---

## Technology Stack

| Area | Choice |
|------|--------|
| Min SDK | 26 (align with BitChat) |
| Target/Compile SDK | 34 |
| Kotlin | 1.9+ |
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| Persistence | Room (queued tx), EncryptedSharedPreferences (keys) |
| Crypto | BouncyCastle, Android Keystore, Ed25519 |
| Solana | Solana Kotlin SDK or Solana4k (TBD by availability) |
| Network | OkHttp + Retrofit for RPC |

**New dependencies (to add):** Solana SDK, kbase58, Ed25519 (e.g. eddsa), BIP39 (e.g. kotlin-bip39), ZXing (QR), Hilt, Room (if not present).

---

## Implementation Phases

### Phase 0: Setup & Foundation — Week 1

| Task | Description | Deliverable |
|------|-------------|-------------|
| **0.1** | Create feature branch `feature/solana-integration` | Branch + PR template |
| **0.2** | Add Solana and supporting deps to `app/build.gradle.kts` (and `gradle/libs.versions.toml` if used) | Updated build files |
| **0.3** | Add Hilt: `@HiltAndroidApp` in Application, plugin, `SolanaModule` | DI wiring |
| **0.4** | Define Room DB: `SolanaDatabase`, `WalletEntity`, `QueuedTransactionEntity`, DAOs, TypeConverters | `data/local/` schema |
| **0.5** | Configure Devnet RPC endpoint (config/build flag or BuildConfig) | RPC config |

**Phase 0 deliverables:** Feature branch, deps, Hilt, Room schema, Devnet RPC verified.

---

### Phase 1: Wallet Foundation — Week 2

| Task | Description | Deliverable |
|------|-------------|-------------|
| **1.1** | **SolanaRpcService** — JSON-RPC client: `getBalance`, `getLatestBlockhash`, `sendTransaction`, `getSignatureStatuses` (poll for confirm) | `solana/SolanaRpcService.kt` |
| **1.2** | **SolanaWalletService** — Generate keypair (BIP39 → seed → BIP44 m/44'/501'/0'/0'), EncryptedSharedPreferences + Keystore for seed/key, load/sign/export | `solana/SolanaWalletService.kt` |
| **1.3** | **WalletViewModel** — Expose `walletState`, `balance`; init load, createWallet, refreshBalance, exportSeedPhrase | `viewmodels/WalletViewModel.kt` |
| **1.4** | **WalletScreen** — Onboarding (create/import), Dashboard (balance, address, copy, Send/Receive), Error state | `ui/screens/WalletScreen.kt` |
| **1.5** | **ReceiveScreen** — Show address + QR (ZXing), copy button | `ui/screens/ReceiveScreen.kt` |
| **1.6** | Unit tests: wallet generation, key storage, balance fetch | `*Test.kt` |

**Phase 1 deliverables:** Wallet create/import, secure storage, address + balance UI, receive flow, tests.

---

### Phase 2: Transaction Creation — Week 3

| Task | Description | Deliverable |
|------|-------------|-------------|
| **2.1** | **TransactionBuilder** — Build SOL transfer (blockhash, fee payer, system program transfer); optional priority fee; serialize to bytes | `solana/TransactionBuilder.kt` |
| **2.2** | **SendTransactionViewModel** — Create tx (amount, recipient, priority fee), sign via WalletService, hand off to protocol/broadcast | `viewmodels/SendTransactionViewModel.kt` |
| **2.3** | **SendTransactionScreen** — Recipient (manual + “Select BitChat Peer”), amount, priority fee toggle, review & confirm dialog | `ui/screens/SendTransactionScreen.kt` |
| **2.4** | **PeerSelectionScreen** (or bottom sheet) — List BitChat peers; select peer → resolve to Solana address (mapping TBD) | `ui/screens/PeerSelectionScreen.kt` or equivalent |
| **2.5** | Unit tests: transaction build, signing, serialization | `*Test.kt` |

**Phase 2 deliverables:** Create + sign SOL transfer, send UI, peer selection, serialized tx for mesh.

---

### Phase 3: Protocol Extension — Week 4

| Task | Description | Deliverable |
|------|-------------|-------------|
| **3.1** | Extend **BinaryProtocol** — New message types: `0x30` Solana Tx Packet, `0x31` Delivery ACK, `0x32` Broadcast Confirmation, `0x33` Status Update | `BinaryProtocol.kt` + packet models |
| **3.2** | **TransactionProtocolService** — Encode/decode `SolanaTransactionPacket` (version, type, serializedTx, sender/recipient, amount, timestamp, flags, signature) | `solana/TransactionProtocolService.kt` |
| **3.3** | Integrate with **BluetoothMeshService** — Register handlers for new types; send/receive tx packets over mesh | `BluetoothMeshService.kt` (or equivalent) |
| **3.4** | Wire ChatViewModel/UI — Show tx status in chat; send/receive tx as special message type | ChatViewModel + UI |

**Phase 3 deliverables:** Tx packet format, protocol service, mesh integration, chat UX for tx.

---

### Phase 4: Broadcast Queue — Week 5

| Task | Description | Deliverable |
|------|-------------|-------------|
| **4.1** | **BroadcastQueue** — Enqueue signed tx (Room), TTL (e.g. 24h), retry count; dequeue when online | `solana/BroadcastQueue.kt` + TransactionDao |
| **4.2** | **NetworkMonitor** — ConnectivityManager (or similar) to detect internet; expose Flow/State | `solana/NetworkMonitor.kt` |
| **4.3** | **BroadcastService** — When online: get recent blockhash (refresh if needed), submit via RPC, poll confirmation; exponential backoff on failure; emit status (e.g. via protocol) | `solana/BroadcastService.kt` |
| **4.4** | Background work — WorkManager or foreground service to process queue when app in background (optional for MVP) | Worker or Service |

**Phase 4 deliverables:** Persistent queue, network awareness, broadcast + confirm, status updates.

---

### Phase 5: Testing & Polish — Week 6

| Task | Description | Deliverable |
|------|-------------|-------------|
| **5.1** | Unit tests — Wallet, RPC, TransactionBuilder, Protocol, BroadcastQueue | Coverage for critical paths |
| **5.2** | UI tests — Wallet onboarding, send flow, receive (Compose Test) | `*Test.kt` |
| **5.3** | Integration test — Create tx → queue → (mock) broadcast → confirm | Optional |
| **5.4** | Manual testing on devices — Devnet SOL, mesh transfer, offline → online | Test report |
| **5.5** | Strings, accessibility, error messages, basic analytics if needed | Ready for beta |

**Phase 5 deliverables:** Test suite, manual sign-off, strings and UX polish.

---

## File Structure

```
app/src/main/kotlin/com/bitchat/android/
├── BitchatApplication.kt              # Add @HiltAndroidApp
│
├── solana/
│   ├── SolanaWalletService.kt
│   ├── SolanaRpcService.kt
│   ├── TransactionBuilder.kt
│   ├── TransactionProtocolService.kt
│   ├── BroadcastService.kt
│   ├── BroadcastQueue.kt
│   └── NetworkMonitor.kt
│
├── data/
│   ├── local/
│   │   ├── SolanaDatabase.kt
│   │   ├── WalletDao.kt
│   │   ├── TransactionDao.kt
│   │   └── entities/
│   │       ├── WalletEntity.kt
│   │       └── QueuedTransactionEntity.kt
│   └── models/
│       ├── SolanaTransactionPacket.kt
│       └── TransactionStatus.kt
│
├── ui/
│   ├── screens/
│   │   ├── WalletScreen.kt
│   │   ├── SendTransactionScreen.kt
│   │   ├── ReceiveScreen.kt
│   │   ├── TransactionHistoryScreen.kt
│   │   └── PeerSelectionScreen.kt (or component)
│   └── components/
│       ├── BalanceCard.kt
│       └── TransactionListItem.kt
│
├── viewmodels/
│   ├── WalletViewModel.kt
│   └── SendTransactionViewModel.kt
│
├── di/
│   ├── SolanaModule.kt
│   └── DatabaseModule.kt (or merge into SolanaModule)
│
├── BluetoothMeshService.kt            # Existing — extend for tx types
├── EncryptionService.kt               # Existing
├── BinaryProtocol.kt                  # Existing — extend
└── ChatViewModel.kt                   # Existing — integrate tx status
```

---

## Success Criteria by Phase

| Phase | Success Criteria |
|-------|-------------------|
| **0** | App builds with new deps; Hilt and Room run; Devnet RPC responds. |
| **1** | New wallet created and stored securely; address and balance shown; copy and receive (QR) work; seed export works. |
| **2** | SOL transfer can be created, signed, and serialized; send UI and peer selection work. |
| **3** | Tx packets sent/received over mesh; packet format matches spec; chat shows tx status. |
| **4** | Queued tx persist and broadcast when online; confirmation and status updates work. |
| **5** | Unit + UI tests pass; manual Devnet + mesh flow verified; ready for beta. |

---

## Protocol Additions (Reference)

- **0x30** — Solana Transaction Packet  
- **0x31** — Transaction Delivery Acknowledgment  
- **0x32** — Transaction Broadcast Confirmation  
- **0x33** — Transaction Status Update  

Packet fields (conceptual): version, transactionType, serializedTransaction, senderAddress, recipientAddress, amount (lamports), timestamp, flags, signature (64 bytes).

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Solana Kotlin SDK maturity | Pin version; have fallback to direct JSON-RPC + local serialization/signing. |
| Ed25519 / key derivation | Use BouncyCastle or dedicated lib (e.g. TweetNaCl); validate against Solana CLI. |
| Blockhash expiry (~60–90s) | Always refresh blockhash at broadcast time; mark tx that need refresh in queue. |
| Mesh packet size limits | Chunk large serialized tx or use compact encoding; document max size. |
| Key backup / loss | Encourage seed export and secure backup; document recovery flow. |

---

## References

- Full MVP spec (this document’s source): BitChat + Solana Android MVP Implementation Plan v1.0 (February 2026).
- Design context: `docs/design/solana-integration.md`.
- Base repo: https://github.com/permissionlesstech/bitchat-android.

---

*Implementation plan for BitChat + Solana Android MVP — Offline tx creation + Bluetooth mesh + delayed broadcast.*
