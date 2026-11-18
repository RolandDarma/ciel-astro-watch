package fr.darmagnac.cielastro

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Éphémérides Soleil / Lune — algorithmes de Meeus (basse précision),
 * port du code vérifié de la page web : nouvelle lune du 06/01/2000 -> 0,0 %,
 * pleine lune du 21/01/2000 -> 100 %, coucher du Soleil à Paris au solstice -> 21:58 CEST.
 *
 * Précision : quelques minutes sur les heures solaires, ~15 min sur les lever/coucher de Lune.
 * Largement suffisant pour décider d'une sortie.
 */
object Astro {

    private const val RAD = Math.PI / 180.0
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2_440_588.0
    private const val J2000 = 2_451_545.0
    private val E = RAD * 23.4397          // obliquité de l'écliptique

    private fun toJulian(ms: Long) = ms / DAY_MS - 0.5 + J1970
    private fun fromJulian(j: Double) = ((j + 0.5 - J1970) * DAY_MS).toLong()
    private fun toDays(ms: Long) = toJulian(ms) - J2000

    private fun rightAscension(l: Double, b: Double) =
        atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))

    private fun declination(l: Double, b: Double) =
        asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))

    private fun altitude(h: Double, phi: Double, dec: Double) =
        asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))

    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw

    private fun solarMeanAnomaly(d: Double) = RAD * (357.5291 + 0.98560028 * d)

    private fun eclipticLongitude(m: Double): Double {
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        return m + c + RAD * 102.9372 + Math.PI
    }

    private class SunCoords(val dec: Double, val ra: Double)

    private fun sunCoords(d: Double): SunCoords {
        val m = solarMeanAnomaly(d)
        val l = eclipticLongitude(m)
        return SunCoords(declination(l, 0.0), rightAscension(l, 0.0))
    }

    private const val J0 = 0.0009

    /**
     * Instant où le Soleil atteint [angleDeg] au-dessus de l'horizon.
     * -0.833 = lever / coucher ; -18 = crépuscule astronomique ; -12 = nautique.
     * [rise] et [set] valent null si la hauteur n'est jamais atteinte ce jour-là.
     */
    class SunTime(val rise: Long?, val set: Long?)

    fun sunTime(dateMs: Long, lat: Double, lon: Double, angleDeg: Double): SunTime {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = toDays(dateMs)
        val n = Math.round(d - J0 - lw / (2 * Math.PI)).toDouble()
        val ds = J0 + (0.0 + lw) / (2 * Math.PI) + n
        val m = solarMeanAnomaly(ds)
        val l = eclipticLongitude(m)
        val dec = declination(l, 0.0)
        val jNoon = J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val h = angleDeg * RAD
        val cosH = (sin(h) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosH > 1 || cosH < -1) return SunTime(null, null)
        val w = acos(cosH)
        val a = J0 + (w + lw) / (2 * Math.PI) + n
        val jSet = J2000 + a + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val jRise = jNoon - (jSet - jNoon)
        return SunTime(fromJulian(jRise), fromJulian(jSet))
    }

    private class MoonCoords(val ra: Double, val dec: Double, val dist: Double)

    private fun moonCoords(d: Double): MoonCoords {
        val l = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val lon = l + RAD * 6.289 * sin(m)
        val lat = RAD * 5.128 * sin(f)
        val dist = 385001 - 20905 * cos(m)
        return MoonCoords(rightAscension(lon, lat), declination(lon, lat), dist)
    }

    /** Hauteur de la Lune en degrés (réfraction incluse). */
    fun moonAltitude(dateMs: Long, lat: Double, lon: Double): Double {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = toDays(dateMs)
        val c = moonCoords(d)
        val hAngle = siderealTime(d, lw) - c.ra
        var h = altitude(hAngle, phi, c.dec)
        h += RAD * 0.017 / tan(h + RAD * 10.26 / (h / RAD + 5.10))
        return h / RAD
    }

    /** Fraction éclairée (0..1) et phase (0 = nouvelle lune, 0,5 = pleine lune). */
    class MoonPhase(val fraction: Double, val phase: Double)

    fun moonPhase(dateMs: Long): MoonPhase {
        val d = toDays(dateMs)
        val s = sunCoords(d)
        val m = moonCoords(d)
        val sdist = 149_598_000.0
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(sdist * sin(phi), m.dist - sdist * cos(phi))
        val angle = atan2(
            cos(s.dec) * sin(s.ra - m.ra),
            sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra)
        )
        return MoonPhase(
            fraction = (1 + cos(inc)) / 2,
            phase = 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / Math.PI
        )
    }

    class MoonTimes(val rise: Long?, val set: Long?)

    /** Lever / coucher de Lune par balayage de la hauteur entre deux instants. */
    fun moonTimes(fromMs: Long, toMs: Long, lat: Double, lon: Double): MoonTimes {
        var rise: Long? = null
        var set: Long? = null
        val step = 10 * 60_000L
        var prev = moonAltitude(fromMs, lat, lon)
        var t = fromMs + step
        while (t <= toMs) {
            val cur = moonAltitude(t, lat, lon)
            if (prev < 0 && cur >= 0 && rise == null) {
                rise = t - step + (step * (-prev / (cur - prev))).toLong()
            }
            if (prev >= 0 && cur < 0 && set == null) {
                set = t - step + (step * (prev / (prev - cur))).toLong()
            }
            prev = cur
            t += step
        }
        return MoonTimes(rise, set)
    }

    fun phaseName(p: Double): String = when {
        p < 0.02 || p >= 0.98 -> "nouvelle lune"
        p < 0.23 -> "croissant"
        p < 0.27 -> "premier quartier"
        p < 0.48 -> "gibbeuse croissante"
        p < 0.52 -> "pleine lune"
        p < 0.73 -> "gibbeuse décroissante"
        p < 0.77 -> "dernier quartier"
        else -> "croissant décroissant"
    }

    fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    @Suppress("unused")
    fun absDouble(v: Double) = abs(v)
}
