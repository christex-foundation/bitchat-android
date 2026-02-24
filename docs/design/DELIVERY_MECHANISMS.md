# Message Delivery and Retry Mechanisms

**Analysis Date:** October 25, 2025  
**Status:** Comprehensive Architecture Review  

---

## Table of Contents
1. [Quick Summary](#quick-summary)
2. [Delivery Mechanism Overview](#delivery-mechanism-overview)
3. [Retry Strategies](#retry-strategies)
4. [Acknowledgment System](#acknowledgment-system)
5. [Transport Considerations](#transport-considerations)
6. [Multi-Transport Possibilities](#multi-transport-possibilities)

---

## Quick Summary

BitChat does **NOT** implement explicit message retries. Instead, it uses:

1. **Gossip Synchronization** - Peers periodically exchange missing messages
2. **Store & Forward** - Offline caching for unavailable peers
3. **Acknowledgments** - For private messages (delivery + read receipts)
4. **Relay with TTL** - Probabilistic forwarding through mesh
5. **Duplicate Detection** - Prevents processing same message twice

This design prioritizes **simplicity and bandwidth efficiency** over guaranteed delivery, suitable for low-bandwidth BLE mesh networks.

---

## Delivery Mechanism Overview

### Message Types and Delivery Guarantees

| Message Type | Delivery Model | Retry Strategy | Status Feedback |
|---|---|---|---|
| **ANNOUNCE** | Gossip sync | Periodic sync (30s) | None |
| **MESSAGE** (broadcast) | Relay + Gossip | TTL + periodic sync | None |
| **MESSAGE** (private) | Relay + Encrypted | None (relies on gossip) | None |
| **NOISE_ENCRYPTED** | Private tunnel | None | Depends on payload |
| **FILE_TRANSFER** | Relay + Fragment | TTL + gossip | Transfer progress |
| **DELIVERED** (ACK) | Acknowledge receipt | None | Implicit (received) |
| **READ_RECEIPT** | Confirm reading | None | Implicit (read) |

### Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│  Message Sending (BluetoothMeshService)                 │
│  ├─ Create BitchatPacket                               │
│  ├─ Sign (Ed25519)                                     │
│  └─ Send via broadcastPacket()                         │
└────────────────┬──────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│  BLE Transport (BluetoothPacketBroadcaster)             │
│  ├─ Fragment if > 512 bytes                            │
│  ├─ Send via GATT write/notify                         │
│  └─ Track transfer progress                            │
└────────────────┬──────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│  Physical BLE Link                                      │
│  └─ MTU ~251 bytes per packet                          │
└────────────────┬──────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│  Receiving Device (BluetoothGattServerManager/Client)   │
│  ├─ Receive GATT characteristic write                   │
│  ├─ Reassemble fragments if needed                      │
│  └─ Call onPacketReceived()                            │
└────────────────┬──────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│  Packet Processing (PacketProcessor)                    │
│  ├─ Per-peer actor serialization                       │
│  ├─ Security validation (SecurityManager)              │
│  └─ Route to handler (MessageHandler, etc.)            │
└────────────────┬──────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│  Delivery Confirmation (if applicable)                  │
│  ├─ Send ACK for private messages                      │
│  ├─ Cache for offline peers (StoreForwardManager)      │
│  └─ Track in gossip sync (GossipSyncManager)           │
└─────────────────────────────────────────────────────────┘
```

---

## Retry Strategies

### Strategy 1: No Explicit Retries (Current)

**When:** BLE sends a message

**What Happens:**
1. Packet is broadcast to all connected peers
2. TTL determines max number of hops (default 4)
3. Each peer probabilistically relays based on network size
4. **No automatic resend** if first attempt fails

**Pros:**
- Minimal bandwidth overhead
- Scales to large networks
- Simple to implement

**Cons:**
- No guaranteed delivery
- Lost messages only recovered via gossip
- Could fail if all relays simultaneously ignore packet

**Code Reference:** `PacketRelayManager.handlePacketRelay()`

### Strategy 2: Gossip Synchronization (Recovery)

**When:** Every 30 seconds

**What Happens:**
1. Each peer sends `REQUEST_SYNC` with GCS filter of seen packets
2. Peers respond with packets they have that requester doesn't have
3. Resend missing packets to requester

**Pros:**
- Recovers lost messages without explicit retry
- Scales efficiently (GCS filter is ~400 bytes)
- Handles network partition scenarios

**Cons:**
- 30-second latency before recovery
- Limited capacity (500 recent packets)
- Doesn't recover truly old messages

**Code Reference:** `GossipSyncManager.sendRequestSync()`

```kotlin
// Periodic sync every 30 seconds
scope.launch(Dispatchers.IO) {
    while (isActive) {
        try {
            delay(30_000)  // 30 seconds
            sendRequestSync()
        } catch (e: Exception) { 
            Log.e(TAG, "Periodic sync error: ${e.message}") 
        }
    }
}
```

### Strategy 3: Store & Forward (Persistence)

**When:** Peer comes online or becomes available

**What Happens:**
1. Messages directed to offline peer are cached (12 hours for regular, indefinite for favorites)
2. When peer comes online, cached messages are resent with 10ms delays
3. Delivery status tracked per message

**Pros:**
- Guarantees eventual delivery to favorites
- Persists across app restarts
- Separate queues for favorites vs. regulars

**Cons:**
- Limited to cached messages (50 for regular, 200 for favorites)
- 12-hour timeout for regular peers
- Requires local storage

**Code Reference:** `StoreForwardManager.sendCachedMessages()`

```kotlin
fun sendCachedMessages(peerID: String) {
    // Don't resend if already sent
    if (cachedMessagesSentToPeer.contains(peerID)) return
    
    cachedMessagesSentToPeer.add(peerID)
    
    managerScope.launch {
        val messagesToSend = mutableListOf<StoredMessage>()
        
        // Collect from favorite queue and regular cache
        favoriteMessageQueue[peerID]?.let { 
            messagesToSend.addAll(it) 
        }
        messageCache.filter { 
            it.packet.recipientID == peerID 
        }.let { 
            messagesToSend.addAll(it) 
        }
        
        // Send with delays to avoid overwhelming connection
        messagesToSend.forEach { storedMessage ->
            delay(10L)  // 10ms between messages
            delegate?.sendPacket(storedMessage.packet)
        }
    }
}
```

### Strategy 4: Mesh Relay with TTL

**When:** Packet is received and not addressed to you

**What Happens:**
1. Check packet TTL > 0
2. Decrement TTL
3. Apply adaptive relay probability based on network size
4. Broadcast with new TTL

**Pros:**
- Probabilistic avoids duplicate broadcasts
- Scales intelligently with network size
- TTL provides loop prevention

**Cons:**
- Probability-based can miss some messages
- No guaranteed forward progress
- Requires peer count estimation

**Code Reference:** `PacketRelayManager.shouldRelayPacket()`

```kotlin
private fun shouldRelayPacket(packet: BitchatPacket, fromPeerID: String): Boolean {
    val networkSize = delegate?.getNetworkSize() ?: 1
    
    val relayProb = when {
        networkSize <= 10 -> 1.0      // Always relay in small networks
        networkSize <= 30 -> 0.85     // High probability
        networkSize <= 50 -> 0.7      // Moderate
        networkSize <= 100 -> 0.55    // Lower
        else -> 0.4                   // Lowest for very large
    }
    
    return Random.nextDouble() < relayProb
}
```

### Strategy 5: No Retry for Encrypted Messages

**When:** Private message fails to send

**What Happens:**
1. Check if Noise session established with recipient
2. If no session: Initiate handshake but don't retry sending
3. Message is NOT automatically queued or retried

**Pros:**
- Prevents plaintext fallback
- Forces explicit key exchange first

**Cons:**
- User must retry manually if no session
- No automatic message queueing

**Code Reference:** `BluetoothMeshService.sendPrivateMessage()`

```kotlin
if (encryptionService.hasEstablishedSession(recipientPeerID)) {
    // Send encrypted message
    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)
    connectionManager.broadcastPacket(RoutedPacket(packet))
} else {
    // NO RETRY - Just initiate handshake
    messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
}
```

---

## Acknowledgment System

### Private Message ACKs (Delivery Confirmation)

**Sender's Perspective:**

```
User types: "Hey Alice"
    ↓
BluetoothMeshService.sendPrivateMessage()
    ↓
Create NOISE_ENCRYPTED packet with PRIVATE_MESSAGE payload
    ↓
Encrypt and broadcast
    ↓
[Wait for DELIVERED ACK from Alice's app]
    ↓
MessageRouter: onDeliveryAckReceived(messageID, fromPeerID)
    ↓
UI updates status: "Delivered"
```

**Receiver's Perspective:**

```
Receive NOISE_ENCRYPTED from Alice
    ↓
MessageHandler.handleNoiseEncrypted()
    ↓
Decrypt and verify signature
    ↓
Extract NoisePayloadType = PRIVATE_MESSAGE
    ↓
Create BitchatMessage, notify UI
    ↓
MessageHandler.sendDeliveryAck(messageID, fromPeerID)
    ↓
Create NOISE_ENCRYPTED with DELIVERED payload
    ↓
Encrypt and send back to Alice
```

**Code Reference:** `MessageHandler.sendDeliveryAck()`

```kotlin
private suspend fun sendDeliveryAck(messageID: String, senderPeerID: String) {
    try {
        val ackPayload = NoisePayload(
            type = NoisePayloadType.DELIVERED,
            data = messageID.toByteArray(Charsets.UTF_8)
        )
        
        val encryptedPayload = delegate?.encryptForPeer(
            ackPayload.encode(), 
            senderPeerID
        )
        
        if (encryptedPayload == null) {
            Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
            return
        }
        
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(senderPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = encryptedPayload,
            ttl = MESSAGE_TTL_HOPS
        )
        
        delegate?.sendPacket(packet)
        Log.d(TAG, "Sent delivery ACK to $senderPeerID for $messageID")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to send delivery ACK: ${e.message}")
    }
}
```

### Read Receipts (Reading Confirmation)

**Similar flow but with NoisePayloadType.READ_RECEIPT**

**Code Reference:** `BluetoothMeshService.sendReadReceipt()`

```kotlin
fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
    serviceScope.launch {
        try {
            val readReceiptPayload = NoisePayload(
                type = NoisePayloadType.READ_RECEIPT,
                data = messageID.toByteArray(Charsets.UTF_8)
            )
            
            val encrypted = encryptionService.encrypt(
                readReceiptPayload.encode(), 
                recipientPeerID
            )
            
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = hexStringToByteArray(recipientPeerID),
                timestamp = System.currentTimeMillis().toULong(),
                payload = encrypted,
                ttl = MESSAGE_TTL_HOPS
            )
            
            val signedPacket = signPacketBeforeBroadcast(packet)
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            Log.d(TAG, "Sent read receipt to $recipientPeerID for $messageID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send read receipt: ${e.message}")
        }
    }
}
```

### UI Status Tracking

**DeliveryStatus Sealed Class:**

```kotlin
sealed class DeliveryStatus : Parcelable {
    object Sending : DeliveryStatus()
    object Sent : DeliveryStatus()
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()
    data class Read(val by: String, val at: Date) : DeliveryStatus()
    data class Failed(val reason: String) : DeliveryStatus()
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()
    
    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}
```

---

## Transport Considerations

### BLE-Specific Constraints

| Constraint | Value | Impact |
|---|---|---|
| MTU (Max Tx Unit) | ~251 bytes | Requires fragmentation for messages > 512 bytes |
| Connection count | Limited by device | Enforced connection limits per device |
| Bandwidth | ~1-2 Mbps | Shared with other BLE services |
| Range | ~10-100m | Limits direct peer count in physical space |
| Power | Battery-dependent | Duty cycling needed for background operation |

### Fragmentation Strategy

**When:** Packet > 512 bytes unencoded

**Process:**
1. Encode packet to binary (13-byte header + payload)
2. If total > 512 bytes: fragment into 469-byte chunks
3. Each fragment wrapped in FRAGMENT packet with:
   - fragmentID: 8 random bytes
   - index: fragment number
   - total: total fragment count
   - originalType: message type of original

**Code Reference:** `FragmentManager.createFragments()`

### No Transport Abstraction (Currently)

All code is tightly coupled to BLE:
- `BluetoothConnectionManager` - BLE specific
- `BluetoothGattServerManager` - BLE specific  
- `BluetoothGattClientManager` - BLE specific
- `BluetoothPacketBroadcaster` - BLE specific

**Why No Abstraction:**
- Only BLE currently implemented
- Would add complexity without current benefit
- Easy to refactor when second transport added

---

## Multi-Transport Possibilities

### Internet Fallback: Nostr Transport

**Currently Implemented:** Parallel, not fallback

**How It Works:**
1. `MessageRouter` chooses between mesh and Nostr
2. If mesh path available: use BLE
3. If no mesh: use Nostr (via internet)

**Code Reference:** `MessageRouter.sendPrivate()`

```kotlin
fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
    val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
    val hasEstablished = mesh.hasEstablishedSession(toPeerID)
    
    if (hasMesh && hasEstablished) {
        Log.d(TAG, "Routing PM via mesh to $toPeerID")
        mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
    } else if (canSendViaNostr(toPeerID)) {
        Log.d(TAG, "Routing PM via Nostr to $toPeerID")
        nostr.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
    } else {
        Log.d(TAG, "Queued PM for $toPeerID (no mesh, no Nostr)")
        queueForLaterDelivery(toPeerID, content, recipientNickname, messageID)
    }
}
```

### WiFi Direct (Not Yet Implemented)

**Potential Benefits:**
- Higher bandwidth than BLE
- Better range than BLE
- Can coexist with BLE

**Integration Points Needed:**
1. Create `WiFiDirectTransport` component
2. Implement same packet API as BLE
3. Update `MessageRouter` to choose transport
4. No changes needed to protocol layer

### Architecture for Multi-Transport

**Proposed (not implemented):**

```kotlin
interface NetworkTransport {
    suspend fun sendPacket(packet: BitchatPacket, targetPeerID: String?): Boolean
    suspend fun broadcastPacket(packet: BitchatPacket): Int  // returns num sent
    fun subscribeToPackets(): Flow<ReceivedPacket>
    fun getConnectedPeers(): List<PeerInfo>
    suspend fun connect(peerAddress: String): Boolean
    suspend fun disconnect(peerAddress: String): Boolean
}

class MeshService(
    private val bleTransport: BLETransport,
    private val wifiTransport: WiFiDirectTransport,
    private val torTransport: TorTransport
) {
    fun selectTransport(peerID: String, messageType: MessageType): NetworkTransport {
        return when {
            messageType == RELAY_REQUEST -> torTransport  // Always use internet for relay
            bleTransport.isPeerConnected(peerID) -> bleTransport
            wifiTransport.isPeerConnected(peerID) -> wifiTransport
            torTransport.isAvailable() -> torTransport
            else -> bleTransport  // Fallback with retry
        }
    }
}
```

---

## Summary: When Messages Succeed vs. Fail

### Message Succeeds When:

1. **Direct BLE connection exists** (< 1 second latency)
2. **Mesh relay path available** (few seconds)
3. **Gossip sync recovers it** (< 30 seconds typically)
4. **Peer comes online** (store & forward delivers)
5. **ACK received** (private messages only)

### Message Fails When:

1. **All relay peers drop it** (rare, probability-based)
2. **TTL expires before reaching recipient** (4 hop limit)
3. **Peer permanently offline** (gossip + store & forward timeout)
4. **No Noise session + recipient offline** (private message, no queue)
5. **Cache full** (after 50 messages for regular peers)
6. **12-hour timeout** (regular peer cache expires)

---

## Recommendations for Improvement

### High Priority

1. **Add explicit retry for important messages**
   - Retry after 5 seconds if no ACK
   - Max 3 retries with exponential backoff
   - Optional per-message flag

2. **Increase cache capacity**
   - Default 50 → 200 messages
   - Configurable per device storage
   - Implement disk-based cache for very large queues

3. **Implement transport abstraction**
   - Prepare for WiFi Direct, NFC, etc.
   - Isolate BLE-specific code
   - Allow runtime transport selection

### Medium Priority

4. **Add message timeout handling**
   - Mark messages failed after 1 minute no delivery
   - Retry up to 3 times
   - User notification on repeated failure

5. **Improve gossip efficiency**
   - Reduce sync interval from 30s (power saving)
   - Increase packet capacity (network coverage)
   - Add per-peer sync schedule

6. **Add acknowledgment for broadcast messages**
   - Optional mode: ACK from first N peers
   - Confirmation: "Reached 5 peers"
   - Network health indicator

### Lower Priority

7. **Implement message compression**
   - GZip compression for payload
   - Reduces bandwidth by ~30%
   - Transparent to transport layer

8. **Add priority queues**
   - High-priority messages (SOS, emergencies)
   - Medium (regular messages)
   - Low (bulk transfers)

---

## Conclusion

BitChat's delivery mechanisms prioritize **reliability through redundancy** rather than explicit retries:

- **Gossip sync** ensures missing messages are recovered
- **Store & forward** guarantees delivery to favorites
- **Mesh relay** with adaptive probability scales to any network size
- **ACKs** provide feedback for private messages
- **No explicit retries** keep bandwidth low and complexity manageable

This approach is well-suited for BLE mesh networks but can be enhanced with explicit retries for critical messages and expanded to support multiple transports as the platform grows.

