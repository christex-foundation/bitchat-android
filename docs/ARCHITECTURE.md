# BitChat Android - Architecture Overview

**Last Reviewed**: October 21, 2025
**Version**: 1.5.1

---

## Table of Contents

1. [Project Purpose & Overview](#project-purpose--overview)
2. [Tech Stack & Build System](#tech-stack--build-system)
3. [Architecture & Core Components](#architecture--core-components)
4. [Networking Layer](#networking-layer)
5. [Encryption & Security](#encryption--security)
6. [Data Storage & Persistence](#data-storage--persistence)
7. [User Interface](#user-interface)
8. [Message & Command System](#message--command-system)
9. [Onboarding & Permissions](#onboarding--permissions)
10. [Power Management](#power-management)
11. [Limitations & Known Issues](#limitations--known-issues)
12. [Key Files Reference](#key-files-reference)

---

## Project Purpose & Overview

**BitChat for Android** is a secure, decentralized, peer-to-peer messaging application that works over Bluetooth mesh networks with no internet requirement for mesh chats.

### Key Characteristics

- **Core Function**: Enables encrypted communication via Bluetooth Low Energy (BLE) mesh networking
- **No Servers/Accounts**: Decentralized architecture with no phone numbers, emails, or account registration
- **100% iOS Compatible**: Binary protocol-compatible with original iOS bitchat for cross-platform messaging
- **Geohash Channels**: Internet-based location channels connecting users by geographic area (optional)
- **Privacy-First**: End-to-end encryption, no persistent identifiers, ephemeral-by-default messages
- **Platform**: Android 8.0+ (API 26+), with modern UI using Jetpack Compose

---

## Tech Stack & Build System

### Language & Framework

- **Language**: 100% Kotlin with Android SDK
- **Target/Compile SDK**: API 34 (target) / API 35 (compile)
- **Min SDK**: API 26 (Android 8.0)
- **Kotlin Version**: 2.2.0
- **Java Compatibility**: Java 1.8

### Build System

- **Gradle**: 8.10.1 (Android Gradle Plugin)
- **Configuration Files**:
  - `app/build.gradle.kts` - App-level build config
  - `build.gradle.kts` - Root-level config
  - `settings.gradle.kts` - Project settings
  - `gradle/libs.versions.toml` - Centralized dependency versions

**Build Features**:
- Minification + resource shrinking enabled in release builds
- ProGuard rules configured
- Kotlin Compose plugin with compiler support
- Parcelize plugin for Parcelable support

### Core Dependencies

#### UI & Compose
- Compose BOM: 2025.06.01
- `androidx.compose.*` (Material Design 3, UI, Runtime, Icons)
- `androidx.activity:activity-compose:1.10.1`
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1`
- `androidx.navigation:navigation-compose:2.9.1`

#### Cryptography
- `org.bouncycastle:bcprov-jdk15on:1.70` - Asymmetric crypto (X25519, Ed25519, AES-GCM)
- `com.google.crypto.tink:tink-android:1.10.0` - TINK cryptographic library
- Custom Noise protocol implementation for session encryption

#### Networking & BLE
- `no.nordicsemi.android:ble:2.6.1` - Nordic BLE library for GATT operations
- `com.squareup.okhttp3:okhttp:4.12.0` - HTTP client for Nostr relay connections
- `org.torproject:tor-android-binary:0.4.4.6` - Bundled Tor client
- `com.google.android.gms:play-services-location:21.3.0` - Location services

#### Data & Storage
- `androidx.security:security-crypto:1.1.0-beta01` - EncryptedSharedPreferences support for identity keys
- `com.google.code.gson:gson:2.13.1` - JSON serialization
- Plain SharedPreferences for channel/favorites state managed by `DataManager`

#### Coroutines & Async
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` - Async operations

#### Permissions
- `com.google.accompanist:accompanist-permissions:0.37.3` - Permission handling UI

---

## Architecture & Core Components

### High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│         Jetpack Compose UI Layer               │
│   (ChatScreen.kt, InputComponents, etc.)      │
└─────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────┐
│      ChatViewModel (MVVM Coordinator)          │
│  - Manages app state via ChatState             │
│  - Delegates to specialized managers           │
└─────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────┐
│      Business Logic Managers (300+ lines each) │
│  • MessageManager - Message lifecycle         │
│  • ChannelManager - Channel management        │
│  • PrivateChatManager - P2P messaging        │
│  • CommandProcessor - IRC-style commands     │
│  • GeohashViewModel - Location-based chats   │
│  • NotificationManager - Message alerts      │
│  • MediaSendingManager - File transfers      │
│  • MessageRouter - Mesh/Nostr routing bridge │
│  • ConversationAliasResolver - Chat merging  │
│  • MeshDelegateHandler - Mesh callbacks      │
└─────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────┐
│     BluetoothMeshService (Coordinator)         │
│  Orchestrates 8+ component managers:          │
│  • PeerManager - Peer lifecycle & discovery  │
│  • SecurityManager - Encryption & handshakes │
│  • BluetoothConnectionManager - BLE ops     │
│  • MessageHandler - Packet processing       │
│  • FragmentManager - Message reassembly    │
│  • StoreForwardManager - Offline caching   │
│  • PacketProcessor - Packet routing        │
│  • GossipSyncManager - Sync protocol       │
└─────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────┐
│     Encryption Layer (Noise Protocol)          │
│  • NoiseEncryptionService - Session mgmt    │
│  • EncryptionService - Public API           │
│  • NoiseSessionManager - Per-peer sessions  │
│  • Noise Protocol (southernstorm impl)     │
└─────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────┐
│     BLE Physical Layer                         │
│  • BluetoothGattServerManager (peripheral)    │
│  • BluetoothGattClientManager (central)      │
│  • BluetoothConnectionTracker (state mgmt)   │
│  • BluetoothPacketBroadcaster (sending)      │
│  • PowerManager (battery optimization)      │
└─────────────────────────────────────────────────┘
```

### Application Entry Points

#### 1. BitchatApplication.kt
**Location**: `app/src/main/java/com/bitchat/android/BitchatApplication.kt`

**Responsibilities**:
- Application-level initialization
- Tor manager initialization (early for network privacy)
- Relay directory loading (Nostr relays from CSV assets)
- Favorites persistence service initialization
- Nostr identity bridge warmup
- Theme preference initialization
- Debug settings initialization

#### 2. MainActivity.kt (770 lines)
**Location**: `app/src/main/java/com/bitchat/android/MainActivity.kt`

**Responsibilities**:
- Main Activity - hosts all UI via Jetpack Compose
- Orchestrates onboarding flow (Bluetooth → Location → Battery optimization → Permissions)
- Creates and manages `BluetoothMeshService` (core mesh networking)
- Implements lifecycle awareness for app foreground/background state
- Handles notification intents for deep linking to private chats and geohash channels

**Key Methods**:
- `checkOnboardingStatus()` - Validates prerequisites
- `handleBluetoothEnabled/Disabled()` - Bluetooth state callbacks
- `checkLocationAndProceed()` - Location services validation
- `checkBatteryOptimizationAndProceed()` - Battery optimization check
- `initializeApp()` - Main app initialization after all checks pass
- `onResume/onPause()` - Foreground/background state management

#### 3. MainViewModel.kt
**Location**: `app/src/main/java/com/bitchat/android/MainViewModel.kt`

**Responsibilities**:
- Manages onboarding state flow
- Tracks Bluetooth, Location, Battery optimization states
- Exposes StateFlow for reactive UI updates
- Decouples onboarding logic from Activity lifecycle

---

## Networking Layer

### A. Bluetooth Mesh Networking

#### Primary Service: BluetoothMeshService (750+ lines)
**Location**: `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt`

**Dual Role Architecture**:
- **Central Role**: Scans for and connects to peripherals (other devices)
- **Peripheral Role**: Advertises services and accepts connections

#### Key Components

**1. BluetoothConnectionManager.kt** - Connection orchestrator
- Manages both server and client GATT managers
- Handles power management and connection tracking
- Implements adaptive scanning based on battery state
- Supports fragmentation for large packets

**2. BluetoothGattServerManager.kt** - Peripheral/Server
- Exposes GATT services for other devices to connect
- Uses Nordic BLE library for reliable operations
- Handles incoming data reception

**3. BluetoothGattClientManager.kt** - Central/Client
- Initiates connections to discovered peripherals
- Manages characteristic read/write/notify operations
- Handles connection retries and timeouts

**4. PeerManager.kt** - Peer lifecycle management
- Tracks online/offline peers
- Maintains peer nicknames and metadata
- Emits peer list updates via delegate

**5. SecurityManager.kt** - Noise handshake coordination
- Initiates and responds to Noise protocol handshakes
- Verifies peer identities via fingerprints
- Manages key exchange state

**6. StoreForwardManager.kt** - Message caching
- Caches messages for offline peers
- Sends cached messages when peers come online
- Handles message retention policies

### B. Binary Protocol

**Location**: `app/src/main/java/com/bitchat/android/protocol/BinaryProtocol.kt`

**Format**: Bitchat Binary Protocol v1/v2 (100% iOS compatible)

**Header Structure**:
```
Version (1) | Type (1) | TTL (1) | Timestamp (8) | Flags (1) | PayloadLength (2 or 4 bytes)
```

**Message Types**:
- `ANNOUNCE (0x01)` - Identity announcement
- `MESSAGE (0x02)` - User messages (private/broadcast)
- `LEAVE (0x03)` - Departure announcement
- `NOISE_HANDSHAKE (0x10)` - Noise protocol handshake
- `NOISE_ENCRYPTED (0x11)` - Encrypted transport message
- `FRAGMENT (0x20)` - Message fragmentation
- `REQUEST_SYNC (0x21)` - Gossip-based sync request
- `FILE_TRANSFER (0x22)` - File transfer packet

**Features**:
- Automatic compression for payloads >100 bytes (LZ4)
- TTL-based routing (max 7 hops)
- Signature support for message authenticity
- Backward compatible v1/v2 decoding

### C. Gossip-Based Synchronization (GCS)

**Documentation**: See [sync.md](./sync.md) for detailed protocol specification

**Protocol**: REQUEST_SYNC with Golomb-Coded Set (GCS) filters

**Purpose**: Ensure eventual consistency of public packets across mesh

**Behavior**:
- Every 30 seconds: broadcast REQUEST_SYNC to neighbors
- 5 seconds after peer connects: unicast sync request to that peer only
- Local-only operation (TTL=0, not relayed)

**Configuration** (via debug settings):
- Max packets per sync (default 100)
- GCS filter size (default 256 bytes, range 128-1024)
- Target false positive rate (default 1%)

**Implementation**:
- `GossipSyncManager` - Sync orchestrator
- `GCSFilter` - Golomb-Coded Set filter
- `PacketIdUtil` - Deterministic packet IDs

### D. File Transfer Protocol

**Documentation**: See [file_transfer.md](./file_transfer.md) for detailed protocol specification

**Supported Types**:
- Audio/Voice notes (AAC MP4, ~32 kbps)
- Images (JPEG, downscaled to 512 px longest edge)
- Generic files (experimental)

**Protocol Details**:
- Uses v2 BitchatPacket format (4-byte payload length for large files)
- TLV payload structure with file metadata
- Fragmentation handled at transport layer (~150 byte fragments)
- Progress tracking via `TransferProgressManager`
- Cancellation support mid-transfer

**Storage Locations**:
- Audio: `files/voicenotes/outgoing/`, `files/voicenotes/incoming/`
- Images: `files/images/outgoing/`, `files/images/incoming/`
- Other: `files/files/incoming/`

**Implementation**:
- `MediaSendingManager` - File send coordination
- `BitchatFilePacket` - File payload structure
- `VoiceRecorder` - Audio capture
- `ImageUtils` - Image processing

### E. Geohash/Location Channels

**Purpose**: Internet-based location-aware channels (Nostr-based)

**Components**:
- `LocationChannelManager.kt` - Channel lifecycle
- `GeohashViewModel.kt` - Location channel UI coordination
- `NostrTransport.kt` - Nostr relay communications
- `LocationNotesManager.kt` - Geohash-specific messaging

**Features**:
- Multi-precision geohash levels (2-7 digits)
- Real-time participant discovery
- Bookmark support for favorite geohashes
- Profile storage (NIP-05 identity)
- Supports "teleporting" to other geohashes

**Nostr Integration**:
- Connects to public Nostr relays (configured in `assets/nostr_relays.csv`)
- NIP-17 direct messages for DMs
- NIP-23 long-form content for message retention
- Proof-of-Work support (configurable difficulty)
- Tor integration for relay privacy

---

## Encryption & Security

### A. Noise Protocol Implementation

**Service**: NoiseEncryptionService.kt
**Location**: `app/src/main/java/com/bitchat/android/noise/NoiseEncryptionService.kt`

**Key Features**:
- **Static Identity Keys**: Persistent across sessions (stored in `SecureIdentityStateManager`)
- **Session Management**: Per-peer sessions with rekey limits
  - Rekey time limit: 1 hour (matches iOS)
  - Rekey message limit: 1000 messages
- **Channel Encryption**: Password-derived keys for channels (PBKDF2 + AES-256-GCM)

**Persistent Storage**:
- Static Noise public/private keys stored in encrypted SharedPreferences
- Identity fingerprint derived from SHA-256 of static public key (first 16 hex chars = peer ID)
- Ed25519 signing keys for message authenticity

### B. Ed25519 Signing

**Purpose**: Message authenticity verification

**Implementation**:
- BouncyCastle Ed25519 implementation
- Packets signed before broadcast (using `BitchatPacket.toBinaryDataForSigning()`)
- TTL excluded from signature (changes during relay)
- Stored in `EncryptedSharedPreferences`

### C. EncryptionService.kt (Facade)

**Location**: `app/src/main/java/com/bitchat/android/crypto/EncryptionService.kt`

**Public API**:
```kotlin
fun encrypt(data: ByteArray, peerID: String): ByteArray
fun decrypt(data: ByteArray, peerID: String): ByteArray
fun signData(data: ByteArray): ByteArray
fun verifySignature(sig: ByteArray, data: ByteArray, peerID: String): Boolean
fun hasEstablishedSession(peerID: String): Boolean
fun initiateHandshake(peerID: String): ByteArray?
fun getPeerFingerprint(peerID: String): String?
```

**Key Operations**:
- Wrapper around `NoiseEncryptionService`
- Manages Ed25519 signing keys
- Maintains established session cache
- Fingerprint-based peer identity verification

### D. Channel Encryption

**Algorithm**: PBKDF2 key derivation + AES-256-GCM

**Configuration**:
- 100,000 iterations (same as iOS)
- Channel name as salt
- 256-bit derived key

**Implementation**:
- `ChannelManager.deriveChannelKey()` - Key derivation
- `ChannelManager.decryptChannelMessage()` - Decryption
- `ChannelManager.sendEncryptedChannelMessage()` - stubbed; encrypted send path not yet active
- Password validation currently falls back to a TODO stub (see limitations)

---

## Data Storage & Persistence

### Storage Solution

- `DataManager` persists channels, favorites, blocked users, and passwords via app-private `SharedPreferences` (`bitchat_prefs.xml`). These entries are sandboxed but not additionally encrypted.
- `SecureIdentityStateManager` stores static Noise keys and Ed25519 signing keys inside `EncryptedSharedPreferences` (`bitchat_identity.xml`).
- Media payloads (voice notes, images, files) live under the app-specific `files/` directory as described below.

### Key Data Stored

#### 1. User Identity
- `nickname` - Auto-generated if not set (e.g., "anon1234")
- Encryption keys (static Noise keys, Ed25519 keys)

#### 2. Channels
- `joined_channels` - Set of channel names
- `password_protected_channels` - Set of protected channels
- `channel_creators` - JSON map of channel → creator peer ID
- Channel-specific keys and passwords
- Stored via `DataManager` in plain SharedPreferences

#### 3. Peers & Social
- `favorite_peers` - Set of favorited peer IDs
- `blocked_users` - Set of blocked peer IDs
- `peer_fingerprints` - Centralized fingerprint storage
- Stored via `DataManager` in plain SharedPreferences

#### 4. Preferences
- `last_geohash_channel` - Previously selected location channel
- `location_services_enabled` - User preference
- Theme settings
- Debug settings

#### 5. Files
- Messages stored in `ChatState` (LiveData, in-memory)
- Media files in app-specific file directories
- Conversation history: transient (ephemeral by default)

### Additional Encryption

- Identity material uses AndroidX `EncryptedSharedPreferences` with a `MasterKey` (AES-256-GCM).
- Channel metadata and passwords currently remain in plain SharedPreferences (see Limitations).

### File Structure

```
/data/data/com.bitchat.droid/
├── files/
│   ├── voicenotes/
│   │   ├── incoming/
│   │   └── outgoing/
│   ├── images/
│   │   ├── incoming/
│   │   └── outgoing/
│   └── files/
│       └── incoming/
├── shared_prefs/
│   ├── bitchat_prefs.xml (plain SharedPreferences)
│   └── bitchat_identity.xml (EncryptedSharedPreferences)
└── cache/
    └── temp files for MediaStore/FileProvider
```

---

## User Interface

### Main Screen: ChatScreen.kt (570 lines)
**Location**: `app/src/main/java/com/bitchat/android/ui/ChatScreen.kt`

**Architecture**: Component-based composition with Material Design 3

### Key Components

#### 1. ChatHeader
- Title (channel/peer name or "Bitchat")
- Peer counter (`N online`)
- Favorites toggle
- Menu button (about, settings, clear)

#### 2. MessageDisplay
- Scrollable message list
- System messages (join/leave events)
- Text messages with sender/timestamp
- Media messages (audio, images, files)
- Mention highlighting

#### 3. InputComponents
- Message input field with autocomplete
- Slash command suggestions (`/j`, `/m`, `/w`, `/block`, etc.)
- @ mention autocomplete
- Media buttons (image picker, voice record)
- Real-time waveform during recording

#### 4. SidebarComponents (Navigation Drawer)
- Channel list with unread indicators
- Online peers list with RSSI indicators
- Favorites section
- Direct message conversations

#### 5. Media UI
- **Voice Notes**: Waveform display with play/pause, seek-to-position support, cancel overlay
- **Images**: Block-reveal progress, fullscreen viewer, save to device
- **File Transfer Progress**: Fragment-level progress tracking

### Theme System

**Colors**: Terminal-inspired aesthetic (dark/light modes)
- Primary: Orange accent
- Backgrounds: Dark neutral
- Text: High contrast

**Material Design 3**: Full theming support with dynamic colors on Android 12+

---

## Message & Command System

### IRC-Style Commands

**Implementation**: CommandProcessor.kt
**Location**: `app/src/main/java/com/bitchat/android/ui/CommandProcessor.kt`

| Command | Alias | Usage | Function |
|---------|-------|-------|----------|
| `/j` | `/join` | `/j #channel [password]` | Join or create channel |
| `/m` | `/msg` | `/m @name [message]` | Start private message |
| `/w` | | `/w` | List online users |
| `/block` | | `/block [@name]` | Block or list blocked users |
| `/unblock` | | `/unblock @name` | Unblock user |
| `/pass` | | `/pass [password]` | Set channel password (owner only) |
| `/clear` | | `/clear` | Clear chat messages |
| `/channels` | | `/channels` | Show all discovered channels |
| `/transfer` | | `/transfer @name` | Planned: transfer channel ownership (not yet implemented) |
| `/save` | | `/save` | Planned: toggle message retention (not yet implemented) |
| `/hug`, `/slap` | | `/hug @name` | Action commands |

### Message Types

```kotlin
enum class BitchatMessageType {
    Message,    // Text
    Audio,      // Voice note
    Image,      // Image
    File        // Generic file
}
```

### Delivery Status

```kotlin
sealed class DeliveryStatus {
    object Sending
    object Sent
    data class Delivered(val to: String, val at: Date)
    data class Read(val by: String, val at: Date)
    data class Failed(val reason: String)
    data class PartiallyDelivered(val reached: Int, val total: Int)
}
```

---

## Onboarding & Permissions

### Onboarding Flow

```
Checking → Bluetooth Check → Location Check → Battery Optimization →
Permissions Explanation → Permission Requesting → App Initialization → Complete
```

### Permissions Requested

**From AndroidManifest.xml** (`app/src/main/AndroidManifest.xml`)

| Permission | Purpose | API Requirement |
|-----------|---------|-----------------|
| `BLUETOOTH` | BLE operations | API ≤ 30 only |
| `BLUETOOTH_ADMIN` | BLE control | API ≤ 30 only |
| `BLUETOOTH_ADVERTISE` | Advertise as peripheral | API 31+ |
| `BLUETOOTH_CONNECT` | Connect as central | API 31+ |
| `BLUETOOTH_SCAN` | BLE scanning | API 31+ |
| `ACCESS_FINE_LOCATION` | BLE scanning requirement | Required |
| `ACCESS_COARSE_LOCATION` | BLE scanning requirement | Required |
| `POST_NOTIFICATIONS` | Message alerts | API 33+ |
| `RECORD_AUDIO` | Voice note recording | Any |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | Media file access | API 33+ |
| `VIBRATE` | Haptic feedback | Any |
| `INTERNET` | Nostr/geohash relay | Any |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background operation | Requested but not required |

### Hardware Requirements

- Bluetooth LE (required)
- Android 8.0+ (API 26+)
- 2GB RAM recommended

---

## Power Management

### PowerManager.kt
**Location**: `app/src/main/java/com/bitchat/android/mesh/PowerManager.kt`

### Adaptive Power Modes

**1. Performance Mode** (Charging or >60% battery)
- Full BLE scanning
- All features enabled

**2. Balanced Mode** (30-60% battery)
- Default operation
- Reduced scan duty cycle

**3. Power Saver** (<30% battery)
- Minimal scanning
- Limited connections

**4. Ultra-Low Power** (<10% battery)
- Emergency mode
- Scanning paused

### Features

- Adaptive scanning intervals
- Connection limiting based on power state
- App lifecycle aware (foreground/background)

---

## Limitations & Known Issues

### Critical Issues Found in Code

#### 1. Channel Password Verification Not Implemented
**Location**: `ChannelManager.kt:122`

```kotlin
private fun verifyChannelPassword(channel: String, password: String): Boolean {
    // TODO: REMOVE THIS - FOR TESTING ONLY
    return true  // Always returns true - not implemented
}
```

⚠️ **CRITICAL**: Passwords are not actually verified!

#### 2. Mention Parsing Not Implemented
**Location**: `MessageHandler.kt`

```kotlin
mentions = null // TODO: Parse mentions if needed
```

Mentions field in messages not parsed from protocol.

#### 3. NIP-17 Geohash DMs Broken
**Location**: `GeohashPeopleList.kt`

```kotlin
// TODO: Re-enable when NIP-17 geohash DM issues are fixed
```

Known issue with Nostr direct message handling.

#### 4. Pending Connection Retry Logic
**Location**: `BluetoothGattClientManager.kt`

```kotlin
// TODO: needs testing
```

Connection retry mechanism needs testing.

#### 5. Adapter Name Configuration
**Location**: `BluetoothConnectionManager.kt`

```kotlin
// TODO: Make this configurable
```

Sets adapter name to 8-char peer ID without user control.

### Architectural Limitations

#### 1. Message Retention
- Default ephemeral (not persisted to disk)
- No built-in message history backup
- Limited to in-memory storage

#### 2. Scale Limitations
- Designed for small-to-medium groups (tested up to ~100 peers)
- No sharding or partitioning for large meshes
- Seen packet capacity configurable (default 500, max typically 1000)

#### 3. Relay Constraints
- TTL-based routing limited to 7 hops
- No sophisticated routing optimization
- Broadcast storms possible in dense networks

#### 4. BLE Physical Limitations
- MTU ~128-512 bytes (varies by device)
- Requires frequent fragmentation for files >64KB
- No QoS guarantees on BLE layer

#### 5. Security Considerations
- Noise sessions ephemeral (not persisted)
- No perfect forward secrecy per message (session-level only)
- Peer identity verified only after handshake
- No resistance to peer impersonation attacks (malicious peer can claim any ID)

#### 6. Geohash Implementation
- Relies on public Nostr relays (privacy concern)
- No end-to-end encryption for geohash messages by default
- Proof-of-Work computationally expensive on mobile

---

## Key Files Reference

### Core Entry Points

| File | Lines | Purpose |
|------|-------|---------|
| `MainActivity.kt` | 770 | Entry point, onboarding orchestration |
| `BitchatApplication.kt` | - | Application-level initialization |
| `MainViewModel.kt` | - | Onboarding state management |

### Networking Core

| File | Lines | Purpose |
|------|-------|---------|
| `mesh/BluetoothMeshService.kt` | 750+ | BLE mesh coordinator |
| `mesh/BluetoothConnectionManager.kt` | - | Connection orchestrator |
| `protocol/BinaryProtocol.kt` | - | Binary protocol encoding/decoding |

### Business Logic

| File | Purpose |
|------|---------|
| `ui/ChatViewModel.kt` | Main MVVM coordinator |
| `ui/MessageManager.kt` | Message lifecycle |
| `ui/ChannelManager.kt` | Channel management |
| `ui/PrivateChatManager.kt` | P2P messaging |
| `ui/CommandProcessor.kt` | IRC-style commands |
| `services/MessageRouter.kt` | Mesh ↔︎ Nostr delivery orchestrator |
| `services/ConversationAliasResolver.kt` | Conversation merging across transports |

### Encryption

| File | Purpose |
|------|---------|
| `crypto/EncryptionService.kt` | Crypto facade |
| `noise/NoiseEncryptionService.kt` | Noise protocol implementation |

### UI

| File | Lines | Purpose |
|------|-------|---------|
| `ui/ChatScreen.kt` | 570 | Main Compose UI |
| `ui/InputComponents.kt` | - | Input UI components |
| `ui/MessageComponents.kt` | - | Message display components |
| `ui/NotificationManager.kt` | - | Notification handling |

### Data

| File | Purpose |
|------|---------|
| `ui/DataManager.kt` | Persistence layer |
| `model/BitchatMessage.kt` | Message data model |

### Configuration

| File | Lines | Purpose |
|------|-------|---------|
| `app/build.gradle.kts` | 116 | Build configuration |
| `gradle/libs.versions.toml` | - | Centralized dependency versions |
| `app/src/main/AndroidManifest.xml` | 82 | App manifest |

### Documentation

| File | Purpose |
|------|---------|
| `README.md` | User-facing documentation |
| `docs/sync.md` | GCS sync protocol |
| `docs/file_transfer.md` | File transfer protocol |
| `docs/ANNOUNCEMENT_GOSSIP.md` | Announcement gossip |
| `docs/SOURCE_ROUTING.md` | Source routing |

---

## Development Patterns

### Design Patterns Used

1. **MVVM**: ChatViewModel + LiveData
2. **Component-Based Architecture**: Specialized managers (MessageManager, ChannelManager, etc.)
3. **Coordinator Pattern**: BluetoothMeshService coordinates multiple components
4. **Delegate Pattern**: Callbacks for event propagation
5. **Repository Pattern**: DataManager for persistence
6. **Factory Pattern**: ViewModelProvider.Factory for dependency injection
7. **State Management**: MutableLiveData + StateFlow
8. **Coroutines**: IO, Main, and Supervisor scopes

### Code Organization

```
src/main/java/com/bitchat/android/
├── crypto/           - Encryption services
├── features/         - Feature-specific code (voice, media)
├── geohash/          - Location channel logic
├── identity/         - Identity management
├── mesh/             - BLE networking (19 files)
├── model/            - Data models
├── net/              - Network utilities (Tor, HTTP)
├── noise/            - Noise protocol
├── nostr/            - Nostr/relay integration (26 files)
├── onboarding/       - Permission/setup flows
├── protocol/         - Binary protocol
├── services/         - Service layer
├── sync/             - GCS synchronization
├── ui/               - Compose UI (40 files)
├── util/             - Utilities
└── utils/            - Additional utilities
```

---

## Version Information

**Current Version**: 1.5.1 (versionCode: 26)
**Last Major Release**: 1.5.1 (October 2025)
**Min SDK**: API 26 (Android 8.0)
**Target SDK**: API 34

### Recent Changes

See [CHANGELOG.md](../CHANGELOG.md) for complete version history.

- **1.5.1** (Oct 2025): Suppress relay of reassembled packets (TTL=0), tablet landscape support
- **1.4.0** (Oct 2025): Debug settings UI fixes
- **0.7.2** (Jul 2025): Battery optimization screen improvements
- **0.7.1** (Jul 2025): Battery optimization management
- **0.7**: Bluetooth architecture refactoring, iOS compatibility fixes
- **0.6**: Channel passwords, favorites restoration, permissions overhaul

---

## Additional Resources

- **Main README**: [../README.md](../README.md)
- **GCS Sync Protocol**: [sync.md](./sync.md)
- **File Transfer Protocol**: [file_transfer.md](./file_transfer.md)
- **Source Routing**: [SOURCE_ROUTING.md](./SOURCE_ROUTING.md)
- **Announcement Gossip**: [ANNOUNCEMENT_GOSSIP.md](./ANNOUNCEMENT_GOSSIP.md)
- **Privacy Policy**: [../PRIVACY_POLICY.md](../PRIVACY_POLICY.md)
- **Changelog**: [../CHANGELOG.md](../CHANGELOG.md)

---

**Document Maintainers**: This document should be reviewed and updated with each major release or significant architectural change.
