package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelKeysTest {

    @Test
    fun `create with Mesh timeline returns mesh prefix`() {
        val key = ChannelKeys.create(ChannelID.Mesh, "#gaming")
        assertEquals("mesh:#gaming", key)
    }

    @Test
    fun `create with null timeline defaults to mesh`() {
        val key = ChannelKeys.create(null, "#gaming")
        assertEquals("mesh:#gaming", key)
    }

    @Test
    fun `create with Location timeline returns geo prefix with geohash`() {
        val geohash = GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")
        val timeline = ChannelID.Location(geohash)
        val key = ChannelKeys.create(timeline, "#gaming")
        assertEquals("geo:9q8yy:#gaming", key)
    }

    @Test
    fun `parseChannelName extracts channel from mesh key`() {
        val channelName = ChannelKeys.parseChannelName("mesh:#gaming")
        assertEquals("#gaming", channelName)
    }

    @Test
    fun `parseChannelName extracts channel from geo key`() {
        val channelName = ChannelKeys.parseChannelName("geo:9q8yy:#gaming")
        assertEquals("#gaming", channelName)
    }

    @Test
    fun `parseGeohash returns geohash from geo key`() {
        val geohash = ChannelKeys.parseGeohash("geo:9q8yy:#gaming")
        assertEquals("9q8yy", geohash)
    }

    @Test
    fun `parseGeohash returns null for mesh key`() {
        val geohash = ChannelKeys.parseGeohash("mesh:#gaming")
        assertNull(geohash)
    }

    @Test
    fun `parseGeohash returns null for invalid key`() {
        val geohash = ChannelKeys.parseGeohash("#gaming")
        assertNull(geohash)
    }

    @Test
    fun `isMesh returns true for mesh keys`() {
        assertTrue(ChannelKeys.isMesh("mesh:#gaming"))
    }

    @Test
    fun `isMesh returns false for geo keys`() {
        assertFalse(ChannelKeys.isMesh("geo:9q8yy:#gaming"))
    }

    @Test
    fun `isMesh returns false for invalid keys`() {
        assertFalse(ChannelKeys.isMesh("#gaming"))
    }

    @Test
    fun `isGeo returns true for geo keys`() {
        assertTrue(ChannelKeys.isGeo("geo:9q8yy:#gaming"))
    }

    @Test
    fun `isGeo returns false for mesh keys`() {
        assertFalse(ChannelKeys.isGeo("mesh:#gaming"))
    }

    @Test
    fun `isGeo returns false for invalid keys`() {
        assertFalse(ChannelKeys.isGeo("#gaming"))
    }

    @Test
    fun `normalize converts legacy format to mesh prefix`() {
        val normalized = ChannelKeys.normalize("#gaming")
        assertEquals("mesh:#gaming", normalized)
    }

    @Test
    fun `normalize preserves already normalized mesh keys`() {
        val normalized = ChannelKeys.normalize("mesh:#gaming")
        assertEquals("mesh:#gaming", normalized)
    }

    @Test
    fun `normalize preserves already normalized geo keys`() {
        val normalized = ChannelKeys.normalize("geo:9q8yy:#gaming")
        assertEquals("geo:9q8yy:#gaming", normalized)
    }

    @Test
    fun `same channel name on different timelines creates different keys`() {
        val meshKey = ChannelKeys.create(ChannelID.Mesh, "#gaming")
        val geohash = GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")
        val geoKey = ChannelKeys.create(ChannelID.Location(geohash), "#gaming")

        assertTrue(meshKey != geoKey)
        assertEquals("mesh:#gaming", meshKey)
        assertEquals("geo:9q8yy:#gaming", geoKey)
    }

    @Test
    fun `same channel name on different geohashes creates different keys`() {
        val geohashSF = GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")
        val geohashNYC = GeohashChannel(GeohashChannelLevel.CITY, "dr5ru")

        val sfKey = ChannelKeys.create(ChannelID.Location(geohashSF), "#gaming")
        val nycKey = ChannelKeys.create(ChannelID.Location(geohashNYC), "#gaming")

        assertTrue(sfKey != nycKey)
        assertEquals("geo:9q8yy:#gaming", sfKey)
        assertEquals("geo:dr5ru:#gaming", nycKey)
    }

    @Test
    fun `levelForGeohashLength returns expected levels`() {
        assertEquals(GeohashChannelLevel.REGION, ChannelKeys.levelForGeohashLength(2))
        assertEquals(GeohashChannelLevel.PROVINCE, ChannelKeys.levelForGeohashLength(3))
        assertEquals(GeohashChannelLevel.CITY, ChannelKeys.levelForGeohashLength(5))
        assertEquals(GeohashChannelLevel.NEIGHBORHOOD, ChannelKeys.levelForGeohashLength(6))
        assertEquals(GeohashChannelLevel.BLOCK, ChannelKeys.levelForGeohashLength(7))
        assertEquals(GeohashChannelLevel.BUILDING, ChannelKeys.levelForGeohashLength(9))
    }

    @Test
    fun `displayName formats mesh keys without prefix`() {
        val display = ChannelKeys.displayName("mesh:#gaming")
        assertEquals("#gaming", display)
    }

    @Test
    fun `displayName formats geo keys with geohash`() {
        val display = ChannelKeys.displayName("geo:9q8yy:#gaming")
        assertEquals("#gaming | #9q8yy", display)
    }
}

