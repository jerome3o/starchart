package io.github.jerome3o.starchart

import android.app.DatePickerDialog
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Day-by-day explorer of the phone's own location history, drawn with
 * MapLibre on OpenFreeMap vector tiles (no API key). Reads straight from the
 * local database, so it works whether or not the server is reachable.
 */
class MapFragment : Fragment() {

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var currentStyleUrl: String? = null
    private lateinit var db: LocationDb
    private var day: LocalDate = LocalDate.now()
    private var daysWithData: List<LocalDate> = emptyList()
    private var pickedInitialDay = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        MapLibre.getInstance(requireContext())
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = LocationDb(requireContext())
        mapView = view.findViewById<MapView>(R.id.map_view).also { it.onCreate(savedInstanceState) }

        view.findViewById<Button>(R.id.btn_prev_day).setOnClickListener {
            daysWithData.lastOrNull { it < day }?.let { show(it) }
        }
        view.findViewById<Button>(R.id.btn_next_day).setOnClickListener {
            daysWithData.firstOrNull { it > day }?.let { show(it) }
        }
        view.findViewById<TextView>(R.id.day_label).setOnClickListener {
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth -> show(LocalDate.of(year, month + 1, dayOfMonth)) },
                day.year, day.monthValue - 1, day.dayOfMonth
            ).show()
        }

        mapView?.getMapAsync { m ->
            map = m
            m.addOnMapClickListener { latLng -> onTap(m, latLng) }
            refresh()
        }
    }

    /** Reloads the day list, applies a changed style, and redraws the current day. */
    private fun refresh() {
        daysWithData = db.daysWithFixes(ZoneId.systemDefault())
        if (!pickedInitialDay && daysWithData.isNotEmpty()) {
            day = daysWithData.last()
            pickedInitialDay = true
        }
        val m = map ?: return
        val url = selectedStyleUrl()
        if (url != currentStyleUrl) {
            currentStyleUrl = url
            style = null
            m.setStyle(Style.Builder().fromUri(url)) { s ->
                style = s
                addLayers(s)
                show(day)
            }
        } else {
            show(day)
        }
    }

    private fun selectedStyleUrl(): String {
        val key = Prefs.get(requireContext()).getString(Prefs.KEY_MAP_STYLE, DEFAULT_STYLE) ?: DEFAULT_STYLE
        return "https://tiles.openfreemap.org/styles/${if (key in STYLE_KEYS) key else DEFAULT_STYLE}"
    }

    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource(SRC_LINE))
        s.addSource(GeoJsonSource(SRC_POINTS))
        s.addLayer(
            LineLayer(LAYER_LINE, SRC_LINE).withProperties(
                PropertyFactory.lineColor(GOLD),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        )
        s.addLayer(
            CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.has("end"), Expression.literal(7f),
                        Expression.has("poor"), Expression.literal(2.5f),
                        Expression.literal(3.5f)
                    )
                ),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleOpacity(
                    Expression.switchCase(
                        Expression.has("poor"), Expression.literal(0.45f), Expression.literal(1f)
                    )
                ),
                PropertyFactory.circleStrokeColor(
                    Expression.switchCase(
                        Expression.has("end"), Expression.literal("#ffffff"), Expression.literal("#000000")
                    )
                ),
                PropertyFactory.circleStrokeWidth(
                    Expression.switchCase(
                        Expression.has("end"), Expression.literal(2f), Expression.literal(1f)
                    )
                ),
            )
        )
    }

    private fun show(newDay: LocalDate) {
        val view = view ?: return
        day = newDay
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val all = db.fixesBetween(from, to)
        // Fixes with poor accuracy (typically cell-tower guesses) are shown
        // faded but kept out of the path, distance and framing.
        val good = all.filter { it.accuracyM <= MAX_GOOD_ACCURACY_M }
        val framed = good.ifEmpty { all }

        view.findViewById<TextView>(R.id.day_label).text =
            day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        view.findViewById<Button>(R.id.btn_prev_day).isEnabled = daysWithData.any { it < day }
        view.findViewById<Button>(R.id.btn_next_day).isEnabled = daysWithData.any { it > day }

        val stats = view.findViewById<TextView>(R.id.map_stats)
        val s = style ?: return
        val lineSource = s.getSourceAs<GeoJsonSource>(SRC_LINE)
        val pointSource = s.getSourceAs<GeoJsonSource>(SRC_POINTS)

        if (all.isEmpty()) {
            stats.text = getString(R.string.map_no_fixes)
            lineSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            pointSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }

        val points = all.map { fix ->
            val poor = fix.accuracyM > MAX_GOOD_ACCURACY_M
            val props = JsonObject().apply {
                addProperty("time", fix.timeMs)
                addProperty("accuracy", fix.accuracyM)
                addProperty(
                    "color",
                    when {
                        poor -> "#9aa4bf"
                        fix === good.firstOrNull() -> "#4fd1a5"
                        fix === good.lastOrNull() -> "#ffffff"
                        else -> GOLD
                    }
                )
                if (poor) addProperty("poor", true)
                else if (fix === good.firstOrNull() || fix === good.lastOrNull()) addProperty("end", true)
            }
            Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat), props)
        }
        pointSource?.setGeoJson(FeatureCollection.fromFeatures(points))

        val line = if (good.size > 1) {
            Feature.fromGeometry(LineString.fromLngLats(good.map { Point.fromLngLat(it.lon, it.lat) }))
        } else null
        lineSource?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line)))

        // Skip hops shorter than the GPS uncertainty so a stationary phone
        // doesn't accumulate phantom distance.
        var distanceM = 0f
        val result = FloatArray(1)
        for (i in 1 until good.size) {
            val a = good[i - 1]
            val b = good[i]
            Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, result)
            if (result[0] > maxOf(a.accuracyM, b.accuracyM)) distanceM += result[0]
        }
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        var text = getString(
            R.string.map_stats,
            framed.size,
            distanceM / 1000f,
            Instant.ofEpochMilli(framed.first().timeMs).atZone(zone).format(timeFmt),
            Instant.ofEpochMilli(framed.last().timeMs).atZone(zone).format(timeFmt),
        )
        val poorCount = all.size - good.size
        if (poorCount > 0 && good.isNotEmpty()) {
            text += "\n" + getString(R.string.map_stats_poor, poorCount)
        }
        stats.text = text

        val m = map ?: return
        val spread = framed.any { it.lat != framed[0].lat || it.lon != framed[0].lon }
        if (spread) {
            val bounds = LatLngBounds.Builder()
            framed.forEach { bounds.include(LatLng(it.lat, it.lon)) }
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90))
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(framed[0].lat, framed[0].lon), 15.0))
        }
    }

    private fun onTap(m: MapLibreMap, latLng: LatLng): Boolean {
        val screen = m.projection.toScreenLocation(latLng)
        val touchArea = RectF(screen.x - 30f, screen.y - 30f, screen.x + 30f, screen.y + 30f)
        val hit = m.queryRenderedFeatures(touchArea, LAYER_POINTS).firstOrNull() ?: return false
        val time = hit.getNumberProperty("time")?.toLong() ?: return false
        val accuracy = hit.getNumberProperty("accuracy")?.toInt() ?: 0
        val at = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        Toast.makeText(requireContext(), getString(R.string.map_point_info, at, accuracy), Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (!isHidden) refresh()
    }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }
    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
        currentStyleUrl = null
        super.onDestroyView()
    }

    companion object {
        val STYLE_KEYS = listOf("liberty", "bright", "positron", "fiord", "dark")
        const val DEFAULT_STYLE = "liberty"
        private const val MAX_GOOD_ACCURACY_M = 250f
        private const val SRC_LINE = "fix-line-src"
        private const val SRC_POINTS = "fix-points-src"
        private const val LAYER_LINE = "fix-line"
        private const val LAYER_POINTS = "fix-points"
        private const val GOLD = "#ffc93c"
    }
}
