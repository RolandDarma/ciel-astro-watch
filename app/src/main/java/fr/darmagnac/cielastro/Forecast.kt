package fr.darmagnac.cielastro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Site d'observation. Modifier ces deux constantes pour changer de lieu. */
object Site {
    const val LAT = 49.3145          // Luc-sur-Mer
    const val LON = -0.3550
    const val NAME = "Luc-sur-Mer"
}

class Hour(
    val t: Long,                     // instant UTC réel
    val label: String,               // "22:00" heure locale du site
    val cloud: Int,
    val low: Int,
    val mid: Int,
    val high: Int,
    val rain: Int,
    val humidity: Int,
    val dewPoint: Double,
    val temp: Double,
    val wind: Double,
    val gust: Double
) {
    var seeing: Double? = null       // estimation en secondes d'arc
    var moonAlt: Double = -90.0
    var dark: Boolean = false

    val cloudPenalty: Double
        get() = Astro.clamp(0.90 * low + 0.70 * mid + 0.45 * high, 0.0, 100.0)

    val dewSpread: Double
        get() = temp - dewPoint

    /** Même formule que la page web : nuages pondérés, pluie, rafales, buée, humidité. */
    val score: Int
        get() {
            var s = 100.0 - cloudPenalty
            s -= rain * 0.60
            if (gust > 30) s -= (gust - 30) * 1.2
            val spread = dewSpread
            when {
                spread < 1 -> s -= 18
                spread < 2 -> s -= 10
                spread < 3 -> s -= 4
            }
            if (humidity >= 96) s -= 5
            return Astro.clamp(s, 0.0, 100.0).roundToInt()
        }
}

class Night(
    val duskMs: Long,                // coucher du Soleil
    val dawnMs: Long,                // lever du Soleil
    val darkStartMs: Long,           // début de nuit astronomique
    val darkEndMs: Long,
    val hours: List<Hour>,
    val score: Int,
    val bestFromMs: Long?,           // plus long créneau dégagé
    val bestToMs: Long?,
    val bestHours: Int,
    val moonFraction: Double,
    val moonPhase: Double,
    val moonRiseMs: Long?,
    val moonSetMs: Long?,
    val moonImpact: Double,          // 0 = absente, 1 = pleine lune toute la nuit
    val seeingMedian: Double?
) {
    val verdict: Verdict get() = Verdict.of(score)

    val moonWord: String
        get() = when {
            moonImpact < 0.05 -> "Lune absente"
            moonImpact < 0.12 -> "Lune discrète"
            moonImpact < 0.35 -> "Lune gênante par moments"
            moonImpact < 0.65 -> "Lune gênante (ciel profond)"
            else -> "Lune très gênante"
        }
}

enum class Verdict(val label: String, val color: Int) {
    GO("Feu vert", 0xFF0CA30C.toInt()),
    OK("Jouable", 0xFFFAB219.toInt()),
    POOR("Médiocre", 0xFFEC835A.toInt()),
    NO("À oublier", 0xFFD03B3B.toInt());

    companion object {
        fun of(score: Int) = when {
            score >= 75 -> GO
            score >= 55 -> OK
            score >= 35 -> POOR
            else -> NO
        }
    }
}

object Forecast {

    private const val PREFS = "cielastro"
    private const val KEY_MAIN = "main_json"
    private const val KEY_UPPER = "upper_json"
    private const val KEY_TIME = "fetched_at"
    private const val FRESH_MS = 30 * 60_000L

    private const val HOURLY =
        "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,precipitation_probability," +
            "relative_humidity_2m,dew_point_2m,temperature_2m,wind_speed_10m,wind_gusts_10m"

    private const val HOURLY_UPPER = "wind_speed_250hPa,wind_speed_500hPa"

    class Snapshot(val nights: List<Night>, val fetchedAt: Long, val fromCache: Boolean, val error: String?)

    /** À appeler hors du thread principal. */
    fun load(ctx: Context, force: Boolean): Snapshot {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0L)
        val cachedMain = prefs.getString(KEY_MAIN, null)

        if (!force && cachedMain != null && age < FRESH_MS) {
            return try {
                Snapshot(build(cachedMain, prefs.getString(KEY_UPPER, null)), prefs.getLong(KEY_TIME, 0L), true, null)
            } catch (e: Exception) {
                Snapshot(emptyList(), 0L, true, "Cache illisible")
            }
        }

        return try {
            val main = get(
                "https://api.open-meteo.com/v1/forecast?latitude=${fmt(Site.LAT)}&longitude=${fmt(Site.LON)}" +
                    "&hourly=$HOURLY&forecast_days=7&timezone=auto&windspeed_unit=kmh"
            )
            // Couches d'altitude : facultatif, un échec ne doit pas faire tomber le reste
            val upper = try {
                get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${fmt(Site.LAT)}&longitude=${fmt(Site.LON)}" +
                        "&hourly=$HOURLY_UPPER&forecast_days=7&timezone=auto&windspeed_unit=kmh"
                )
            } catch (e: Exception) {
                null
            }
            val nights = build(main, upper)
            prefs.edit()
                .putString(KEY_MAIN, main)
                .putString(KEY_UPPER, upper)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
            Snapshot(nights, System.currentTimeMillis(), false, null)
        } catch (e: Exception) {
            if (cachedMain != null) {
                try {
                    Snapshot(
                        build(cachedMain, prefs.getString(KEY_UPPER, null)),
                        prefs.getLong(KEY_TIME, 0L), true, "Hors ligne"
                    )
                } catch (e2: Exception) {
                    Snapshot(emptyList(), 0L, true, "Pas de données")
                }
            } else {
                Snapshot(emptyList(), 0L, false, "Pas de réseau")
            }
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 12_000
        c.requestMethod = "GET"
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseLocalNaive(s: String, offsetSeconds: Int): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return (f.parse(s)?.time ?: 0L) - offsetSeconds * 1000L
    }

    private fun ints(h: JSONObject, name: String): JSONArray? = h.optJSONArray(name)
    private fun vi(a: JSONArray?, i: Int, d: Int) = a?.optInt(i, d) ?: d
    private fun vd(a: JSONArray?, i: Int, d: Double) = a?.optDouble(i, d) ?: d

    private fun build(mainJson: String, upperJson: String?): List<Night> {
        val root = JSONObject(mainJson)
        val offset = root.getInt("utc_offset_seconds")
        val h = root.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val n = times.length()

        val aCloud = ints(h, "cloud_cover")
        val aLow = ints(h, "cloud_cover_low")
        val aMid = ints(h, "cloud_cover_mid")
        val aHigh = ints(h, "cloud_cover_high")
        val aRain = ints(h, "precipitation_probability")
        val aRh = ints(h, "relative_humidity_2m")
        val aDew = ints(h, "dew_point_2m")
        val aTemp = ints(h, "temperature_2m")
        val aWind = ints(h, "wind_speed_10m")
        val aGust = ints(h, "wind_gusts_10m")

        val rows = ArrayList<Hour>(n)
        for (i in 0 until n) {
            val label = times.getString(i)
            val cloud = vi(aCloud, i, 0)
            rows.add(
                Hour(
                    t = parseLocalNaive(label, offset),
                    label = label.substring(11, 16),
                    cloud = cloud,
                    // si un modèle ne fournit pas le détail par étage, on retombe sur le total
                    low = vi(aLow, i, cloud),
                    mid = vi(aMid, i, 0),
                    high = vi(aHigh, i, 0),
                    rain = vi(aRain, i, 0),
                    humidity = vi(aRh, i, 0),
                    dewPoint = vd(aDew, i, 0.0),
                    temp = vd(aTemp, i, 0.0),
                    wind = vd(aWind, i, 0.0),
                    gust = vd(aGust, i, 0.0)
                )
            )
        }

        // Estimation du seeing à partir du courant-jet (même règle que la page web)
        if (upperJson != null) {
            try {
                val uh = JSONObject(upperJson).getJSONObject("hourly")
                val ut = uh.getJSONArray("time")
                val w250 = uh.getJSONArray("wind_speed_250hPa")
                val w500 = uh.optJSONArray("wind_speed_500hPa")
                val index = HashMap<String, Int>()
                for (i in 0 until ut.length()) index[ut.getString(i)] = i
                for ((i, row) in rows.withIndex()) {
                    val key = times.getString(i)
                    val j = index[key] ?: continue
                    val a = w250.optDouble(j, Double.NaN)
                    if (a.isNaN()) continue
                    val b = w500?.optDouble(j, 0.0) ?: 0.0
                    val w = a + 0.4 * b
                    row.seeing = when {
                        w < 40 -> 0.9
                        w < 70 -> 1.2
                        w < 110 -> 1.6
                        w < 150 -> 2.1
                        else -> 2.8
                    }
                }
            } catch (e: Exception) {
                // on continue sans seeing
            }
        }

        return buildNights(rows, offset)
    }

    private fun buildNights(rows: List<Hour>, offset: Int): List<Night> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<Night>()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = rows[0].t + offset * 1000L
        val y0 = cal.get(Calendar.YEAR)
        val m0 = cal.get(Calendar.MONTH)
        val d0 = cal.get(Calendar.DAY_OF_MONTH)
        val now = System.currentTimeMillis()

        for (k in -1..6) {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(y0, m0, d0 + k, 12, 0, 0)
            val noon = c.timeInMillis - offset * 1000L
            val noonNext = noon + 86_400_000L

            val sun = Astro.sunTime(noon, Site.LAT, Site.LON, -0.833)
            val sunNext = Astro.sunTime(noonNext, Site.LAT, Site.LON, -0.833)
            val dusk = sun.set ?: continue
            val dawn = sunNext.rise ?: continue
            if (dawn < now - 1_800_000L) continue            // nuit déjà passée

            var darkStart = Astro.sunTime(noon, Site.LAT, Site.LON, -18.0).set
            var darkEnd = Astro.sunTime(noonNext, Site.LAT, Site.LON, -18.0).rise
            if (darkStart == null || darkEnd == null || darkEnd <= darkStart) {
                darkStart = Astro.sunTime(noon, Site.LAT, Site.LON, -12.0).set
                darkEnd = Astro.sunTime(noonNext, Site.LAT, Site.LON, -12.0).rise
            }
            if (darkStart == null || darkEnd == null || darkEnd <= darkStart) {
                darkStart = dusk
                darkEnd = dawn
            }

            val from = dusk - 3_600_000L
            val to = dawn + 3_600_000L
            val hours = rows.filter { it.t in from..to }
            if (hours.size < 8) continue

            for (hr in hours) {
                hr.dark = hr.t >= darkStart - 1_800_000L && hr.t <= darkEnd
                hr.moonAlt = Astro.moonAltitude(hr.t, Site.LAT, Site.LON)
            }

            val pool = hours.filter { it.t in darkStart..darkEnd }.ifEmpty { hours }
            val score = (pool.sumOf { it.score }.toDouble() / pool.size).roundToInt()

            // plus long créneau consécutif réellement dégagé
            var bestFrom: Long? = null
            var bestTo: Long? = null
            var bestN = 0
            var curFrom: Long? = null
            var curTo = 0L
            var curN = 0
            for (hr in pool) {
                if (hr.cloudPenalty <= 25 && hr.rain <= 15) {
                    if (curFrom == null) curFrom = hr.t
                    curTo = hr.t + 3_600_000L
                    curN++
                } else {
                    if (curN > bestN) { bestN = curN; bestFrom = curFrom; bestTo = curTo }
                    curFrom = null; curN = 0
                }
            }
            if (curN > bestN) { bestN = curN; bestFrom = curFrom; bestTo = curTo }

            val mid = (darkStart + darkEnd) / 2
            val phase = Astro.moonPhase(mid)
            val mt = Astro.moonTimes(dusk - 7_200_000L, dawn + 7_200_000L, Site.LAT, Site.LON)
            val moonUp = pool.count { it.moonAlt > 0 }.toDouble() / pool.size

            val seeings = pool.mapNotNull { it.seeing }.sorted()
            val seeingMedian = if (seeings.isEmpty()) null else seeings[seeings.size / 2]

            out.add(
                Night(
                    duskMs = dusk, dawnMs = dawn,
                    darkStartMs = darkStart, darkEndMs = darkEnd,
                    hours = hours, score = score,
                    bestFromMs = bestFrom, bestToMs = bestTo, bestHours = bestN,
                    moonFraction = phase.fraction, moonPhase = phase.phase,
                    moonRiseMs = mt.rise, moonSetMs = mt.set,
                    moonImpact = phase.fraction * moonUp,
                    seeingMedian = seeingMedian
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- format

    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dayFmt = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val shortDay = SimpleDateFormat("EEE d", Locale.FRANCE)

    fun time(ms: Long?): String = if (ms == null) "—" else hhmm.format(Date(ms))
    fun day(ms: Long): String = dayFmt.format(Date(ms)).replaceFirstChar { it.uppercase() }
    fun dayShort(ms: Long): String = shortDay.format(Date(ms)).replaceFirstChar { it.uppercase() }
}
