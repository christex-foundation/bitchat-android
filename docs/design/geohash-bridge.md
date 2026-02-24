# Geohash Bridge Feature: Design Document

**Status:** PROPOSED - Pending Review
**Version:** 1.0
**Last Updated:** 2025-10-27
**Author:** System Architecture Team

---

## Overview

This document outlines a **location-based mesh-to-internet bridge** that enables users without internet to communicate with internet-connected users via nearby peers who have both Bluetooth mesh and internet connectivity.

### Key Design Decisions

- **Automatic bridging**: No user configuration required
- **Location-based**: Bridges messages only for user's current geohash
- **Bidirectional**: Mesh ↔ Nostr relay in both directions
- **Eligibility gating**: Internet + battery ≥10% + location enabled
- **No new protocol**: Reuses existing mesh broadcast + Nostr geohash channels
- **Minimal code**: ~30 lines new, ~10 lines modified

### Goals

- Enable Bluetooth-only users to participate in internet-based geohash channels
- Enable distant internet users to communicate with local mesh-only users
- Create automatic relay infrastructure based on physical location
- Maintain backwards compatibility with existing mesh and Nostr users
- Prove location-based mesh networking utility

---

## The Core Idea (Simple Version)

**Problem:** Bob has only Bluetooth (no internet). Charlie is far away with internet. They both want to talk to the same local group.

**Solution:** Alice is nearby with both Bluetooth AND internet. Her phone automatically:
1. Receives Bob's Bluetooth messages → relays to Nostr
2. Receives Charlie's Nostr messages → broadcasts on Bluetooth mesh
3. Everyone in the geohash can talk, regardless of connectivity

**Key Insight:** Anyone with internet at a location becomes an automatic bridge for that location's mesh network.

---

## End-to-End Flow

### Part 1: Alice Enables Location (Bridge Setup)

```
1. User Action
   ├─ Opens BitChat app
   ├─ Enables location services
   └─ GPS detects: geohash "9q8yy" (San Francisco)

2. Automatic Subscription
   ├─ LocationChannelManager computes geohash
   ├─ Auto-subscribe to Nostr relay for "9q8yy"
   └─ User is now in geo:9q8yy timeline

3. Bridge Activation
   ├─ Check eligibility:
   │  ├─ Has internet? ✓ (WiFi or cellular)
   │  ├─ Battery ≥10%? ✓
   │  └─ Location enabled? ✓
   └─ Bridge mode: ACTIVE (no UI indication needed)
```

---

### Part 2: Bob Sends Message (Bluetooth Only → Internet)

```
Bob's phone (BLE only, no internet)
    │
    │ "Hey everyone, coffee meetup at 3pm?"
    │
    ↓
┌──────────────────────────────────────┐
│  Bob broadcasts via BLE mesh         │
│  Content: "Hey everyone..."          │
│  No geohash tag (Bob doesn't know)   │
└──────────┬───────────────────────────┘
           ↓
    [BLE mesh hops]
           ↓
┌──────────────────────────────────────┐
│  Alice receives (has internet)       │
├──────────────────────────────────────┤
│  1. MeshDelegateHandler              │
│     └─ onMessageReceived()           │
│  2. GeohashViewModel                 │
│     └─ onMeshMessageReceived()       │
│  3. Check eligibility:               │
│     ├─ Has internet? ✓               │
│     ├─ Battery ≥10%? ✓               │
│     ├─ At geohash location? ✓        │
│     └─ Not my own message? ✓         │
│  4. Bridge to Nostr:                 │
│     └─ NostrTransport.publishTo      │
│        Geohash("9q8yy", content)     │
└──────────┬───────────────────────────┘
           ↓
    [Nostr relay]
           ↓
┌──────────────────────────────────────┐
│  Charlie receives (distant, internet)│
│  "Hey everyone, coffee meetup..."    │
│  From: Bob (via Alice's bridge)      │
└──────────────────────────────────────┘
```

---

### Part 3: Charlie Replies (Internet → Bluetooth Only)

```
Charlie's phone (internet, far away)
    │
    │ "I'll be there!"
    │
    ↓
┌──────────────────────────────────────┐
│  Charlie posts to Nostr              │
│  Tag: g=9q8yy                        │
│  Content: "I'll be there!"           │
└──────────┬───────────────────────────┘
           ↓
    [Nostr relay]
           ↓
┌──────────────────────────────────────┐
│  Alice receives (subscribed to 9q8yy)│
├──────────────────────────────────────┤
│  1. GeohashMessageHandler            │
│     └─ onEvent()                     │
│  2. Add to message history           │
│  3. Alice's UI shows message         │
│  4. EXISTING: Already works          │
└──────────┬───────────────────────────┘
           │
           │ (NEW: Also broadcast to mesh)
           ↓
┌──────────────────────────────────────┐
│  Alice broadcasts via BLE mesh       │
│  (Change 1: dual-broadcast)          │
│  Content: "I'll be there!"           │
└──────────┬───────────────────────────┘
           ↓
    [BLE mesh hops]
           ↓
┌──────────────────────────────────────┐
│  Bob receives (BLE only)             │
│  "I'll be there!"                    │
│  From: Charlie (via Alice's bridge)  │
└──────────────────────────────────────┘
```

---

## Architecture Integration

### Visual Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    BOB (Bluetooth Only)                      │
├─────────────────────────────────────────────────────────────┤
│  ChatViewModel.sendMessage("coffee at 3pm")                  │
│         ↓                                                    │
│  BluetoothMeshService.sendMessage() ← EXISTING              │
│         ↓                                                    │
│  BLE broadcast to nearby peers                               │
└─────────────────┬───────────────────────────────────────────┘
                  ↓
         ┌────────────────┐
         │  Mesh Network  │ ← EXISTING (no changes)
         │  (BLE hops)    │
         └────────┬───────┘
                  ↓
┌─────────────────────────────────────────────────────────────┐
│              ALICE (Bluetooth + Internet)                    │
├─────────────────────────────────────────────────────────────┤
│  MeshDelegateHandler.onMessageReceived()                    │
│         ↓                                                    │
│  GeohashViewModel.onMeshMessageReceived() ← NEW (~25 LOC)   │
│         ├─ Check eligibility (internet, battery, location)   │
│         ├─ Check not own message                            │
│         └─ Bridge to Nostr                                   │
│         ↓                                                    │
│  NostrTransport.publishToGeohash() ← EXISTING               │
│         ↓                                                    │
│  POST to Nostr relay (kind=20000, g=9q8yy)                  │
└─────────────────┬───────────────────────────────────────────┘
                  ↓
         ┌────────────────┐
         │  Nostr Relay   │ ← EXISTING (no changes)
         └────────┬───────┘
                  ↓
┌─────────────────────────────────────────────────────────────┐
│           CHARLIE (Internet, Distant)                        │
├─────────────────────────────────────────────────────────────┤
│  GeohashMessageHandler.onEvent() ← EXISTING                 │
│         ↓                                                    │
│  Display message in UI                                       │
│         ↓                                                    │
│  Charlie replies: "I'll be there!"                          │
│         ↓                                                    │
│  NostrTransport.publishToGeohash() ← EXISTING               │
└─────────────────┬───────────────────────────────────────────┘
                  ↓
         ┌────────────────┐
         │  Nostr Relay   │
         └────────┬───────┘
                  ↓
┌─────────────────────────────────────────────────────────────┐
│              ALICE (receives via Nostr)                      │
├─────────────────────────────────────────────────────────────┤
│  GeohashMessageHandler.onEvent() ← EXISTING                 │
│         ↓                                                    │
│  Display message in UI                                       │
│         ↓                                                    │
│  ChatViewModel.sendGeohashMessage() ← EXISTING              │
│         ↓                                                    │
│  ALSO BluetoothMeshService.sendMessage() ← NEW (1 LOC)     │
│         ↓                                                    │
│  BLE broadcast to nearby peers                               │
└─────────────────┬───────────────────────────────────────────┘
                  ↓
         ┌────────────────┐
         │  Mesh Network  │
         └────────┬───────┘
                  ↓
┌─────────────────────────────────────────────────────────────┐
│                    BOB (receives via BLE)                    │
├─────────────────────────────────────────────────────────────┤
│  MeshDelegateHandler.onMessageReceived()                    │
│         ↓                                                    │
│  Display: "I'll be there!" (from Charlie)                   │
└─────────────────────────────────────────────────────────────┘
```

### Component Modifications

| Component | Change | Type | LOC |
|-----------|--------|------|-----|
| `ChatViewModel.kt` | Add dual-broadcast to mesh when sending geohash message | Modified | +1 |
| `GeohashViewModel.kt` | Add `onMeshMessageReceived()` bridge function | New method | +25 |
| `MeshDelegateHandler.kt` | Hook bridge into message receive flow | Modified | +5 |
| **Total** | | | **~31** |

---

## Example User Journeys

### Journey 1: Music Festival (No Cell Signal)

**Scenario:** 500 people at music festival, spotty/no cell signal. 10 people have WiFi from nearby cafe.

```
1. Alice (WiFi from cafe) enables location
   └─ GPS: geohash "9q8yyb"
   └─ Auto-subscribed to Nostr relay for 9q8yyb
   └─ Bridge mode: ACTIVE

2. Bob (no internet) broadcasts on mesh:
   "Meeting at main stage in 10 min!"
   └─ Alice's phone receives via BLE
   └─ Alice's phone relays to Nostr
   └─ Posted to Nostr relay for 9q8yyb

3. Charlie (at home, 50km away) sees message on Nostr
   └─ Subscribed to 9q8yyb (his favorite festival location)
   └─ Replies: "Wish I was there! Have fun!"
   └─ Posted to Nostr

4. Alice receives Charlie's reply via Nostr
   └─ Also broadcasts to mesh
   └─ Bob receives via BLE: Charlie's message

Result: Bob (no internet) and Charlie (far away) communicated
        via Alice's automatic bridge
```

**What this enables:**
- ✅ Offline festival coordination
- ✅ Remote friends stay connected to physical events
- ✅ Resilient communication infrastructure
- ✅ No configuration needed

---

### Journey 2: Disaster Relief Zone

**Scenario:** Earthquake damages cell towers. Relief workers need coordination.

```
1. Area affected: geohash "9q8yy" (5km x 5km)
   └─ 90% of cell towers down
   └─ 10% of people have satellite internet

2. Worker A (satellite internet):
   └─ Location enabled → auto-bridge for 9q8yy
   └─ Receives mesh messages from 50 nearby workers
   └─ Relays all to Nostr

3. Worker B (BLE only):
   └─ Broadcasts: "Medical supplies needed at City Hall"
   └─ Worker A bridges to Nostr

4. Command Center (distant, internet):
   └─ Monitoring Nostr channel for 9q8yy
   └─ Sees Worker B's request
   └─ Replies: "Helicopter dispatched, ETA 15 min"
   └─ Worker A bridges reply to mesh
   └─ Worker B receives via BLE

Result: Command center coordinates with field workers
        despite infrastructure damage
```

**What this enables:**
- ✅ Resilient emergency communication
- ✅ Remote coordination with isolated areas
- ✅ Leverages partial connectivity
- ✅ No special equipment needed

---

### Journey 3: Rural Community Network

**Scenario:** Village with limited internet. One cafe has WiFi.

```
1. Cafe owner (WiFi):
   └─ Enables location → geohash "dr5ru"
   └─ Phone acts as bridge 24/7 (charging, WiFi)

2. Villagers (BLE only):
   └─ Community group chat on mesh
   └─ Discuss: town meeting tonight

3. Cafe owner's phone:
   └─ Receives all mesh messages
   └─ Bridges to Nostr automatically

4. Expat villagers (worldwide):
   └─ Subscribe to Nostr channel "dr5ru"
   └─ See hometown conversations in real-time
   └─ Reply: "Send photos of the meeting!"

5. Cafe owner's phone:
   └─ Receives Nostr messages
   └─ Broadcasts to mesh
   └─ Villagers receive via BLE

Result: Global diaspora stays connected to hometown
        via single bridge node
```

**What this enables:**
- ✅ Global village communication
- ✅ Single internet point serves entire community
- ✅ Preserves local culture/connection
- ✅ Accessible technology

---

## Technical Design

### Eligibility Criteria (Bridge Activation)

A device acts as a bridge when ALL conditions are met:

```kotlin
fun isEligibleBridge(): Boolean {
    val hasInternet = hasInternetConnection()        // WiFi or cellular
    val batteryOk = getBatteryLevel() >= 10          // ≥10% battery
    val locationEnabled = locationMgr.isLocationServicesEnabled()
    val hasLocation = locationMgr.selectedChannel.value is ChannelID.Location

    return hasInternet && batteryOk && locationEnabled && hasLocation
}
```

**Rationale:**
- **Internet**: Required to reach Nostr relay
- **Battery ≥10%**: Same as Solana relay pattern, prevents battery drain
- **Location enabled**: User explicitly opted into location features
- **Has location**: Must be in a geohash timeline, not mesh

### Message Flow Logic

#### Direction 1: Mesh → Nostr (Bridge Mesh Messages)

```kotlin
fun onMeshMessageReceived(message: BitchatMessage) {
    viewModelScope.launch {
        // 1. Eligibility check
        if (!isEligibleBridge()) return@launch

        // 2. Get current geohash location
        val currentLocation = state.selectedLocationChannel.value
        if (currentLocation !is ChannelID.Location) return@launch

        // 3. Deduplicate: don't relay our own messages
        if (message.senderPeerID == meshService.myPeerID) return@launch

        // 4. Deduplicate: don't relay messages we already bridged
        if (recentlyBridged.contains(message.id)) return@launch
        recentlyBridged.add(message.id)

        // 5. Bridge to Nostr
        Log.d(TAG, "Bridging mesh→Nostr: ${message.content}")
        sendGeohashMessage(
            message.content,
            currentLocation.channel,
            message.senderPeerID,  // Preserve original sender
            message.sender          // Preserve nickname
        )
    }
}
```

#### Direction 2: Nostr → Mesh (Dual-Broadcast)

```kotlin
// In ChatViewModel.kt, line ~488
if (selectedLocationChannel is ChannelID.Location) {
    // Send to Nostr (existing)
    geohashViewModel.sendGeohashMessage(
        messageContent,
        selectedLocationChannel.channel,
        meshService.myPeerID,
        state.getNicknameValue()
    )

    // NEW: Also broadcast to mesh (1 line)
    meshService.sendMessage(messageContent, mentions, null)
}
```

### Deduplication Strategy

**Problem:** Avoid message loops (A relays to Nostr → B receives → B relays to mesh → A receives → loop)

**Solution:** Multiple layers of deduplication

1. **Sender check**: Never relay own messages (`senderPeerID == myPeerID`)
2. **Message ID tracking**: Keep LRU cache of recently bridged message IDs (size: 1000, TTL: 5 min)
3. **Direction isolation**:
   - Mesh→Nostr: only bridge mesh-originated messages
   - Nostr→Mesh: only broadcast Nostr-originated messages (via dual-broadcast)

```kotlin
private val recentlyBridged = object : LinkedHashMap<String, Long>(1000, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
        return size > 1000 || (eldest?.value ?: 0L) < System.currentTimeMillis() - 300_000
    }
}
```

---

## Security Considerations

### Privacy

**Considerations:**
- Bridge reveals user is at specific geohash location
- Messages are public (kind=20000 Nostr events)
- Mesh broadcasts are unencrypted by default

**Mitigations:**
- User explicitly enables location (informed consent)
- No bridge indicator in UI (passive, not advertising)
- Only bridge public messages, never private/encrypted
- Geohash precision limits exact location (5km x 5km typical)

**Code guard:**
```kotlin
// Never bridge private messages
if (message.channel == null && message.recipientPeerID != null) {
    return // Private message, don't bridge
}
```

### Spam Prevention

**Attack vectors:**
1. Malicious mesh user floods bridge with spam
2. Malicious Nostr user floods geohash with spam
3. Multiple bridges create duplicate messages

**Mitigations:**

1. **Rate limiting per sender**
```kotlin
private val senderRateLimits = mutableMapOf<String, RateLimiter>()

fun shouldBridge(senderPeerID: String): Boolean {
    val limiter = senderRateLimits.getOrPut(senderPeerID) {
        RateLimiter(maxMessages = 20, windowMs = 3600_000) // 20/hour
    }
    return limiter.tryAcquire()
}
```

2. **Battery gating**: Bridge stops at <10% battery (reduces spam window)

3. **Mesh TTL**: Existing mesh TTL=4 prevents infinite propagation

4. **Nostr PoW**: Existing PoW validation applies (if enabled by user)

5. **Message deduplication**: LRU cache prevents duplicate bridges

### Resource Consumption

**Concern:** Bridges use extra battery/bandwidth relaying messages

**Mitigations:**
- Battery gate: ≥10% required
- Bandwidth: Only relays messages from current geohash (localized)
- No UI overhead: Passive operation, no extra renders
- User control: Disabling location services disables bridge

**Estimated overhead:**
- Battery: ~2-5% extra drain per hour (if actively bridging)
- Bandwidth: ~1-5 KB per bridged message
- Typical usage: 5-20 messages/hour → ~5-100 KB/hour

---

## Implementation Plan

### Phase 1: Core Bridge Logic (~2 hours)

**File:** `GeohashViewModel.kt`

Add bridge function:
```kotlin
// Track recently bridged messages (dedupe)
private val recentlyBridged = /* LRU cache as above */

fun onMeshMessageReceived(message: BitchatMessage) {
    viewModelScope.launch {
        // Eligibility check
        if (!isEligibleBridge()) return@launch

        // Get current geohash
        val currentLocation = state.selectedLocationChannel.value
        if (currentLocation !is ChannelID.Location) return@launch

        // Deduplicate
        if (message.senderPeerID == meshService.myPeerID) return@launch
        if (recentlyBridged.contains(message.id)) return@launch
        recentlyBridged[message.id] = System.currentTimeMillis()

        // Bridge to Nostr
        Log.d(TAG, "Bridging mesh→Nostr: geohash=${currentLocation.channel.geohash}")
        sendGeohashMessage(
            message.content,
            currentLocation.channel,
            message.senderPeerID,
            message.sender
        )
    }
}

private fun isEligibleBridge(): Boolean {
    val hasInternet = hasInternetConnection()
    val batteryOk = getBatteryLevel() >= 10
    val locationEnabled = locationMgr.isLocationServicesEnabled()
    return hasInternet && batteryOk && locationEnabled
}

private fun hasInternetConnection(): Boolean {
    val cm = getApplication<Application>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun getBatteryLevel(): Int {
    val bm = getApplication<Application>()
        .getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
```

**Estimated:** ~25 lines

---

### Phase 2: Dual-Broadcast (Nostr → Mesh) (~15 minutes)

**File:** `ChatViewModel.kt` line ~488

Modify geohash message sending:
```kotlin
// Existing: Send to Nostr
geohashViewModel.sendGeohashMessage(
    messageContent,
    selectedLocationChannel.channel,
    meshService.myPeerID,
    state.getNicknameValue()
)

// NEW: Also broadcast to local mesh
meshService.sendMessage(messageContent, mentions, null)
```

**Estimated:** 1 line

---

### Phase 3: Hook Bridge into Message Flow (~30 minutes)

**File:** `MeshDelegateHandler.kt`

Find `onMessageReceived` callback:
```kotlin
override fun onMessageReceived(message: BitchatMessage) {
    // ... existing logic ...

    // NEW: Bridge to Nostr if eligible
    try {
        val geohashVM = /* resolve GeohashViewModel reference */
        geohashVM?.onMeshMessageReceived(message)
    } catch (e: Exception) {
        Log.e(TAG, "Bridge error: ${e.message}")
    }
}
```

**Estimated:** ~5 lines

---

### Phase 4: Testing & Validation (~2 hours)

**Test scenarios:**
1. Bridge activation when location enabled + internet available
2. Bridge deactivation when battery <10%
3. Message flow: mesh → Nostr → distant user
4. Message flow: distant user → Nostr → mesh
5. Deduplication: no message loops
6. Rate limiting: max 20 messages/hour per sender
7. Privacy: private messages not bridged

**Test devices:**
- Device A: Internet + BLE (bridge)
- Device B: BLE only (mesh-only user)
- Device C: Internet only (distant Nostr user)

---

### Phase 5: Documentation & ADR (~1 hour)

Create ADR documenting:
- Design rationale
- Alternatives considered
- Security analysis
- Performance impact
- User privacy implications

---

## Related Documents

- `ADR-001-channel-scoping.md` - Channel timeline architecture
- `MESH_ARCHITECTURE.md` - Mesh network implementation
- `DELIVERY_MECHANISMS.md` - Message routing patterns
- `exit-relay.md` - Similar eligibility pattern (not yet implemented)
- `LocationChannelManager.kt` - Geohash computation
- `GeohashMessageHandler.kt` - Nostr message handling
- `MessageRouter.kt` - Transport routing logic

---

## Alternatives Considered

### Alternative 1: Explicit Bridge Mode Toggle

**Design:** Add UI toggle "Enable Bridge Mode" in location settings.

**Pros:**
- User explicitly opts into bridging
- Clear indication of bridge status
- User control over resource usage

**Cons:**
- Extra UI complexity
- Users may not understand benefit
- Reduces bridge availability (most won't enable)
- Requires explanation/education

**Rejected because:** Automatic bridging based on location opt-in is simpler and increases bridge availability. Privacy preserved via existing location permission.

---

### Alternative 2: Dedicated Bridge Message Type

**Design:** Create new message type `GEOHASH_BRIDGE` (0x32) for bridged messages.

**Pros:**
- Clear protocol distinction
- Easier to track bridge metrics
- Can add bridge-specific metadata

**Cons:**
- Protocol change required
- Not backwards compatible
- Requires iOS coordination
- Adds complexity without clear benefit

**Rejected because:** Existing broadcast format works. No protocol change needed. Simpler = better.

---

### Alternative 3: Opt-in Per Message

**Design:** Add "Bridge to Nostr" checkbox when sending mesh messages.

**Pros:**
- Granular user control
- Explicit consent per message
- Clear privacy model

**Cons:**
- UX friction on every message
- Users may not understand when to use
- Reduces bridge effectiveness
- Inconsistent message delivery

**Rejected because:** Too much friction. Automatic bridging within location context is sufficient privacy boundary.

---

### Alternative 4: Smart Bridge Selection

**Design:** Bridge coordination protocol where multiple eligible bridges elect a primary.

**Pros:**
- Reduces duplicate messages
- More efficient bandwidth usage
- Distributes load

**Cons:**
- Complex coordination protocol needed
- Adds message overhead
- Failure modes more complex
- Premature optimization

**Rejected because:** Simple deduplication via message ID is sufficient. YAGNI principle - add coordination only if duplication becomes a real problem.

---

## Performance Characteristics

### Resource Usage per Bridge

| Resource | Idle | Light (5 msg/hr) | Heavy (20 msg/hr) |
|----------|------|------------------|-------------------|
| Battery drain | 0% | ~1-2% / hour | ~3-5% / hour |
| Network data | 0 KB | ~5-10 KB / hour | ~20-40 KB / hour |
| Memory | ~1 KB | ~5 KB | ~20 KB |
| CPU | 0% | <1% | ~1-2% |

**Assumptions:**
- Average message size: 200 bytes
- Nostr overhead: 500 bytes per event
- Mesh broadcast: 250 bytes per packet
- Deduplication cache: 1000 messages × 20 bytes = 20 KB

### Scalability Analysis

**Network size:** N mesh users, M bridges, K distant Nostr users

**Message load:**
- Each mesh message → relayed by M bridges → M Nostr events (deduplicated by relay)
- Each Nostr message → received by M bridges → M mesh broadcasts (deduplicated by mesh)

**Worst case:**
- 100 mesh users × 10 messages/hour = 1000 messages
- 10 bridges × 1000 messages = 10,000 bridge operations
- Deduplication reduces to ~1000 actual Nostr posts + ~1000 mesh broadcasts

**Optimization:**
- Nostr relay deduplicates by event ID
- Mesh deduplicates by message ID + TTL
- Rate limiting: 20 msg/hour per sender limits spam

**Conclusion:** Scales to ~100 mesh users per geohash with 10 bridges before optimization needed.

---

## Privacy Impact Analysis

### Information Revealed by Bridge

**Before bridge:**
- User has location enabled (already known)
- User is at geohash X (already revealed to Nostr when sending)

**After bridge:**
- Same as before + user is actively relaying messages
- Bridge status not explicitly indicated to others
- Messages show original sender, not bridge

**Mitigation:**
- Bridge is passive (no "I'm a bridge" announcement)
- Nostr events don't reveal bridge identity
- Mesh broadcasts don't show bridge path

### Metadata Analysis

**Mesh → Nostr bridge reveals:**
- Mesh user exists at location X (message content → Nostr)
- Message timing (timestamp)
- Message content (public anyway)

**NOT revealed:**
- Bridge identity (events show original sender)
- Physical proximity (geohash is 5km × 5km area)
- Other mesh users (only bridged messages visible)

**Nostr → Mesh bridge reveals:**
- Distant user knows about geohash X (already true when posting)
- Message timing (timestamp)
- Message content (public anyway)

**NOT revealed:**
- Bridge identity (mesh broadcast anonymous)
- Mesh network size
- Other mesh users

**Conclusion:** Privacy impact is minimal. Bridge reveals no new information beyond what public messaging already reveals.

---

## Future Enhancements

### Enhancement 1: Bridge Metrics (v2)

Track and display bridge statistics:
- Messages bridged today: 127
- Data relayed: 45 KB
- Users helped: 8
- Tip earnings: 2,000 sats (if tipping added)

**UI location:** Settings → Location → Bridge Stats

**Implementation:** ~50 lines, SharedPreferences storage

---

### Enhancement 2: Selective Bridging (v2)

Allow users to configure:
- Bridge only when WiFi (not cellular)
- Bridge only when battery >20%
- Bridge only specific channels

**UI location:** Settings → Location → Bridge Settings

**Implementation:** ~100 lines, settings UI + gating logic

---

### Enhancement 3: Bridge Reputation (v3)

Track reliable bridges:
- Uptime percentage
- Message success rate
- Response latency

Display bridge quality in UI, prefer high-reputation bridges for special messages.

**Implementation:** ~200 lines, requires coordination protocol

---

### Enhancement 4: Incentive Layer (v3)

Optional tipping for bridge services:
- Users can tip bridges automatically
- Bridges display tip QR codes
- Gamification: "Top bridge of the day"

**Implementation:** ~500 lines, requires Solana integration (Lightning Network alternative)

---

## Open Questions

### Q1: Should bridges announce themselves?

**Current:** No announcement, passive operation

**Alternative:** Broadcast "I'm a bridge" presence message

**Trade-offs:**
- Pro: Users know help is available
- Con: Privacy concern (reveals bridge)
- Con: May encourage abuse/targeting

**Decision:** Keep passive for MVP. Add announcement only if user feedback indicates confusion.

---

### Q2: How to handle bridge conflicts?

**Scenario:** Multiple bridges relay same message → duplicate Nostr events

**Current solution:** Nostr relay deduplicates by event ID

**Potential issue:** Different bridges create different event IDs for same message

**Options:**
1. Accept duplicates (Nostr relay handles)
2. Deterministic event ID (requires coordination)
3. Bridge coordination protocol (complex)

**Decision:** Accept duplicates for MVP. Nostr relays will deduplicate. Monitor real-world impact before adding complexity.

---

### Q3: Bridge battery drain acceptable?

**Estimated:** 3-5% extra drain per hour during active bridging

**Question:** Is this acceptable default behavior?

**Options:**
1. Default ON (current design)
2. Default OFF with opt-in
3. Adaptive (enable when battery >50%, disable when <20%)

**Decision:** Default ON with ≥10% gate. User explicitly enabled location, implies willingness to participate in location-based network. Monitor battery complaints.

---

## Success Metrics

### MVP Success Criteria (4 weeks)

1. **Technical:**
   - ✅ Bridge activates when location enabled + internet available
   - ✅ Mesh messages successfully relayed to Nostr (>95% success rate)
   - ✅ Nostr messages successfully broadcast to mesh (>95% success rate)
   - ✅ No message loops detected
   - ✅ Battery drain <5% per hour

2. **Usage:**
   - 🎯 10+ users enable location services
   - 🎯 5+ active bridges online simultaneously
   - 🎯 50+ messages bridged per day
   - 🎯 3+ geohash locations with bridge coverage

3. **User feedback:**
   - 🎯 0 privacy complaints
   - 🎯 <2 battery drain complaints
   - 🎯 Positive feedback on "internet without internet" experience

### Long-term Success Metrics (6 months)

1. **Adoption:**
   - 100+ users with location enabled
   - 50+ geohash locations with bridge coverage
   - 1000+ messages bridged per day

2. **Use cases:**
   - 3+ verified disaster relief scenarios
   - 10+ community events using bridge
   - 5+ rural communities with regular bridge usage

3. **Technical maturity:**
   - Bridge uptime >95%
   - Message success rate >99%
   - Zero security incidents

---

## Rollout Plan

### Stage 1: Internal Testing (Week 1)

- Deploy to development team devices (5 devices)
- Test all 3-device scenarios (bridge, mesh-only, Nostr-only)
- Validate deduplication, rate limiting, battery gating
- Monitor logs for errors

**Gate:** All test scenarios pass, no critical bugs

---

### Stage 2: Beta Release (Week 2-3)

- Deploy behind feature flag `ENABLE_GEOHASH_BRIDGE`
- Invite 20 beta testers
- Provide testing guide with scenarios
- Collect feedback via in-app form
- Monitor bridge metrics remotely

**Gate:** <5% battery drain, 0 privacy complaints, positive feedback

---

### Stage 3: General Availability (Week 4)

- Remove feature flag
- Enable for all users with location permission
- Announce in release notes
- Create user guide: "How geohash bridging works"
- Monitor crash reports, battery complaints

**Gate:** Stable for 1 week, <1% crash rate increase

---

### Stage 4: Optimization (Week 5-8)

- Analyze bridge metrics
- Identify bottlenecks
- Optimize deduplication if needed
- Add selective bridging settings if requested
- Plan v2 enhancements based on usage patterns

---

## Conclusion

This design delivers a **minimal, pragmatic implementation** of location-based mesh-to-internet bridging:

### Key Strengths

1. **Minimal code**: ~31 lines (vs 300+ for exit relay)
2. **No protocol changes**: Reuses existing formats
3. **Automatic**: No user configuration required
4. **Backwards compatible**: Doesn't affect existing users
5. **Privacy-preserving**: No new information revealed
6. **Resource-efficient**: <5% battery drain per hour

### Implementation Summary

| Component | Type | LOC | Time |
|-----------|------|-----|------|
| Bridge logic | New method | 25 | 2h |
| Dual-broadcast | Modified | 1 | 15m |
| Message hook | Modified | 5 | 30m |
| **Total** | | **31** | **~3h** |

### Next Steps

1. ✅ Review this design document
2. ✅ Get stakeholder approval
3. 🔜 Create ADR documenting decision
4. 🔜 Implement Phase 1 (bridge logic)
5. 🔜 Implement Phase 2 (dual-broadcast)
6. 🔜 Implement Phase 3 (message hook)
7. 🔜 Begin internal testing (Stage 1)

---

**Document Status:** PROPOSED - Ready for review
**Estimated Time to MVP:** 1 week (3 hours implementation + 4 hours testing)
**Estimated Code:** ~31 lines new, ~6 lines modified
**Risk Level:** Low (minimal changes, no protocol modifications)

---

**Revolutionary Impact:** This feature enables true "internet without internet" communication. Anyone with connectivity becomes an automatic relay for their physical location, creating a resilient, location-based communication infrastructure that works even when internet/cellular fails.
