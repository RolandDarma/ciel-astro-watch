package fr.darmagnac.cielastro

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.tiles.TileService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran détaillé : les prochaines nuits, du plus proche au plus lointain.
 * Un appui n'importe où relance la récupération des données.
 */
class MainActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // marges généreuses : l'écran de la Watch 4 est rond, les coins sont rognés
            setPadding(dp(22), dp(34), dp(22), dp(40))
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)

        status = label("Chargement…", 12f, GREY, Gravity.CENTER)
        container.addView(title(Site.NAME))
        container.addView(status)

        refresh(force = false)
    }

    override fun onResume() {
        super.onResume()
        if (container.childCount <= 2) refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        status.text = if (force) "Actualisation…" else "Chargement…"
        Thread {
            val result = Forecast.load(applicationContext, force)
            ui.post {
                loading = false
                render(result)
                try {
                    TileService.getUpdater(applicationContext)
                        .requestUpdate(SkyTileService::class.java)
                } catch (e: Exception) {
                    // la tuile n'est peut-être pas installée : sans importance
                }
            }
        }.start()
    }

    private fun render(result: Forecast.Snapshot) {
        container.removeAllViews()
        container.addView(title(Site.NAME))

        val stamp = if (result.fetchedAt > 0)
            SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(result.fetchedAt)) else "—"
        val suffix = when {
            result.error != null -> " · ${result.error}"
            result.fromCache -> " · en cache"
            else -> ""
        }
        container.addView(label("Mis à jour $stamp$suffix", 11f, GREY, Gravity.CENTER))

        if (result.nights.isEmpty()) {
            container.addView(space(12))
            container.addView(label("Aucune donnée.", 13f, WHITE, Gravity.CENTER))
            container.addView(space(10))
            container.addView(refreshButton())
            return
        }

        for ((i, night) in result.nights.withIndex()) {
            container.addView(space(if (i == 0) 14 else 18))
            container.addView(card(night, i == 0))
        }
        container.addView(space(10))
        container.addView(refreshButton())
    }

    private fun card(n: Night, tonight: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xFF161615.toInt())
        }
        val head = if (tonight) "Cette nuit" else Forecast.day(n.duskMs)
        box.addView(label(head, 13f, WHITE, Gravity.START, bold = true))

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(label("${n.score}", 30f, n.verdict.color, Gravity.START, bold = true))
        line.addView(label("  ${n.verdict.label}", 13f, n.verdict.color, Gravity.START))
        box.addView(line)

        val window = if (n.bestFromMs != null)
            "Dégagé ${Forecast.time(n.bestFromMs)} → ${Forecast.time(n.bestToMs)} (${n.bestHours} h)"
        else
            "Aucun créneau dégagé"
        box.addView(label(window, 12f, if (n.bestFromMs != null) WHITE else ORANGE, Gravity.START))
        box.addView(
            label(
                "Nuit noire ${Forecast.time(n.darkStartMs)} → ${Forecast.time(n.darkEndMs)}",
                11f, GREY, Gravity.START
            )
        )

        val moonTimes = buildString {
            n.moonSetMs?.let { if (it in n.duskMs..n.dawnMs) append(" · couche ${Forecast.time(it)}") }
            n.moonRiseMs?.let { if (it in n.duskMs..n.dawnMs) append(" · lève ${Forecast.time(it)}") }
        }
        box.addView(
            label(
                "${n.moonWord} ${Math.round(n.moonFraction * 100)} %$moonTimes",
                11f, GREY, Gravity.START
            )
        )
        n.seeingMedian?.let {
            box.addView(
                label(
                    "Seeing estimé " + String.format(Locale.FRANCE, "%.1f", it) + "\"",
                    11f, GREY, Gravity.START
                )
            )
        }
        return box
    }

    // ------------------------------------------------------------- utilitaires

    private fun title(t: String) = label(t, 15f, WHITE, Gravity.CENTER, bold = true)

    private fun refreshButton(): View = label("↻  Actualiser", 13f, WHITE, Gravity.CENTER).apply {
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setBackgroundColor(0xFF201F1E.toInt())
        isClickable = true
        setOnClickListener { refresh(force = true) }
    }

    private fun label(
        t: String,
        sizeSp: Float,
        color: Int,
        gravity: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        this.gravity = gravity
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WHITE = Color.WHITE
        private val GREY = 0xFF9A9A94.toInt()
        private val ORANGE = 0xFFEC835A.toInt()
    }
}
