# Exit Relay Feature: Design Document

**Status:** NOT YET IMPLEMENTED
**Version:** 2.1
**Last Updated:** 2025-10-25
**Author:** Design Review

---

## Overview

This document outlines a **simplified, pragmatic MVP** for an "Exit Relay" feature that enables BitChat mesh users to send Solana transactions while offline.

Key MVP decisions:
- Chain: Solana devnet (fixed, non-configurable)
- RPC fast-path: sendTransaction with skipPreflight=true, preflightCommitment=processed
- Blockhash: durable nonce (offline signing) — no interactive blockhash handshake in MVP
- Relay policy: allow Wi‑Fi or cellular; battery must be ≥10%

Goals
- Enable offline users to submit Solana transfers via mesh with minimal roundtrips
- Keep implementation small and reuse existing mesh infrastructure
- Deliver fast user feedback (signature receipt) over the mesh

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

3. Create Signed Transaction (Durable Nonce)
   ├─ Standard Solana transfer using a durable nonce account
   ├─ From: Alice's Solana address (signer + nonce authority)
   ├─ To: Bob's Solana address; Amount: 0.5 SOL
   └─ Uses cached durable nonce value; if none available, show error and do not send

4. Wrap in RELAY_REQUEST Message
   ├─ Type: 0x30 (new message type)
   ├─ Payload (TLV, see Protocol): request_id + tx_bytes + flags
   ├─ Packet-level signature: REQUIRED (HAS_SIGNATURE)
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
   ├─ Connectivity: Wi‑Fi or cellular ✓
   ├─ Battery: ≥10% ✓
   └─ Proceed with relay

3. Extract Transaction Bytes
   └─ Parse: 250-byte signed transaction

4. HTTP POST to Solana RPC
   ├─ POST https://api.devnet.solana.com (fixed)
   ├─ Body (JSON-RPC 2.0): {"jsonrpc":"2.0","id":"<request_id>","method":"sendTransaction",
   │   "params":["<base64_tx>",{"skipPreflight":true,"preflightCommitment":"processed"}]}
   ├─ Response: {"result": "5K3x9pE...signature"}
   └─ Time: ~1–3 seconds

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
│  SolanaPaymentQueue → Create signed tx (durable nonce)   │
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
│  SolanaRelayHandler.handleRequest() ← NEW (~150 lines)   │
│         ↓                                                │
│  OkHttpProvider.post() ← EXISTING                       │
│         ↓                                                │
│  POST to Solana RPC (devnet, skipPreflight)              │
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
- Economic cost (tip optional, later)
- Rate limiting (max 20 requests/hour per peer)
- Battery constraints (no relay if <10%)
- Connectivity allowed: Wi‑Fi or cellular

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

## Durable Nonce Strategy (MVP)

Rationale
- Solana signatures cover the recentBlockhash; you cannot add a blockhash after the fact. To sign offline without a network roundtrip, use a durable nonce.

Assumptions
- Each user provisions a durable nonce account on devnet while online (one‑time)
- The app caches the current nonce value locally; it remains valid until consumed
- Alice is the nonce authority and signs the AdvanceNonceAccount instruction

Behavior
- If a fresh cached nonce is available, Alice constructs and signs the transaction offline
- RELAY_REQUEST carries the fully signed tx bytes; the relay only submits and returns a signature
- If the nonce was already consumed or unavailable, the relay returns a receipt with ERROR_CODE and message; no handshake fallback in MVP

Note: A “relayer partial sign” cannot fix missing blockhash; signatures commit to the blockhash. A two‑step blockhash handshake is a future option, not in MVP.

---

## Android Implementation Plan (High Level)

Core
- Add MessageType entries (RELAY_REQUEST=0x30, RELAY_RECEIPT=0x31) and TLV codecs
- New SolanaRelayHandler (~150 LOC): parse request, eligibility checks (connectivity, battery, rate), base64 encode, JSON‑RPC submit, emit receipt
- Hook into PacketProcessor/MessageHandler to dispatch new types

Eligibility gating
- Connectivity: Wi‑Fi or cellular via ConnectivityManager
- Battery: BatteryManager percentage ≥10%
- Rate limit: per‑sender ≤20/hour; simple in‑memory window

UX
- Offline detection: prompt to request relay; show spinner with network size
- On success: show signature and devnet explorer link
- Timeout: mark failed if no receipt within ~30s; allow manual retry

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
3. 🔜 Create ADR documenting MVP decisions (devnet, durable nonce, skipPreflight, Wi‑Fi/cellular, ≥10% battery)
4. 🔜 Begin Phase 1 implementation behind a feature flag

---

**Document Status:** ACTIVE - Ready for implementation
**Estimated Time to MVP:** 2 weeks
**Estimated Code:** ~300 lines new, ~50 lines modified
