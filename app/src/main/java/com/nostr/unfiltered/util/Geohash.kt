package com.nostr.unfiltered.util

/**
 * Geohash encoder / decoder + neighbour calculation.
 *
 * Geohash (https://en.wikipedia.org/wiki/Geohash) encodes a (lat, lon) pair
 * into a short base32 string where nearby strings share long prefixes.
 *
 * We use geohashes on Nostr posts as `g` tags (per NIP-52 / common practice)
 * to enable coarse proximity-based feeds without exposing precise GPS.
 *
 * Precision reference:
 *   1  ~2500 km
 *   2  ~630 km
 *   3  ~78 km
 *   4  ~20 km
 *   5  ~5 km
 *   6  ~1.2 km
 *   7  ~150 m
 *   8  ~38 m
 *
 * Default precision for the Nearby feed is 5 (~5 km, city-scale).
 */
object Geohash {

    /** Standard geohash base32 alphabet (Niemeyer's). */
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    init {
        require(BASE32.length == 32) { "Geohash base32 must be exactly 32 chars" }
    }

    /**
     * Encode (lat, lon) at the given precision (1..12).
     * Throws on out-of-range coordinates or precision.
     */
    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        require(precision in 1..12) { "precision must be 1..12, got $precision" }
        require(latitude in -90.0..90.0) { "latitude must be in [-90, 90], got $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be in [-180, 180], got $longitude" }

        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0

        val out = CharArray(precision)
        var bit = 0      // 0..4 — bit index within current base32 char
        var ch = 0       // current base32 character being built (0..31)
        var idx = 0      // character index in result
        var evenBit = true

        while (idx < precision) {
            if (evenBit) {
                val lonMid = (lonLow + lonHigh) / 2.0
                if (longitude >= lonMid) {
                    ch = (ch shl 1) or 1
                    lonLow = lonMid
                } else {
                    ch = ch shl 1
                    lonHigh = lonMid
                }
            } else {
                val latMid = (latLow + latHigh) / 2.0
                if (latitude >= latMid) {
                    ch = (ch shl 1) or 1
                    latLow = latMid
                } else {
                    ch = ch shl 1
                    latHigh = latMid
                }
            }
            evenBit = !evenBit

            bit++
            if (bit == 5) {
                out[idx] = BASE32[ch]
                idx++
                bit = 0
                ch = 0
            }
        }

        return String(out)
    }

    /**
     * Decode a geohash back to the centre point of its cell as (lat, lon).
     * Useful for displaying a rough position or for further bounds checks.
     */
    fun decode(geohash: String): Pair<Double, Double> {
        require(geohash.isNotEmpty()) { "geohash must not be empty" }

        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0
        var evenBit = true

        for (c in geohash) {
            val cd = BASE32.indexOf(c)
            require(cd >= 0) { "invalid geohash char '$c'" }

            // Each base32 char carries 5 bits.
            for (n in 4 downTo 0) {
                val bitN = (cd shr n) and 1
                if (evenBit) {
                    val lonMid = (lonLow + lonHigh) / 2.0
                    if (bitN == 1) lonLow = lonMid else lonHigh = lonMid
                } else {
                    val latMid = (latLow + latHigh) / 2.0
                    if (bitN == 1) latLow = latMid else latHigh = latMid
                }
                evenBit = !evenBit
            }
        }

        val lat = (latLow + latHigh) / 2.0
        val lon = (lonLow + lonHigh) / 2.0
        return lat to lon
    }

    /**
     * Approximate cell width in degrees for a given precision.
     * (Roughly: lon span of the cell.)
     */
    fun approximateCellDegrees(precision: Int): Double {
        // Standard geohash cell widths by precision (degrees).
        // Index 0 unused; precision 1..12.
        return when (precision) {
            1 -> 45.0
            2 -> 11.25
            3 -> 1.40625
            4 -> 0.3515625
            5 -> 0.0439453125
            6 -> 0.010986328125
            7 -> 0.001373291015625
            8 -> 0.000343322753906
            9 -> 4.29153442382813e-05
            10 -> 1.07288360595703e-05
            11 -> 2.68220901489258e-06
            12 -> 6.70552253723145e-07
            else -> throw IllegalArgumentException("precision must be 1..12, got $precision")
        }
    }

    /**
     * Return the 8 neighbouring cells of the given geohash (sharing the same
     * precision), as well as the input itself. The result has 9 entries,
     * with [self] in the centre index 4.
     *
     * Order, with self at index 4:
     *   0=NW, 1=N, 2=NE,
     *   3=W,  4=self, 5=E,
     *   6=SW, 7=S, 8=SE
     */
    fun neighboursAndSelf(geohash: String): List<String> {
        require(geohash.isNotEmpty()) { "geohash must not be empty" }
        val precision = geohash.length

        // Decode to get the centre of the current cell.
        val (lat, lon) = decode(geohash)

        // Approximate one cell of the *parent* precision to step in lon/lat.
        // For precision p, the cell size is ~ approximateCellDegrees(p).
        val stepDeg = approximateCellDegrees(precision)

        // Step slightly more than one cell to land squarely in a neighbour
        // (avoid edge-case ambiguity when source is right on a cell boundary).
        val step = stepDeg * 1.5

        val out = mutableListOf<String>()
        for (dLat in intArrayOf(1, 0, -1)) {       // N, centre, S
            for (dLon in intArrayOf(-1, 0, 1)) {   // W, centre, E
                val nLat = (lat + dLat * step).coerceIn(-90.0, 90.0)
                // Wrap longitude into [-180, 180].
                var nLon = lon + dLon * step
                nLon = ((nLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                out.add(encode(nLat, nLon, precision))
            }
        }
        // Layout above is:
        //   (lat+,lon-)  (lat+,lon0)  (lat+,lon+)
        //   (lat0,lon-)  (self)       (lat0,lon+)
        //   (lat-,lon-)  (lat-,lon0)  (lat-,lon+)
        // We want NW..SE with self at index 4. Reorder:
        // current index -> desired index (NW=0,N=1,NE=2,W=3,self=4,E=5,SW=6,S=7,SE=8):
        // 0(NW) -> 0 ; 1(N) -> 1 ; 2(NE) -> 2
        // 3(W)  -> 3 ; 4(self) -> 4 ; 5(E)  -> 5
        // 6(SW) -> 6 ; 7(S) -> 7 ; 8(SE) -> 8
        // -> already in the right order, so no shuffle needed.
        return out
    }

    /**
     * Convenience: return the 8 neighbours only (excluding self).
     */
    fun neighbours(geohash: String): List<String> {
        val all = neighboursAndSelf(geohash)
        return all.filter { it != geohash }
    }

    /**
     * Sanity check: a geohash string is valid iff every char is in the base32
     * alphabet. Does not verify ordering (a "valid" string may still decode
     * to a point — the alphabet check is enough for input validation in
     * tag filters).
     */
    fun isValid(geohash: String): Boolean {
        if (geohash.isEmpty()) return false
        return geohash.all { it in BASE32 }
    }
}