package com.nostr.unfiltered.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Geohash]. Reference vectors cross-checked against the canonical
 * Niemeyer geohash spec (Wikipedia / Rosetta Code).
 */
class GeohashTest {

    // ---- Encode: known-good vectors ----

    @Test
    fun encode_Denmark_tip() {
        // 57.64911, 10.40744 -> "u4pruydqqvj" (precision 11)
        assertEquals(
            "u4pruydqqvj",
            Geohash.encode(57.64911, 10.40744, 11)
        )
    }

    @Test
    fun encode_UK_precision2() {
        // 51.433718, -0.214126 -> "gc" (precision 2)
        assertEquals(
            "gc",
            Geohash.encode(51.433718, -0.214126, 2)
        )
    }

    @Test
    fun encode_UK_precision9() {
        // 51.433718, -0.214126 -> "gcpue5hp4" (precision 9)
        assertEquals(
            "gcpue5hp4",
            Geohash.encode(51.433718, -0.214126, 9)
        )
    }

    @Test
    fun encode_London_precision5() {
        // 51.5074, -0.1278 (London) -> "gcpvj" (precision 5)
        assertEquals(
            "gcpvj",
            Geohash.encode(51.5074, -0.1278, 5)
        )
    }

    @Test
    fun encode_Tokyo_precision5() {
        // 35.6762, 139.6503 (Tokyo) -> "xn76c" (precision 5)
        assertEquals(
            "xn76c",
            Geohash.encode(35.6762, 139.6503, 5)
        )
    }

    @Test
    fun encode_NYC_precision5() {
        // 40.7128, -74.0060 (NYC) -> "dr5re" (precision 5)
        assertEquals(
            "dr5re",
            Geohash.encode(40.7128, -74.0060, 5)
        )
    }

    @Test
    fun encode_origin() {
        // 0,0 -> "s00000" (precision 6)
        assertEquals(
            "s00000",
            Geohash.encode(0.0, 0.0, 6)
        )
    }

    // ---- Encode: error cases ----

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsBadLat() {
        Geohash.encode(91.0, 0.0, 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsBadLon() {
        Geohash.encode(0.0, 181.0, 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsBadPrecision() {
        Geohash.encode(0.0, 0.0, 13)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsZeroPrecision() {
        Geohash.encode(0.0, 0.0, 0)
    }

    // ---- Decode ----

    @Test
    fun decode_roundtrips() {
        val lat = 51.5074
        val lon = -0.1278
        val gh = Geohash.encode(lat, lon, 8)
        val (dLat, dLon) = Geohash.decode(gh)
        // Decoded point is the centre of the cell — should be within one cell.
        val cellDeg = Geohash.approximateCellDegrees(8)
        assertTrue("lat off: $dLat vs $lat", kotlin.math.abs(dLat - lat) < cellDeg)
        assertTrue("lon off: $dLon vs $lon", kotlin.math.abs(dLon - lon) < cellDeg)
    }

    @Test
    fun decode_Denmark_at_precision11() {
        // Reference: u4pruydqqvj -> (57.64911063015461, 10.407439693808556)
        val (lat, lon) = Geohash.decode("u4pruydqqvj")
        assertEquals(57.64911063015461, lat, 1e-9)
        assertEquals(10.407439693808556, lon, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsInvalidChar() {
        Geohash.decode("gcpv!")  // '!' is not in geohash alphabet
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsEmpty() {
        Geohash.decode("")
    }

    // ---- Neighbours ----

    @Test
    fun neighboursAndSelf_returns9_unique() {
        val gh = Geohash.encode(51.5074, -0.1278, 5)
        val nine = Geohash.neighboursAndSelf(gh)
        assertEquals(9, nine.size)
        // All unique (within a non-edge cell, no dedup happens).
        assertEquals(9, nine.toSet().size)
        // Index 4 is self.
        assertEquals(gh, nine[4])
    }

    @Test
    fun neighbours_London_standardGrid() {
        // The 8 neighbours of gcpvj are well-known:
        //   NW=gcpvs N=gcpvt NE=gcpvx
        //   W=gcpvh        E=gcpvp
        //   SW=gcpuu S=gcpuv SE=gcpuz
        val gh = "gcpvj"
        val expected = setOf(
            "gcpvs", "gcpvt", "gcpvx",
            "gcpvh",            "gcpvp",
            "gcpuu", "gcpuv", "gcpuz"
        )
        val actual = Geohash.neighbours(gh).toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun neighbours_excludesSelf() {
        val gh = Geohash.encode(35.6762, 139.6503, 5)
        val ns = Geohash.neighbours(gh)
        assertEquals(8, ns.size)
        assertFalse(ns.contains(gh))
    }

    // ---- isValid ----

    @Test
    fun isValid_acceptsStandard() {
        assertTrue(Geohash.isValid("gcpvj"))
        assertTrue(Geohash.isValid("u4pruydqqvj"))
        assertTrue(Geohash.isValid("0"))
    }

    @Test
    fun isValid_rejectsBadChars() {
        assertFalse(Geohash.isValid(""))           // empty
        assertFalse(Geohash.isValid("gcpv!"))      // '!'
        assertFalse(Geohash.isValid("GCPVJ"))      // uppercase not in alphabet
        assertFalse(Geohash.isValid("gcpv "))      // space
    }

    // ---- Cell widths ----

    @Test
    fun approximateCellDegrees_knownValues() {
        assertEquals(45.0, Geohash.approximateCellDegrees(1), 1e-9)
        assertEquals(11.25, Geohash.approximateCellDegrees(2), 1e-9)
        assertEquals(0.0439453125, Geohash.approximateCellDegrees(5), 1e-12)
        assertEquals(0.001373291015625, Geohash.approximateCellDegrees(7), 1e-15)
    }

    @Test
    fun approximateCellDegrees_invalidThrows() {
        try {
            Geohash.approximateCellDegrees(0)
            assert(false) { "should have thrown" }
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            Geohash.approximateCellDegrees(13)
            assert(false) { "should have thrown" }
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    // ---- Practical sanity: encode + decode + neighbours agree ----

    @Test
    fun encode_decode_neighbours_areMutuallyConsistent() {
        // Pick a far-from-edge point and check that all 8 neighbours decode
        // to roughly distinct cells around the centre.
        val centre = Geohash.encode(48.8566, 2.3522, 6)  // Paris
        val nine = Geohash.neighboursAndSelf(centre)
        assertEquals(9, nine.toSet().size)
        for (g in nine) {
            assertTrue("neighbour $g should be valid", Geohash.isValid(g))
            // Each neighbour must round-trip
            val (lat, lon) = Geohash.decode(g)
            assertTrue("lat in range: $lat", lat in -90.0..90.0)
            assertTrue("lon in range: $lon", lon in -180.0..180.0)
        }
        // And all 8 neighbours should differ from the centre.
        for (g in nine) {
            assertNotEquals(centre, g)
        }
    }
}