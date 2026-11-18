package fr.darmagnac.cielastro

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * Tuile « Ciel » : verdict de la nuit qui vient, d'un coup d'œil au poignet.
 * Les données sont rafraîchies au maximum toutes les 30 min (cache dans Forecast),
 * et la tuile elle-même redemande un rendu toutes les heures.
 */
class SkyTileService : TileService() {

    private val resourcesVersion = "1"

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture<TileBuilders.Tile> { completer ->
            Thread {
                val layout = try {
                    val result = Forecast.load(applicationContext, force = false)
                    val night = result.nights.firstOrNull()
                    if (night == null) layout(message = result.error ?: "Pas de données")
                    else layout(night = night, stale = result.error != null)
                } catch (e: Exception) {
                    layout(message = "Erreur")
                }
                completer.set(
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(resourcesVersion)
                        .setFreshnessIntervalMillis(60 * 60 * 1000L)
                        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                        .build()
                )
            }.start()
            "CielAstroTile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture<ResourceBuilders.Resources> { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder().setVersion(resourcesVersion).build()
            )
            "CielAstroResources"
        }

    // ------------------------------------------------------------------ rendu

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                        else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()

    private fun spacer(height: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun root(column: LayoutElementBuilders.Column): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(18f)).setEnd(dp(18f))
                            .setTop(dp(10f)).setBottom(dp(10f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

    private fun layout(message: String): LayoutElementBuilders.LayoutElement =
        root(
            LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text("Ciel", 13f, GREY))
                .addContent(spacer(6f))
                .addContent(text(message, 15f, WHITE, bold = true))
                .addContent(spacer(6f))
                .addContent(text("Toucher pour réessayer", 11f, GREY))
                .build()
        )

    private fun layout(night: Night, stale: Boolean): LayoutElementBuilders.LayoutElement {
        val v = night.verdict
        val window = if (night.bestFromMs != null)
            "${Forecast.time(night.bestFromMs)} → ${Forecast.time(night.bestToMs)}"
        else
            "aucun créneau dégagé"

        val header = Forecast.dayShort(night.duskMs) + if (stale) " · ancien" else ""

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(header, 12f, GREY))
            .addContent(spacer(2f))
            .addContent(text("${night.score}", 36f, v.color, bold = true))
            .addContent(text(v.label, 14f, v.color, bold = true))
            .addContent(spacer(6f))
            .addContent(text(window, 13f, WHITE))
            .addContent(spacer(4f))
            .addContent(
                text(
                    "Lune ${Math.round(night.moonFraction * 100)} %" +
                        (night.seeingMedian?.let { " · seeing ${fmt1(it)}\"" } ?: ""),
                    12f, GREY
                )
            )
            .build()

        return root(column)
    }

    private fun fmt1(v: Double) = String.format(java.util.Locale.FRANCE, "%.1f", v)

    companion object {
        private val WHITE = 0xFFFFFFFF.toInt()
        private val GREY = 0xFF9A9A94.toInt()
    }
}
