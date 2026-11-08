package net.asksakis.massdroidv2.domain.party

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mirror ball behind the Party screen: its tiles, where they are at a given spin angle,
 * and how bright each one is.
 *
 * The tiles sit in [BANDS] latitude bands, with fewer tiles towards the poles. Every value
 * that does not depend on the angle is computed once here, and [update] writes its results
 * into arrays that are allocated once, because the screen calls it on every animation step.
 * The results are in unit coordinates: x to the right and y up, with the ball's radius as 1.
 */
class MirrorBall {
    /** The number of tiles on the whole ball. */
    val tileCount: Int

    // Per tile, fixed: the sines and cosines of its corner and center latitudes and longitudes.
    private val cosLat: FloatArray
    private val sinLat: FloatArray
    private val cosLon: FloatArray
    private val sinLon: FloatArray
    private val grey: FloatArray
    private val tint: IntArray

    /** The four corners of each tile, x and y in turn; valid for the tiles in [visible]. */
    val corners: FloatArray

    /** The center of each tile, x and y. */
    val centers: FloatArray

    /** The colour of each tile as opaque ARGB. */
    val colors: IntArray

    /** The specular highlight of each tile, from 0 to 1. Above [GLINT_THRESHOLD] it glints. */
    val specular: FloatArray

    /** The tiles facing the viewer, in drawing order; the first [visibleCount] are valid. */
    val visible: IntArray
    var visibleCount: Int = 0
        private set

    init {
        val bandTiles = IntArray(BANDS) { band -> tilesInBand(band) }
        tileCount = bandTiles.sum()
        cosLat = FloatArray(tileCount * POINTS)
        sinLat = FloatArray(tileCount * POINTS)
        cosLon = FloatArray(tileCount * POINTS)
        sinLon = FloatArray(tileCount * POINTS)
        grey = FloatArray(tileCount)
        tint = IntArray(tileCount)
        corners = FloatArray(tileCount * CORNERS * 2)
        centers = FloatArray(tileCount * 2)
        colors = IntArray(tileCount)
        specular = FloatArray(tileCount)
        visible = IntArray(tileCount)
        buildTiles(bandTiles)
    }

    private fun buildTiles(bandTiles: IntArray) {
        val random = ParkMiller(TILE_SEED)
        val bandHeight = PI / BANDS
        var tile = 0
        for (band in 0 until BANDS) {
            val a0 = -PI / 2 + band * bandHeight
            val a1 = a0 + bandHeight
            val count = bandTiles[band]
            val width = 2 * PI / count
            val da = bandHeight * TILE_GAP / 2
            val dl = width * TILE_GAP / 2
            for (j in 0 until count) {
                val l0 = j * width
                val l1 = l0 + width
                // The same order of draws as the reference mockup, so the pattern matches it.
                tint[tile] = if (random.next() < TINTED_SHARE) (if (random.next() < 0.5) 0 else 1) else NO_TINT
                grey[tile] = (MIN_GREY + random.next() * (1 - MIN_GREY)).toFloat()
                // Corners in drawing order, then the center.
                setPoint(tile, 0, a0 + da, l0 + dl)
                setPoint(tile, 1, a0 + da, l1 - dl)
                setPoint(tile, 2, a1 - da, l1 - dl)
                setPoint(tile, 3, a1 - da, l0 + dl)
                setPoint(tile, CENTER, (a0 + a1) / 2, (l0 + l1) / 2)
                tile++
            }
        }
    }

    private fun setPoint(tile: Int, point: Int, lat: Double, lon: Double) {
        val i = tile * POINTS + point
        cosLat[i] = cos(lat).toFloat()
        sinLat[i] = sin(lat).toFloat()
        cosLon[i] = cos(lon).toFloat()
        sinLon[i] = sin(lon).toFloat()
    }

    /**
     * Turns the ball to [angle] radians and shades it, with [tintA] and [tintB] (opaque ARGB)
     * as the colours of the tinted tiles.
     */
    fun update(angle: Float, tintA: Int, tintB: Int) {
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        visibleCount = 0
        for (tile in 0 until tileCount) {
            val c = tile * POINTS + CENTER
            // sin and cos of (longitude + angle), from the sums of angles.
            val sinC = sinLon[c] * cosAngle + cosLon[c] * sinAngle
            val cosC = cosLon[c] * cosAngle - sinLon[c] * sinAngle
            val x = cosLat[c] * sinC
            val y0 = sinLat[c]
            val z0 = cosLat[c] * cosC
            val y = y0 * COS_TILT - z0 * SIN_TILT
            val z = y0 * SIN_TILT + z0 * COS_TILT
            if (z <= MIN_VISIBLE_Z) continue
            visible[visibleCount++] = tile
            centers[tile * 2] = x
            centers[tile * 2 + 1] = y
            for (corner in 0 until CORNERS) {
                val p = tile * POINTS + corner
                val sinP = sinLon[p] * cosAngle + cosLon[p] * sinAngle
                val cosP = cosLon[p] * cosAngle - sinLon[p] * sinAngle
                val pz = cosLat[p] * cosP
                val out = (tile * CORNERS + corner) * 2
                corners[out] = cosLat[p] * sinP
                corners[out + 1] = sinLat[p] * COS_TILT - pz * SIN_TILT
            }
            shade(tile, x, y, z, tintA, tintB)
        }
    }

    private fun shade(tile: Int, x: Float, y: Float, z: Float, tintA: Int, tintB: Int) {
        val facing = x * LIGHT_X + y * LIGHT_Y + z * LIGHT_Z
        val diffuse = max(0f, facing)
        val spec = max(0f, 2 * facing * z - LIGHT_Z).pow(SHININESS)
        val base = grey[tile] * (AMBIENT + DIFFUSE * diffuse) * (DEPTH_FLOOR + (1 - DEPTH_FLOOR) * z)
        var r = base
        var g = base
        var b = base
        if (tint[tile] != NO_TINT) {
            val color = if (tint[tile] == 0) tintA else tintB
            val strength = TINT_MIX * (TINT_FLOOR + diffuse)
            r = r * (1 - TINT_MIX) + ((color shr 16) and 0xFF) * strength
            g = g * (1 - TINT_MIX) + ((color shr 8) and 0xFF) * strength
            b = b * (1 - TINT_MIX) + (color and 0xFF) * strength
        }
        val highlight = spec * CHANNEL_MAX
        colors[tile] = argb(r + highlight, g + highlight, b + highlight)
        specular[tile] = spec
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun channel(value: Float): Int = min(CHANNEL_MAX, max(0f, value)).roundToInt()

    /** The generator the reference mockup uses, so the tiles come out the same. */
    private class ParkMiller(private var state: Long) {
        fun next(): Double {
            state = state * MULTIPLIER % MODULUS
            return state.toDouble() / MODULUS
        }

        private companion object {
            const val MULTIPLIER = 16_807L
            const val MODULUS = 2_147_483_647L
        }
    }

    companion object {
        const val BANDS = 14
        const val GLINT_THRESHOLD = 0.5f

        /** The tile count of a band: the most at the equator, never fewer than [MIN_BAND_TILES]. */
        fun tilesInBand(band: Int): Int {
            val center = -PI / 2 + (band + 0.5) * PI / BANDS
            return max(MIN_BAND_TILES, (MAX_BAND_TILES * cos(center)).roundToInt())
        }

        private const val MAX_BAND_TILES = 30
        private const val MIN_BAND_TILES = 4
        private const val CORNERS = 4
        private const val CENTER = 4
        private const val POINTS = 5
        private const val TILE_SEED = 3L
        private const val TINTED_SHARE = 0.2
        private const val NO_TINT = -1
        private const val MIN_GREY = 0.7
        /** The share of a tile's width and height left as grout between tiles. */
        private const val TILE_GAP = 0.14
        private const val TILT = 0.38
        private val COS_TILT = cos(TILT).toFloat()
        private val SIN_TILT = sin(TILT).toFloat()
        private const val MIN_VISIBLE_Z = 0.03f
        private const val SHININESS = 28f
        private const val AMBIENT = 34f
        private const val DIFFUSE = 180f
        /** Tiles towards the rim keep this share of their brightness. */
        private const val DEPTH_FLOOR = 0.45f
        private const val TINT_MIX = 0.6f
        private const val TINT_FLOOR = 0.5f
        private const val CHANNEL_MAX = 255f
        private val LIGHT_LENGTH = sqrt(0.45 * 0.45 + 0.55 * 0.55 + 0.70 * 0.70)
        private val LIGHT_X = (-0.45 / LIGHT_LENGTH).toFloat()
        private val LIGHT_Y = (0.55 / LIGHT_LENGTH).toFloat()
        private val LIGHT_Z = (0.70 / LIGHT_LENGTH).toFloat()
    }
}
