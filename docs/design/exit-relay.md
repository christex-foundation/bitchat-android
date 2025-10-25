# Exit Relay Feature: Design Document

**Status:** ACTIVE - Simplified MVP Approach
**Version:** 2.0
**Last Updated:** 2025-10-24
**Author:** Design Review

---

## Overview

This document outlines a **simplified, pragmatic approach** to building an "Exit Relay" feature that enables BitChat mesh network users to send Solana transactions even when offline.

### The Core Idea (Simple Version)

**Problem:** Alice wants to send 0.5 SOL to Bob, but she has no internet. Charlie is nearby and has WiFi.

**Solution:** Alice's phone sends a "help me send this" message through the mesh. Charlie's phone picks it up, posts it to Solana RPC, and sends back "done!" to Alice.

---

## End-to-End Flow

### Part 1: Alice Creates Request (Offline)

```
1. User Action
   ├─ Opens Solana payment screen
   ├─ Enters: @bob, 0.5 SOL, "Coffee thanks!"
   └─ Taps "Send"

2. App Detects Offline
   ├─ Checks connectivity: No WiFi, no cellular
   └─ Shows: "You're offline. Request relay from nearby users?"

3. Create Signed Transaction
   ├─ Standard Solana transaction (250 bytes)
   ├─ From: Alice's Solana address
   ├─ To: Bob's Solana address
   └─ Amount: 0.5 SOL

4. Wrap in RELAY_REQUEST Message
   ├─ Type: 0x30 (new message type)
   ├─ Payload: {
   │    transaction_bytes: <250 bytes>,
   │    request_id: <16-byte UUID>
   │  }
   └─ Broadcast through existing mesh

5. UI Updates
   └─ "⏳ Waiting for relay... 3 nearby users"
```

---

### Part 2: Message Travels Through Mesh

```
Alice's phone (BLE)
    ↓
Peer 1 (relay)
    ↓
Peer 2 (relay)
    ↓
Charlie's phone (WiFi!) ← Receives request
```

---

### Part 3: Charlie Relays (Exit Relay)

```
1. Receive Message
   ├─ PacketProcessor → MessageHandler
   └─ Identifies: RELAY_REQUEST

2. Check Eligibility
   ├─ Am I an "Exit Relay"? ✓
   ├─ Do I have internet (WiFi)? ✓
   ├─ Is battery OK (>20%)? ✓
   └─ Proceed with relay

3. Extract Transaction Bytes
   └─ Parse: 250-byte signed transaction

4. HTTP POST to Solana RPC
   ├─ POST https://api.mainnet-beta.solana.com
   ├─ Body: {"method": "sendTransaction", "params": ["base64_tx"]}
   ├─ Response: {"result": "5K3x9pE...signature"}
   └─ Time: ~1-3 seconds

5. Create Receipt
   ├─ Type: 0x31 (RELAY_RECEIPT)
   ├─ Payload: {
   │    tx_signature: "5K3x9pE...",
   │    request_id: <same UUID>
   │  }
   └─ Broadcast back through mesh

6. Track Stats
   └─ "You relayed a transaction for @alice"
```

---

### Part 4: Alice Receives Confirmation

```
1. Receive RELAY_RECEIPT
   ├─ PacketProcessor → MessageHandler
   └─ Match request_id to pending request

2. Update UI
   ├─ Before: "⏳ Waiting for relay..."
   ├─ After:  "✅ Relayed by @charlie"
   └─ Show: "Tx: 5K3x9pE... [View on Solscan]"

3. Show Tip Prompt (Later)
   └─ "Tip @charlie for helping?"
      [2000 lamports] [Send Tip]
```

---

## Architecture Integration

### Visual Overview

```
┌──────────────────────────────────────────────────────────┐
│                    ALICE (Offline)                       │
├──────────────────────────────────────────────────────────┤
│  SolanaPaymentQueue → Create signed tx (250 bytes)       │
│         ↓                                                │
│  Wrap in RELAY_REQUEST message (type 0x30)              │
│         ↓                                                │
│  BluetoothMeshService.broadcast() ← EXISTING            │
└──────────────────┬───────────────────────────────────────┘
                   ↓
         ┌─────────────────┐
         │  Mesh Network   │ ← EXISTING (no changes)
         │  (BLE hops)     │
         └─────────┬───────┘
                   ↓
┌──────────────────────────────────────────────────────────┐
│                  CHARLIE (Has WiFi)                      │
├──────────────────────────────────────────────────────────┤
│  PacketProcessor → MessageHandler → NEW HANDLER          │
│         ↓                                                │
│  SolanaRelayHandler.handleRequest() ← NEW (~150 lines)  │
│         ↓                                                │
│  OkHttpProvider.post() ← EXISTING                       │
│         ↓                                                │
│  POST to Solana RPC                                      │
│         ↓                                                │
│  Get signature: "5K3x9pE..."                             │
│         ↓                                                │
│  Create RELAY_RECEIPT message                            │
│         ↓                                                │
│  BluetoothMeshService.broadcast() ← EXISTING            │
└──────────────────┬───────────────────────────────────────┘
                   ↓
         ┌─────────────────┐
         │  Mesh Network   │ ← EXISTING (no changes)
         └─────────┬───────┘
                   ↓
┌──────────────────────────────────────────────────────────┐
│                    ALICE (Offline)                       │
├──────────────────────────────────────────────────────────┤
│  PacketProcessor → MessageHandler                        │
│         ↓                                                │
│  Match request_id, update UI                             │
│         ↓                                                │
│  "✅ Relayed by @charlie"                                │
└──────────────────────────────────────────────────────────┘
```

---

## Example User Journeys

### Journey 1: Offline Payment at Festival

**Alice** is at a music festival (mesh-only, no cell signal). She wants to send Bob 0.1 SOL for coffee.

```

1. Alice requests relay (2 taps)
2. Charlie (20m away, has WiFi) relays automatically
3. Transaction confirms in ~8 seconds
4. Alice sees "✅ Relayed by @charlie"
5. Later, Alice tips Charlie 2,000 lamports (~$0.0002)
```

**What this enables:**
- ✅ Offline Solana payments possible
- ✅ Community cooperation
- ✅ Social/voluntary payment model
- ✅ Real-world utility for mesh networks

---

### Journey 2: Remote Work Team

**Development team** at remote location with spotty internet needs to pay contributors.

```
1. Project lead (offline in basement) queues 10 payments
2. Each tagged for relay
3. Broadcasts through mesh
4. Team member upstairs (near window, 1 bar LTE) relays all 10
5. All contributors receive payments
6. Team member gets social credit + optional tips
```

**What this enables:**
- ✅ Teams operate with partial connectivity
- ✅ One online node enables entire group
- ✅ Resilient payment infrastructure
- ✅ Cooperative economics

---

## Security Considerations

### Spam Prevention

**Mechanisms:**
- Economic cost (must pay tip, even if small)
- Rate limiting (max 20 requests/hour per peer)
- Battery constraints (no relay if <20%)
- WiFi-only default (preserve cellular data)

**Implementation:** Check recent request count, battery level, and connectivity before accepting relay requests.

---

### Proof Verification (Optional)

**For sender to verify receipt:**
- Query Solana RPC `getTransaction` method with signature
- Verify transaction exists on-chain

**When to verify:**
- High-value transactions (>1 SOL)
- Suspicious relayers
- User-initiated check

---

### Privacy

**Considerations:**
- Solana transactions are public anyway (no privacy loss)
- Private messages MUST NOT use relay (enforce in code)
- Relay metadata shouldn't leak sensitive info
- Optional: Tor routing via existing `TorManager`

**Guard:** Never relay private/encrypted messages; only allow broadcast or Solana-specific message types.

---

## Related Documents

- `SOLANA_INTEGRATION.md` - Planned Solana features
- `SOURCE_ROUTING.md` - Source-based routing (could optimize relay paths)
- `sync.md` - Gossip sync protocol (similar patterns)
- `file_transfer.md` - Large payload handling (similar to relay)

---

## Conclusion

This simplified approach prioritizes:

1. **Minimal code** (~300 lines vs thousands)
2. **Fast implementation** (2 weeks vs 2-3 months)
3. **Low risk** (no complex economics)
4. **User validation** (prove demand first)
5. **Iterative improvement** (add complexity only if needed)

**Key Insight:** The technical relay is trivial. The economic layer is complex. Ship the simple part first, prove it works, then add automation only if usage patterns justify it.

**Next Steps:**
1. ✅ Review this simplified design
2. ✅ Get stakeholder approval
3. 🔜 Create ADR documenting decisions
4. 🔜 Begin Phase 1 implementation

---

**Document Status:** ACTIVE - Ready for implementation
**Estimated Time to MVP:** 2 weeks
**Estimated Code:** ~300 lines new, ~50 lines modified
