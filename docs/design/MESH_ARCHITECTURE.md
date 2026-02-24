# BitChat Android Mesh Network Architecture - Comprehensive Summary

**Analysis Date:** October 25, 2025  
**Codebase:** bitchat-android  
**Project Branch:** feat/exit-relay  

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Current Mesh Network Implementation](#current-mesh-network-implementation)
3. [Transport Layers](#transport-layers)
4. [Retry and Delivery Mechanisms](#retry-and-delivery-mechanisms)
5. [Network Layer Abstractions](#network-layer-abstractions)
6. [Key Components](#key-components)
7. [Message Flow](#message-flow)
8. [File Paths and Key Files](#file-paths-and-key-files)

---

## Architecture Overview

BitChat implements a **Bluetooth Low Energy (BLE) mesh networking system** with iOS compatibility as a primary design constraint. The architecture uses a **component-based, modular design** organized around:

- **Peer Management** - Tracks connected devices and their identities
- **Security** - Noise Protocol encryption, signature verification, duplicate detection
- **Fragmentation** - Large message splitting for BLE MTU limits
- **Packet Processing** - Routing and handling different message types
- **Relay Management** - Adaptive packet forwarding through the mesh
- **Store & Forward** - Offline caching for unavailable peers

### Design Philosophy
- 100% iOS compatibility (exact same UUIDs, packet format, protocol logic)
- Component-based architecture for maintainability
- Coroutine-based async processing
- Per-peer actor-based serialization for thread safety
- Centralized delegate pattern for loose coupling

---

## Current Mesh Network Implementation

### 1. Core Architecture: Component-Based Service

**Main Service:** `BluetoothMeshService` (1,199 lines)
- Acts as a **coordinator** for smaller, focused components
- Each component handles specific responsibilities
- Delegates coordinate between components via callback interfaces

**Key Architecture Diagram:**
```
┌─────────────────────────────────────────────────────────────┐
│               BluetoothMeshService (Coordinator)             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Components (organized by responsibility):                   │
│  ├─ PeerManager          → Peer lifecycle & tracking         │
│  ├─ FragmentManager      → Message fragmentation             │
│  ├─ SecurityManager      → Crypto, duplicates, replays       │
│  ├─ StoreForwardManager  → Offline message caching          │
│  ├─ MessageHandler       → Message type processing           │
│  ├─ PacketProcessor      → Incoming packet routing           │
│  ├─ BluetoothConnectionManager → BLE connections (GATT)     │
│  ├─ GossipSyncManager    → Gossip-based sync protocol       │
│  └─ [Other components]                                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2. Protocol and Message Types

**Binary Protocol:** Custom binary format (13 bytes v1, 15 bytes v2)

**Message Types (Enum):**
```
0x01 - ANNOUNCE         (Peer discovery & identity announcement)
0x02 - MESSAGE          (User messages, broadcast or private)
0x03 - LEAVE            (Peer offline/channel leave)
0x10 - NOISE_HANDSHAKE  (Noise protocol key exchange)
0x11 - NOISE_ENCRYPTED  (Encrypted private transport)
0x20 - FRAGMENT         (Large packet fragmentation)
0x21 - REQUEST_SYNC     (Gossip sync request with GCS filters)
0x22 - FILE_TRANSFER    (Binary file/voice/media transfer)
0x30 - RELAY_REQUEST    (Solana relay request - NEW, exit relay feature)
0x31 - RELAY_RECEIPT    (Solana relay response - NEW, exit relay feature)
```

**Packet Structure:**
```
Header (13 bytes for v1, 15 bytes for v2):
  - Version: 1 byte
  - Type: 1 byte
  - TTL: 1 byte (decremented on relay)
  - Timestamp: 8 bytes (UInt64, big-endian)
  - Flags: 1 byte (hasRecipient, hasSignature, isCompressed)
  - PayloadLength: 2 bytes (v1) / 4 bytes (v2) (big-endian)

Variable sections:
  - SenderID: 8 bytes (fixed, from Noise identity fingerprint)
  - RecipientID: 8 bytes (optional, if hasRecipient flag set)
  - Payload: Variable length
  - Signature: 64 bytes (optional, Ed25519, if hasSignature flag set)
```

**Special Recipients:**
- Broadcast: `0xFF FF FF FF FF FF FF FF` (all bytes 0xFF)

### 3. Lifecycle Flow

```
┌────────────────────────────────────────────┐
│  User Action / External Trigger             │
└────────────────┬────────────────────────────┘
                 ↓
┌────────────────────────────────────────────┐
│  BluetoothConnectionManager                 │
│  ├─ BluetoothGattServerManager              │
│  │   └─ Advertises & accepts connections   │
│  └─ BluetoothGattClientManager              │
│      └─ Scans & connects to devices        │
└────────────────┬────────────────────────────┘
                 ↓
┌────────────────────────────────────────────┐
│  BluetoothPacketBroadcaster                 │
│  └─ Serialized via Actor pattern            │
│  └─ Fragments large packets                 │
└────────────────┬────────────────────────────┘
                 ↓
    ┌────────────────────────────────┐
    │  BLE Data Transfer (MTU ~251)  │
    └────────────────┬───────────────┘
                     ↓
        ┌────────────────────────────┐
        │  Incoming Packet           │
        │  (OnCharacteristicRead)    │
        └────────────┬───────────────┘
                     ↓
    ┌────────────────────────────────────┐
    │  PacketProcessor                   │
    │  ├─ Per-peer actor serialization   │
    │  └─ Validates & routes packets     │
    └────────────────┬───────────────────┘
                     ↓
    ┌────────────────────────────────────┐
    │  Handler (based on MessageType)    │
    │  ├─ Announce → PeerManager update  │
    │  ├─ Message → MessageHandler       │
    │  ├─ Handshake → SecurityManager    │
    │  ├─ Fragment → FragmentManager     │
    │  └─ etc.                           │
    └────────────────────────────────────┘
```

---

## Transport Layers

### Currently Implemented: Bluetooth Low Energy (BLE)

**BLE Stack Details:**

#### Peripheral Mode (Server)
- **Service:** Custom UUID for Bitchat
- **Characteristic:** Writable/Notifiable for bidirectional communication
- **Advertisement:** Includes Bitchat service UUID
- **MTU:** ~251 bytes (Android standard)
- **Implementation:** `BluetoothGattServerManager`
  - Sets up GATT server
  - Manages subscriptions & notifications
  - Enforces connection limits (configurable)

#### Central Mode (Client)
- **Scanning:** Filtered by Bitchat service UUID
- **Connection:** Initiates GATT connections to discovered devices
- **Duty Cycling:** Power-aware adaptive scanning via `PowerManager`
- **RSSI Monitoring:** Tracks signal strength for peer quality
- **Implementation:** `BluetoothGattClientManager`
  - Manages BLE scanning
  - Initiates/maintains client connections
  - Rate limits scan attempts (5s minimum between starts)

#### Power Management Integration
**PowerManager Component:**
- Adapts behavior based on:
  - App foreground/background state
  - Battery level
  - Screen state
- Implements duty cycling for battery efficiency
- Controlled via debug settings
- Delegates to connection manager for scan state changes

**Key Transition Points:**
```
┌─ WiFi/Cellular (NOT implemented yet)
│
├─ BLE Mesh (CURRENT)
│   ├─ Peripheral advertising
│   ├─ Central scanning
│   └─ GATT operations
│
└─ Other transports (Future)
    ├─ WiFi Direct
    ├─ NFC (Near Field)
    └─ Internet (Tor via NostrTransport)
```

### Related Non-Mesh Transports

The codebase already supports parallel transports for specific use cases:

1. **Tor/Internet via NostrTransport** (`/net/TorManager.kt`)
   - Used for Nostr protocol communication
   - Integrated with MessageRouter for hybrid mesh+Nostr routing

2. **Nostr Protocol** (`/nostr/NostrTransport.kt`)
   - Alternative for reliable internet delivery
   - Can route private messages when mesh unavailable

---

## Retry and Delivery Mechanisms

### 1. Duplicate Detection (Not Retries)
**SecurityManager** maintains:
- `processedMessages` - Set of message IDs already processed
- `messageTimestamps` - Timestamp map for cleanup
- **Strategy:** Dropping duplicates rather than retrying

**Duplicate Detection Logic:**
```kotlin
fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
    // Generate message ID from packet + sender
    val messageID = generateMessageID(packet, peerID)
    
    if (processedMessages.contains(messageID)) {
        Log.d(TAG, "Dropping duplicate packet: $messageID")
        return false  // Don't process duplicate
    }
    
    processedMessages.add(messageID)
    messageTimestamps[messageID] = currentTime
    return true  // Valid, new packet
}
```

**Replay Attack Protection:**
- Commented out: 5-minute timestamp window validation
- Currently: Relies on duplicate detection + TTL

### 2. Acknowledgment System

**Delivery Acknowledgments (Private Messages):**
- Message type: `NOISE_ENCRYPTED` with `NoisePayloadType.DELIVERED`
- **Triggered by:** Receiver of private message automatically sends ACK
- **Implementation:** `MessageHandler.sendDeliveryAck()`
- **Format:** Encrypted message containing message ID

**Read Receipts (Private Messages):**
- Message type: `NOISE_ENCRYPTED` with `NoisePayloadType.READ_RECEIPT`
- **Triggered by:** User reads message in UI
- **Sent to:** Original sender
- **Format:** Encrypted message containing message ID

**Status Classes (UI Layer):**
```kotlin
sealed class DeliveryStatus {
    object Sending : DeliveryStatus()
    object Sent : DeliveryStatus()
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()
    data class Read(val by: String, val at: Date) : DeliveryStatus()
    data class Failed(val reason: String) : DeliveryStatus()
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()
}
```

### 3. Store & Forward (Offline Delivery)

**StoreForwardManager** handles offline peer caching:

**Features:**
- Caches broadcast and private messages for offline peers
- Separate queues for:
  - Regular peers: up to 50 messages, 12-hour timeout
  - Favorite peers: up to 200 messages, indefinite (until delivered)
- Sends cached messages when peer comes online
- Tracks delivery status per message

**Caching Logic:**
```
IF message is directed to peer AND peer is offline:
  ├─ IF recipient is favorite: Add to favoriteMessageQueue[peerID]
  └─ ELSE: Add to messageCache with TTL
  
WHEN peer comes online:
  ├─ Check favorite queue
  ├─ Check regular cache
  ├─ Sort by timestamp
  └─ Send with 10ms delays between messages
```

**Not Cached:**
- NOISE_HANDSHAKE, NOISE_ENCRYPTED (encrypted already)
- ANNOUNCE, LEAVE
- Broadcast messages (recipients online or not intended for specific peer)

### 4. Gossip Synchronization (Missing Messages)

**GossipSyncManager** implements gossip-based sync:

**Protocol:**
- Periodic `REQUEST_SYNC` packets with GCS filters (every 30 seconds)
- Peer responds with missing announcements and broadcast messages
- **GCS:** Golomb Coded Sets for efficient set representation

**Features:**
- Tracks seen public packets (ANNOUNCE, broadcast MESSAGE)
- Sends REQUEST_SYNC to peers
- Peers respond with missing packets based on GCS filter membership test
- Automatic cleanup of stale announcements (>30 min old)
- Configurable capacity and false positive rate

**This provides recovery for:**
- Messages that were relayed but not cached
- Announcements from peers that briefly connected elsewhere
- Messages arriving during app background state

### 5. Relay Management

**PacketRelayManager** handles mesh forwarding:

**Adaptive Relay Probability:**
```
Network Size ≤ 3:     Always relay (100%)
Network Size ≤ 10:    Always relay (100%)
Network Size ≤ 30:    High probability (85%)
Network Size ≤ 50:    Moderate probability (70%)
Network Size ≤ 100:   Lower probability (55%)
Network Size > 100:   Low probability (40%)
```

**Relay Logic:**
```
FOR each incoming packet NOT addressed to us:
  ├─ Check TTL > 0 (if TTL=0, drop)
  ├─ Decrement TTL by 1
  ├─ Check if packet addressed to us (if yes, don't relay)
  ├─ Check if from ourselves (if yes, don't relay)
  └─ Apply adaptive relay probability based on network size
     └─ IF should relay: broadcast with decremented TTL
```

**Note:** Relay is optional (not guaranteed delivery), which is why gossip sync is needed for recovery.

---

## Network Layer Abstractions

### Current State: BLE-Only, but Structured for Extensibility

**No Abstract Transport Interface Exists Currently**

However, the architecture supports adding new transports through:

1. **Dual-Transport Architecture:** 
   - `BluetoothMeshService` coordinates mesh (BLE)
   - `NostrTransport` provides internet backup
   - `MessageRouter` chooses between them at message send time

2. **Message-Level Abstraction:**
   - All messages are `BitchatPacket` objects
   - Packet encoding/decoding is media-agnostic
   - Different transports serialize the same packet format

3. **Potential for Interface-Based Design:**
```kotlin
// (Not currently implemented, but architecture allows)
interface TransportLayer {
    suspend fun sendPacket(packet: BitchatPacket, recipientAddress: String?)
    suspend fun startListening(): Flow<ReceivedPacket>
    suspend fun connect(peerAddress: String): Boolean
    suspend fun disconnect(peerAddress: String)
    fun getAvailablePeers(): List<PeerInfo>
}

// Implementations:
// - BluetoothLETransport(context)
// - WiFiDirectTransport(context)
// - ToroInternet(context) // Already partially done via NostrTransport
// - NFCTransport(context)
```

### Design Pattern Used: Delegation with Callbacks

**Example from BluetoothConnectionManager:**
```kotlin
// Interface for consumers
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}

// Implementation:
var delegate: BluetoothConnectionManagerDelegate? = null

// Usage:
delegate?.onPacketReceived(packet, peerID, device)
```

This pattern allows:
- Clean separation of concerns
- Easy mocking for testing
- Swappable implementations
- Minimal coupling

---

## Key Components

### 1. BluetoothMeshService (1,199 lines)
**Location:** `/mesh/BluetoothMeshService.kt`

**Responsibilities:**
- Coordinate all mesh components
- Initialize and wire up delegates
- Provide public API for sending messages
- Manage service lifecycle
- Handle panic mode data clearing

**Key Methods:**
```kotlin
fun startServices(): Boolean                    // Start mesh
fun stopServices(): Unit                        // Graceful shutdown
fun sendMessage(content: String, ...)           // Broadcast message
fun sendPrivateMessage(content: String, ...)    // Encrypted DM
fun sendFileBroadcast(file: BitchatFilePacket) // Public file
fun sendFilePrivate(recipientID: String, ...)   // Encrypted file
fun sendReadReceipt(messageID: String, ...)    // Mark as read
fun sendBroadcastAnnounce(): Unit              // Peer discovery
fun cancelFileTransfer(transferId: String)     // Stop file send
```

### 2. BluetoothConnectionManager (390 lines)
**Location:** `/mesh/BluetoothConnectionManager.kt`

**Responsibilities:**
- Manage BLE GATT server and client
- Coordinate scanning and advertising
- Track connected devices
- Enforce connection limits
- Power management integration

**Key Components Within:**
- `BluetoothConnectionTracker` - Active connection tracking
- `BluetoothGattServerManager` - Peripheral mode
- `BluetoothGattClientManager` - Central mode
- `BluetoothPacketBroadcaster` - Serialized packet dispatch

### 3. PacketProcessor (220 lines)
**Location:** `/mesh/PacketProcessor.kt`

**Responsibilities:**
- Route incoming packets to handlers
- Per-peer actor serialization
- Packet validation
- Trigger relay decisions

**Key Feature:** Uses Kotlin actors for per-peer serialization:
```kotlin
private val actors = mutableMapOf<String, SendChannel<RoutedPacket>>()

fun processPacket(routed: RoutedPacket) {
    val actor = actors.getOrPut(peerID) { getOrCreateActorForPeer(peerID) }
    processorScope.launch {
        actor.send(routed)  // Serialized per peer
    }
}
```

### 4. MessageHandler (572 lines)
**Location:** `/mesh/MessageHandler.kt`

**Responsibilities:**
- Process different message types (ANNOUNCE, MESSAGE, FILE_TRANSFER, etc.)
- Handle Noise encryption for private messages
- Manage identity announcements
- Send delivery ACKs and read receipts
- Handle favorite notifications

**Message Types Handled:**
```
ANNOUNCE       → Extract peer identity, verify signature
MESSAGE        → Broadcast or private text
NOISE_HANDSHAKE → Key exchange
NOISE_ENCRYPTED → Decrypt and extract payload type:
                  ├─ PRIVATE_MESSAGE
                  ├─ FILE_TRANSFER
                  ├─ DELIVERED (ACK)
                  └─ READ_RECEIPT
FILE_TRANSFER  → Save file, notify UI
LEAVE          → Peer offline or channel leave
```

### 5. SecurityManager (320 lines)
**Location:** `/mesh/SecurityManager.kt`

**Responsibilities:**
- Duplicate detection & replay attack protection
- Noise handshake handling
- Signature verification (Ed25519)
- Encryption/decryption coordination
- Key exchange completion callbacks

**Key Data Structures:**
```kotlin
processedMessages: Set<String>        // Prevent duplicates
processedKeyExchanges: Set<String>    // Prevent replay of handshakes
messageTimestamps: Map<String, Long>  // Timestamp tracking for cleanup
```

**Cleanup:** 
- Periodic cleanup every 5 minutes
- Removes messages older than 5 minutes
- Max 1000 processed messages tracked

### 6. FragmentManager (300+ lines)
**Location:** `/mesh/FragmentManager.kt`

**Responsibilities:**
- Break large packets into BLE-compatible fragments
- Reassemble incoming fragments
- Handle fragment timeouts
- iOS-compatible implementation

**Parameters (from AppConstants):**
```
FRAGMENT_SIZE_THRESHOLD = 512 bytes   // If packet > 512, fragment it
MAX_FRAGMENT_SIZE       = 469 bytes   // Each fragment ≤ 469 bytes
FRAGMENT_TIMEOUT        = 30 seconds  // Clean up incomplete reassemblies
CLEANUP_INTERVAL        = 10 seconds  // Periodic cleanup checks
```

**Fragment Structure:**
```
Header (13 bytes):
  - fragmentID: 8 bytes (random)
  - index: 1 byte
  - total: 1 byte
  - originalType: 1 byte
  - dataLength: 2 bytes

Data: variable length
```

### 7. StoreForwardManager (250+ lines)
**Location:** `/mesh/StoreForwardManager.kt`

**Responsibilities:**
- Cache messages for offline peers
- Send cached messages when peer comes online
- Separate handling for favorites vs. regular peers
- Track delivery status

**Configuration:**
```
MESSAGE_CACHE_TIMEOUT  = 12 hours    // Regular peer cache TTL
MAX_CACHED_MESSAGES    = 50          // Regular peer message limit
MAX_CACHED_MESSAGES_FAVORITES = 200  // Favorite peer limit
CLEANUP_INTERVAL       = 10 minutes  // Periodic removal of old messages
```

### 8. PeerManager (250+ lines)
**Location:** `/mesh/PeerManager.kt`

**Responsibilities:**
- Track active peers and their status
- Manage peer nicknames and RSSI
- Store peer fingerprints (centralized)
- Update peer verification status
- Detect and handle stale peers

**Data Tracked (PeerInfo):**
```kotlin
data class PeerInfo(
    val id: String,                      // 16-char hex peer ID
    var nickname: String,                // Display name
    var isConnected: Boolean,            // Active/online
    var isDirectConnection: Boolean,     // Connected over BLE
    var noisePublicKey: ByteArray?,      // Noise encryption key
    var signingPublicKey: ByteArray?,    // Signature verification key
    var isVerifiedNickname: Boolean,     // Identity verified
    var lastSeen: Long                   // Timestamp
)
```

### 9. GossipSyncManager (270+ lines)
**Location:** `/sync/GossipSyncManager.kt`

**Responsibilities:**
- Implement gossip-based synchronization
- Build and send GCS filters
- Process sync requests from peers
- Maintain seen message/announcement log

**Configuration:**
```
Periodic REQUEST_SYNC: every 30 seconds
GCS target false positive rate: 1%
GCS filter max bytes: 400 bytes
Seen packet capacity: 500 packets
```

### 10. RoutedPacket Model (15 lines)
**Location:** `/model/RoutedPacket.kt`

**Data Class:**
```kotlin
data class RoutedPacket(
    val packet: BitchatPacket,          // The actual protocol packet
    val peerID: String? = null,         // Sender (parsed from senderID)
    val relayAddress: String? = null,   // Device address it came from
    val transferId: String? = null      // Progress tracking for files
)
```

---

## Message Flow

### Complete Flow: User Sends Message

```
┌─────────────────────────────────────┐
│ User taps "Send" in Chat UI          │
└─────────────┬───────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────┐
│ BluetoothMeshService.sendMessage()                      │
│ ├─ Create BitchatPacket:                               │
│ │  ├─ type = MESSAGE                                   │
│ │  ├─ senderID = myPeerID (16 chars)                  │
│ │  ├─ recipientID = BROADCAST or specific peer       │
│ │  ├─ payload = message text                          │
│ │  ├─ ttl = MAX_TTL (4 hops)                         │
│ │  └─ timestamp = now                                 │
│ ├─ Sign packet: signPacketBeforeBroadcast()          │
│ └─ Send to connectionManager.broadcastPacket()       │
└─────────────┬───────────────────────────────────────────┘
              ↓
┌──────────────────────────────────────────────────────────┐
│ BluetoothConnectionManager.broadcastPacket()             │
│ └─ Delegate to BluetoothPacketBroadcaster               │
└─────────────┬──────────────────────────────────────────┘
              ↓
┌──────────────────────────────────────────────────────────┐
│ BluetoothPacketBroadcaster (Actor-based serialization)   │
│ ├─ Check if fragmentation needed:                       │
│ │  ├─ encode packet to binary                           │
│ │  ├─ if size > 512: create fragments                  │
│ │  │  ├─ generate random fragmentID                    │
│ │  │  ├─ split into 469-byte chunks                    │
│ │  │  ├─ create FRAGMENT packet for each               │
│ │  │  └─ send with 20ms delays                         │
│ │  └─ else: send single packet                         │
│ └─ Get GATT server & characteristic                     │
│    └─ For each connected device:                        │
│       └─ Write or notify characteristic value          │
└──────────────────────────────────────────────────────────┘
```

### Complete Flow: User Receives Message

```
┌────────────────────────────────────────┐
│ BLE GATT Characteristic onRead/Notify  │
│ (Android system callback)              │
└─────────────┬──────────────────────────┘
              ↓
┌────────────────────────────────────────────────────────┐
│ BluetoothGattServerManager / ClientManager             │
│ (Handles GATT operations)                              │
│ ├─ Parse incoming BLE data                            │
│ ├─ Reassemble if multiple packets                      │
│ └─ Call delegate: onPacketReceived()                   │
└─────────────┬────────────────────────────────────────┘
              ↓
┌────────────────────────────────────────────────────────┐
│ BluetoothConnectionManager.delegate                    │
│ ├─ onPacketReceived callback                          │
│ ├─ Extract peerID from senderID                       │
│ ├─ Create RoutedPacket                                │
│ └─ Call packetProcessor.processPacket()               │
└─────────────┬────────────────────────────────────────┘
              ↓
┌────────────────────────────────────────────────────────┐
│ PacketProcessor.processPacket()                        │
│ ├─ Get or create per-peer actor                       │
│ ├─ Send packet to actor for serialized processing     │
│ └─ [Actor processes one packet at a time per peer]    │
└─────────────┬────────────────────────────────────────┘
              ↓
┌────────────────────────────────────────────────────────┐
│ PacketProcessor (actor): handleReceivedPacket()        │
│ ├─ Validate security (SecurityManager)                │
│ ├─ Determine MessageType                              │
│ └─ Route to appropriate handler:                       │
│    ├─ ANNOUNCE → handleAnnounce()                     │
│    ├─ MESSAGE → handleMessage()                       │
│    ├─ NOISE_HANDSHAKE → handleNoiseHandshake()       │
│    ├─ NOISE_ENCRYPTED → handleNoiseEncrypted()       │
│    ├─ FRAGMENT → handleFragment()                     │
│    └─ etc.                                            │
└─────────────┬────────────────────────────────────────┘
              ↓
      [Specific Handler Logic]
              ↓
┌────────────────────────────────────────────────────────┐
│ MessageHandler.handleMessage()                         │
│ ├─ Determine if broadcast or private:                 │
│ │  ├─ NO recipientID → Broadcast                      │
│ │  ├─ recipientID == me → Private for me              │
│ │  └─ recipientID == other → Relay                    │
│ ├─ If private & encrypted:                            │
│ │  ├─ Decrypt with Noise session                      │
│ │  ├─ Extract NoisePayloadType                        │
│ │  └─ Process (PM, file, ACK, etc.)                  │
│ ├─ Create BitchatMessage object                       │
│ └─ Call delegate: onMessageReceived()                 │
└─────────────┬────────────────────────────────────────┘
              ↓
┌────────────────────────────────────────────────────────┐
│ UI Layer (ChatViewModel, etc.)                         │
│ ├─ Receive onMessageReceived callback                 │
│ ├─ Update chat state                                  │
│ └─ Trigger UI recomposition                           │
└────────────────────────────────────────────────────────┘
```

### Relay Flow (non-addressed packets)

```
After handleReceivedPacket() completes in PacketProcessor:

┌──────────────────────────────────────────────────────┐
│ Check if packet should be relayed                    │
│ ├─ IF packet addressed to us: SKIP                   │
│ ├─ IF from ourselves: SKIP                           │
│ ├─ IF TTL == 0: SKIP                                 │
│ └─ ELSE: Call packetRelayManager.handlePacketRelay() │
└──────────────────────────────────────────────────────┘
         ↓
┌──────────────────────────────────────────────────────┐
│ PacketRelayManager.handlePacketRelay()               │
│ ├─ Decrement TTL by 1                               │
│ ├─ Check high TTL (≥4) → Always relay               │
│ ├─ Check network size:                              │
│ │  ├─ Small (≤3): Always relay                      │
│ │  ├─ Medium (≤30): 85% probability                 │
│ │  ├─ Large (≤100): 55% probability                 │
│ │  └─ Very large (>100): 40% probability            │
│ ├─ If should relay:                                 │
│ │  └─ Create new RoutedPacket with decremented TTL  │
│ │  └─ Call delegate.broadcastPacket()               │
│ └─ else: DROP (no relay)                            │
└──────────────────────────────────────────────────────┘
         ↓
    [Packets relayed back to step: 
     BluetoothPacketBroadcaster]
```

---

## File Paths and Key Files

### Core Mesh Components

| Component | File Path | Lines | Purpose |
|-----------|-----------|-------|---------|
| Main Service | `mesh/BluetoothMeshService.kt` | 1,199 | Coordinator service |
| Connection Manager | `mesh/BluetoothConnectionManager.kt` | 390 | BLE connection management |
| Packet Broadcaster | `mesh/BluetoothPacketBroadcaster.kt` | 400+ | Serialized packet dispatch |
| Packet Processor | `mesh/PacketProcessor.kt` | 220 | Incoming packet routing |
| Message Handler | `mesh/MessageHandler.kt` | 572 | Message type processing |
| Security Manager | `mesh/SecurityManager.kt` | 320 | Crypto & duplicate detection |
| Fragment Manager | `mesh/FragmentManager.kt` | 300+ | Message fragmentation |
| Store/Forward Manager | `mesh/StoreForwardManager.kt` | 250+ | Offline message caching |
| Packet Relay Manager | `mesh/PacketRelayManager.kt` | 174 | Adaptive mesh forwarding |
| Peer Manager | `mesh/PeerManager.kt` | 250+ | Peer tracking |
| Peer Fingerprint Manager | `mesh/PeerFingerprintManager.kt` | ? | Centralized fingerprint storage |

### BLE GATT Implementation

| Component | File Path | Purpose |
|-----------|-----------|---------|
| GATT Server Manager | `mesh/BluetoothGattServerManager.kt` | Peripheral mode (advertising, subscriptions) |
| GATT Client Manager | `mesh/BluetoothGattClientManager.kt` | Central mode (scanning, connections) |
| Connection Tracker | `mesh/BluetoothConnectionTracker.kt` | Active device tracking |
| Permission Manager | `mesh/BluetoothPermissionManager.kt` | Runtime permission handling |
| Power Manager | `mesh/PowerManager.kt` | Battery-aware duty cycling |

### Protocol & Models

| Component | File Path | Purpose |
|-----------|-----------|---------|
| Binary Protocol | `protocol/BinaryProtocol.kt` | Packet encoding/decoding |
| Message Types | `protocol/BinaryProtocol.kt` (enum) | Protocol message types |
| Bitchat Packet | `protocol/BinaryProtocol.kt` | Packet data class |
| Routed Packet | `model/RoutedPacket.kt` | Packet with metadata |
| Bitchat Message | `model/BitchatMessage.kt` | UI message model |
| Identity Announcement | `model/IdentityAnnouncement.kt` | Peer discovery format |
| Fragment Payload | `model/FragmentPayload.kt` | Fragment structure |
| File Packet | `model/BitchatFilePacket.kt` | File transfer encoding |
| Noise Encrypted | `model/NoiseEncrypted.kt` | Encrypted transport wrapper |

### Synchronization

| Component | File Path | Purpose |
|-----------|-----------|---------|
| Gossip Sync Manager | `sync/GossipSyncManager.kt` | Gossip protocol implementation |
| GCS Filter | `sync/GCSFilter.kt` | Golomb Coded Sets |
| Packet ID Util | `sync/PacketIdUtil.kt` | Packet ID generation |
| Request Sync Packet | `model/RequestSyncPacket.kt` | Sync request format |
| Sync Defaults | `sync/SyncDefaults.kt` | Configuration constants |

### Services & Utilities

| Component | File Path | Purpose |
|-----------|-----------|---------|
| Message Router | `services/MessageRouter.kt` | Mesh/Nostr hybrid routing |
| Encryption Service | `crypto/EncryptionService.kt` | Noise protocol & signing |
| Transfer Progress Manager | `mesh/TransferProgressManager.kt` | File progress tracking |
| File Utils | `features/file/FileUtils.kt` | File I/O helpers |

### Nostr (Alternative Transport)

| Component | File Path | Purpose |
|-----------|-----------|---------|
| Nostr Transport | `nostr/NostrTransport.kt` | Internet-based messaging |
| Nostr Client | `nostr/NostrClient.kt` | Relay communication |
| Nostr Protocol | `nostr/NostrProtocol.kt` | Event serialization |

---

## Summary of Current State

### What Works Well
✅ **BLE Mesh Networking** - Stable peer-to-peer communication  
✅ **Encryption** - Noise Protocol for private messages  
✅ **Message Types** - Multiple message categories (text, files, voice)  
✅ **Fragmentation** - Large packets transparently split  
✅ **Peer Discovery** - ANNOUNCE mechanism  
✅ **Offline Caching** - Store & forward for favorites  
✅ **Duplicate Detection** - Prevents duplicate processing  
✅ **iOS Compatibility** - Exact protocol match  
✅ **Gossip Sync** - Missing message recovery via GCS filters  
✅ **Delivery ACKs** - Confirmation for private messages  
✅ **Read Receipts** - Message status tracking  

### What Needs Enhancement
⚠️ **No explicit retry mechanism** - Relies on gossip sync & relaying  
⚠️ **Limited transports** - BLE only (Nostr for internet backup)  
⚠️ **No transport abstraction** - Tightly coupled to BLE  
⚠️ **TTL-based delivery** - No guaranteed delivery confirmation  
⚠️ **Broadcast not cached** - Unless explicitly stored & forwarded  
⚠️ **No timeout recovery** - Messages dropped after TTL expires  

### Architecture Strengths for Multi-Transport
✅ Component-based design allows adding new components  
✅ Delegate pattern enables loose coupling  
✅ Actor-based serialization prevents race conditions  
✅ Message format (BitchatPacket) is transport-agnostic  
✅ RoutedPacket metadata supports multiple path tracking  
✅ Already supports Nostr for internet fallback  

---

## Exit Relay Feature (In Scope)

**Status:** Design document completed, MVP implementation planned

**New Components Needed:**
- `SolanaRelayHandler` - Process Solana relay requests/receipts
- New message types: `RELAY_REQUEST` (0x30), `RELAY_RECEIPT` (0x31)
- Solana transaction submission via OkHttpProvider

**Integration Points:**
- Hook into `MessageHandler` for RELAY_REQUEST/RELAY_RECEIPT processing
- Use existing `BluetoothMeshService.broadcast()` for response
- Leverage existing HTTP/Tor infrastructure for RPC calls

**Estimated Implementation:** ~300 lines of new code

---

## Conclusion

BitChat's mesh network implementation is:
- **Well-structured** with clear separation of concerns
- **iOS-compatible** down to packet-level details
- **Extensible** for additional transports (WiFi, etc.)
- **Resilient** with multiple recovery mechanisms
- **Ready for relay features** with minimal changes

The architecture supports adding new transports and delivery mechanisms without major refactoring, using existing patterns (delegate callbacks, component composition, actor-based serialization).
