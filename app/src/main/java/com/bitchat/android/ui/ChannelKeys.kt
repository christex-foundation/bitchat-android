package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel

/**
 * Helper object for managing composite channel keys.
 *
 * Channels are scoped by timeline to prevent collisions:
 * - Mesh: "mesh:#gaming"
 * - Geohash: "geo:9q8yy:#gaming"
 *
 * This ensures that the same channel name (#gaming) on different timelines
 * (mesh vs geohash) or different geohashes (SF vs NYC) remain independent.
 */
object ChannelKeys {

    /**
     * Create a composite key for a channel based on the current timeline.
     *
     * @param timeline The current timeline (Mesh or Location)
     * @param channelName The channel name (e.g., "#gaming")
     * @return Composite key (e.g., "mesh:#gaming" or "geo:9q8yy:#gaming")
     */
    fun create(timeline: ChannelID?, channelName: String): String = when (timeline) {
        is ChannelID.Mesh, null -> "mesh:$channelName"
        is ChannelID.Location -> "geo:${timeline.channel.geohash}:$channelName"
    }

    /**
     * Extract the channel name from a composite key.
     *
     * @param key Composite key (e.g., "mesh:#gaming" or "geo:9q8yy:#gaming")
     * @return Channel name (e.g., "#gaming")
     */
    fun parseChannelName(key: String): String = key.substringAfterLast(":")

    /**
     * Extract the geohash from a geohash composite key.
     *
     * @param key Composite key (e.g., "geo:9q8yy:#gaming")
     * @return Geohash (e.g., "9q8yy") or null if not a geohash key
     */
    fun parseGeohash(key: String): String? =
        if (key.startsWith("geo:")) key.removePrefix("geo:").substringBefore(":") else null

    /**
     * Check if a key is for a mesh channel.
     */
    fun isMesh(key: String): Boolean = key.startsWith("mesh:")

    /**
     * Check if a key is for a geohash channel.
     */
    fun isGeo(key: String): Boolean = key.startsWith("geo:")

    /**
     * Normalize legacy keys that don't have timeline prefix.
     * Old format: "#gaming" -> "mesh:#gaming"
     *
     * @param key Key to normalize
     * @return Normalized key with timeline prefix
     */
    fun normalize(key: String): String {
        return if (key.contains(":")) key else "mesh:$key"
    }

    fun levelForGeohashLength(length: Int): GeohashChannelLevel = when {
        length <= 2 -> GeohashChannelLevel.REGION
        length in 3..4 -> GeohashChannelLevel.PROVINCE
        length == 5 -> GeohashChannelLevel.CITY
        length == 6 -> GeohashChannelLevel.NEIGHBORHOOD
        length == 7 -> GeohashChannelLevel.BLOCK
        else -> GeohashChannelLevel.BUILDING
    }

    fun displayName(key: String): String {
        val channelName = parseChannelName(key)
        return if (isGeo(key)) {
            val geohash = parseGeohash(key)
            if (geohash.isNullOrEmpty()) channelName else "$channelName | #$geohash"
        } else {
            channelName
        }
    }
}
