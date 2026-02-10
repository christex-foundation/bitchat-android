# BitChat + Solana Android MVP — Task Completion Checklist

Track implementation progress here. Mark a task done by changing `[ ]` to `[x]`.

**Implementation plan:** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)  
**Legend:** `[ ]` = not started, `[x]` = complete

---

## Phase 0: Setup & Foundation — Week 1

- [x] **0.1** Create feature branch `feature/solana-integration` — Branch + PR template
- [x] **0.2** Add Solana and supporting deps to `app/build.gradle.kts` (and `gradle/libs.versions.toml` if used) — Updated build files
- [x] **0.3** Add Hilt: `@HiltAndroidApp` in Application, plugin, `SolanaModule` — DI wiring (deferred: Kotlin 2.2/KSP; services via BitchatApplication getters)
- [x] **0.4** Define Room DB: `SolanaDatabase`, `WalletEntity`, `QueuedTransactionEntity`, DAOs, TypeConverters — `data/local/` schema (SharedPreferences-backed; Room-ready entities)
- [x] **0.5** Configure Devnet RPC endpoint (config/build flag or BuildConfig) — RPC config

**Phase 0 deliverables:** Feature branch, deps, Hilt, Room schema, Devnet RPC verified.

---

## Phase 1: Wallet Foundation — Week 2

- [x] **1.1** **SolanaRpcService** — JSON-RPC client: `getBalance`, `getLatestBlockhash`, `sendTransaction`, `getSignatureStatuses` (poll for confirm) — `solana/SolanaRpcService.kt`
- [x] **1.2** **SolanaWalletService** — Generate keypair (BIP39 → seed → BIP44 m/44'/501'/0'/0'), EncryptedSharedPreferences + Keystore for seed/key, load/sign/export — `solana/SolanaWalletService.kt`
- [x] **1.3** **WalletViewModel** — Expose `walletState`, `balance`; init load, createWallet, refreshBalance, exportSeedPhrase — `viewmodels/WalletViewModel.kt`
- [x] **1.4** **WalletScreen** — Onboarding (create/import), Dashboard (balance, address, copy, Send/Receive), Error state — `ui/screens/WalletScreen.kt`
- [x] **1.5** **ReceiveScreen** — Show address + QR (ZXing), copy button — `ui/screens/ReceiveScreen.kt`
- [ ] **1.6** Unit tests: wallet generation, key storage, balance fetch — `*Test.kt`

**Phase 1 deliverables:** Wallet create/import, secure storage, address + balance UI, receive flow, tests.

---

## Phase 2: Transaction Creation — Week 3

- [x] **2.1** **TransactionBuilder** — Build SOL transfer (blockhash, fee payer, system program transfer); optional priority fee; serialize to bytes — `solana/TransactionBuilder.kt`
- [x] **2.2** **SendTransactionViewModel** — Create tx (amount, recipient, priority fee), sign via WalletService, hand off to protocol/broadcast — `viewmodels/SendTransactionViewModel.kt`
- [x] **2.3** **SendTransactionScreen** — Recipient (manual + “Select BitChat Peer”), amount, priority fee toggle, review & confirm dialog — `ui/screens/SendTransactionScreen.kt`
- [x] **2.4** **PeerSelectionScreen** (or bottom sheet) — List BitChat peers; select peer → resolve to Solana address (mapping TBD) — `ui/screens/PeerSelectionScreen.kt` or equivalent
- [ ] **2.5** Unit tests: transaction build, signing, serialization — `*Test.kt`

**Phase 2 deliverables:** Create + sign SOL transfer, send UI, peer selection, serialized tx for mesh.

---

## Phase 3: Protocol Extension — Week 4

- [x] **3.1** Extend **BinaryProtocol** — New message types: `0x30` Solana Tx Packet, `0x31` Delivery ACK, `0x32` Broadcast Confirmation, `0x33` Status Update — `BinaryProtocol.kt` + packet models
- [x] **3.2** **TransactionProtocolService** — Encode/decode `SolanaTransactionPacket` (version, type, serializedTx, sender/recipient, amount, timestamp, flags, signature) — `solana/TransactionProtocolService.kt`
- [x] **3.3** Integrate with **BluetoothMeshService** — Register handlers for new types; send/receive tx packets over mesh — `BluetoothMeshService.kt` (or equivalent)
- [x] **3.4** Wire ChatViewModel/UI — Show tx status in chat; send/receive tx as special message type — ChatViewModel + UI

**Phase 3 deliverables:** Tx packet format, protocol service, mesh integration, chat UX for tx.

---

## Phase 4: Broadcast Queue — Week 5

- [x] **4.1** **BroadcastQueue** — Enqueue signed tx (Room), TTL (e.g. 24h), retry count; dequeue when online — `solana/BroadcastQueue.kt` + TransactionDao
- [x] **4.2** **NetworkMonitor** — ConnectivityManager (or similar) to detect internet; expose Flow/State — `solana/NetworkMonitor.kt`
- [x] **4.3** **BroadcastService** — When online: get recent blockhash (refresh if needed), submit via RPC, poll confirmation; exponential backoff on failure; emit status (e.g. via protocol) — `solana/BroadcastService.kt`
- [x] **4.4** Background work — WorkManager or foreground service to process queue when app in background (optional for MVP) — Worker or Service (BroadcastService runs in app scope; optional WorkManager deferred)

**Phase 4 deliverables:** Persistent queue, network awareness, broadcast + confirm, status updates.

---

## Phase 5: Testing & Polish — Week 6

- [ ] **5.1** Unit tests — Wallet, RPC, TransactionBuilder, Protocol, BroadcastQueue — Coverage for critical paths
- [ ] **5.2** UI tests — Wallet onboarding, send flow, receive (Compose Test) — `*Test.kt`
- [ ] **5.3** Integration test — Create tx → queue → (mock) broadcast → confirm (optional) — Optional
- [ ] **5.4** Manual testing on devices — Devnet SOL, mesh transfer, offline → online — Test report
- [ ] **5.5** Strings, accessibility, error messages, basic analytics if needed — Ready for beta

**Phase 5 deliverables:** Test suite, manual sign-off, strings and UX polish.
