# ADR-001: Channel Scoping by Timeline

**Date**: October 22, 2025
**Status**: ✅ Implemented
**Authors**: System Architecture Team

---

## Context

BitChat supports two independent communication timelines:
- **Mesh**: Local Bluetooth network (offline-capable)
- **Geohash**: Internet-based location channels via Nostr relays

Channels were stored as `Set<String>` with just the channel name (e.g., `"#gaming"`). This caused namespace collisions when users joined the same channel name on different timelines - all messages mixed together regardless of whether they came from mesh or a specific geohash location.

### Problem Example
User joins `#gaming` on mesh, then joins `#gaming` while viewing geohash `9q8yy`:
- Expected: Two independent channels with separate message histories
- Actual: Single channel with mixed messages from both timelines

This violated the fundamental isolation guarantee between timelines.

---

## Decision

Implement **composite keys with timeline namespacing** using the format:
```
{timeline-type}:{timeline-identifier}:{channel-name}

Examples:
- mesh:#gaming
- geo:9q8yy:#gaming
- geo:dr5ru:#gaming
```

### Key Design Choices

1. **String-based composite keys** over data classes
   - Pros: No schema changes, works with existing `Set<String>` storage
   - Cons: Requires parsing, potential for string manipulation errors
   - Mitigation: Centralized `ChannelKeys` helper object

2. **Timeline identifier in key** over separate storage buckets
   - Pros: Scales to unlimited timelines, simple mental model
   - Cons: Keys are longer
   - Rationale: Clarity and scalability over micro-optimization

3. **Lazy migration** over bulk migration
   - Old keys without `:` prefix automatically normalized to `mesh:` on load
   - Zero downtime, zero migration complexity

### Implementation Components

**New:**
- `ChannelKeys.kt` - Helper object for all key operations (create, parse, validate)
- `ChannelKeysTest.kt` - 17 unit tests

**Modified:**
- `ChannelManager` - Accepts `timeline` parameter, creates composite keys
- `MessageManager` - Added `parseChannelInfo()` for message routing
- `MeshDelegateHandler` - Routes to composite keys, supports legacy `message.channel` field
- `GeohashMessageHandler` - Parses channels from Nostr event content
- `ChatViewModel` - Timeline-aware sending, prepends channel name to wire content
- `GeohashViewModel` - Timeline switch handler validates current channel
- `SidebarComponents` - Groups channels by timeline with visual sections
- `DataManager` - Lazy normalization of legacy keys
- `CommandProcessor` - Timeline context for `/join`, user-friendly names for `/channels`

---

## Consequences

### Positive

✅ **Namespace Isolation** - Same channel name on different timelines stay completely separate
✅ **Scalability** - Pattern extends to future timeline types (satellite, LoRa, etc.)
✅ **Backward Compatible** - Automatic migration, supports legacy message format
✅ **Type Safe** - Reuses existing `ChannelID` sealed class
✅ **Clean Abstractions** - `ChannelKeys` helper prevents duplication
✅ **No Schema Changes** - Still `Set<String>` persistence

### Negative

⚠️ **Key Length** - Composite keys are longer than simple names
⚠️ **String Parsing** - Requires parsing overhead (mitigated by helper object)
⚠️ **Migration Surface** - All channel operations updated to use composite keys

### Neutral

- UI now shows timeline context (MESH/GEO sections in sidebar)
- Wire protocol changed: mesh sends include channel prefix in content
- Legacy clients using `message.channel` field still supported

---

## Alternatives Considered

### 1. Separate Storage Per Timeline
Store mesh channels and geo channels in different `Set<String>` collections.

**Rejected because:**
- Doesn't scale beyond 2 timelines
- Requires conditional logic throughout codebase
- Can't support same geohash in multiple granularities

### 2. Data Class-Based Keys
Use sealed class hierarchy for type-safe channel identifiers.

**Rejected because:**
- Requires schema migration (SharedPreferences can't persist sealed classes natively)
- Complicates JSON serialization
- Overkill for current needs

### 3. Channel-Level Timeline Field
Add `timeline: ChannelID` field to channel metadata.

**Rejected because:**
- Requires parallel data structures (channel list + timeline mapping)
- Harder to query "all channels for timeline X"
- More complex state synchronization

---

## Implementation Notes

### Wire Protocol Impact

**Mesh Messages:**
- Before: Sent as `"hello"` with `channel="#gaming"` field
- After: Sent as `"#gaming hello"` with `channel=null`
- Receiving: Supports both formats for legacy compatibility

**Geohash Messages:**
- Content format: `"#gaming hello"` in Nostr event
- Routing: Parses channel from content, stores to `geo:{geohash}:{channel}`

### Timeline Switching

When user switches timelines (mesh → geohash or between geohashes), the system:
1. Checks if current channel is valid for new timeline
2. If invalid: exits channel view, returns to main timeline
3. If valid: stays in channel (e.g., switching between geo channels with same geohash)

### UI/UX Changes

- Sidebar groups channels: "MESH 🔵" and "GEO {geohash} 🌐" sections
- `/channels` command shows clean names without prefixes
- Clicking channel automatically switches to correct timeline

---

## References

- Implementation PR: [Link to PR]
- Original proposal: This document
- Related: `ARCHITECTURE.md` - Data Storage & Persistence section
