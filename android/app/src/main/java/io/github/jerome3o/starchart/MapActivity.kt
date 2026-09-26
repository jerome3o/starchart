package io.github.jerome3o.starchart

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var db: LocationDb
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var day: LocalDate = LocalDate.now()
    private var daysWithData: List<LocalDate> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = LocationDb(this)
        mapView = findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)

        daysWithData = db.daysWithFixes(ZoneId.systemDefault())
        day = daysWithData.lastOrNull() ?: LocalDate.now()

        findViewById<Button>(R.id.btn_prev_day).setOnClickListener {
            daysWithData.lastOrNull { it < day }?.let { show(it) }
        }
        findViewById<Button>(R.id.btn_next_day).setOnClickListener {
            daysWithData.firstOrNull { it > day }?.let { show(it) }
        }
        findViewById<TextView>(R.id.day_label).setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth -> show(LocalDate.of(year, month + 1, dayOfMonth)) },
                day.year, day.monthValue - 1, day.dayOfMonth
            ).show()
        }

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        mapView.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromUri(if (night) STYLE_DARK else STYLE_LIGHT)) { s ->
                style = s
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
                                Expression.has("end"), Expression.literal(7f), Expression.literal(3.5f)
                            )
                        ),
                        PropertyFactory.circleColor(Expression.get("color")),
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
                m.addOnMapClickListener { latLng -> onTap(m, latLng) }
                show(day)
            }
        }
    }

    private fun show(newDay: LocalDate) {
        day = newDay
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val fixes = db.fixesBetween(from, to)

        findViewById<TextView>(R.id.day_label).text =
            day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        findViewById<Button>(R.id.btn_prev_day).isEnabled = daysWithData.any { it < day }
        findViewById<Button>(R.id.btn_next_day).isEnabled = daysWithData.any { it > day }

        val stats = findViewById<TextView>(R.id.map_stats)
        val s = style ?: return
        val lineSource = s.getSourceAs<GeoJsonSource>(SRC_LINE)
        val pointSource = s.getSourceAs<GeoJsonSource>(SRC_POINTS)

        if (fixes.isEmpty()) {
            stats.text = getString(R.string.map_no_fixes)
            lineSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            pointSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }

        val points = fixes.mapIndexed { i, fix ->
            val props = JsonObject().apply {
                addProperty("time", fix.timeMs)
                addProperty("accuracy", fix.accuracyM)
                addProperty(
                    "color",
                    when (i) {
                        0 -> "#4fd1a5"
                        fixes.lastIndex -> "#ffffff"
                        else -> GOLD
                    }
                )
                if (i == 0 || i == fixes.lastIndex) addProperty("end", true)
            }
            Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat), props)
        }
        pointSource?.setGeoJson(FeatureCollection.fromFeatures(points))

        val line = if (fixes.size > 1) {
            Feature.fromGeometry(LineString.fromLngLats(fixes.map { Point.fromLngLat(it.lon, it.lat) }))
        } else null
        lineSource?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line)))

        // Skip hops shorter than the GPS uncertainty so a stationary phone
        // doesn't accumulate phantom distance.
        var distanceM = 0f
        val result = FloatArray(1)
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]
            val b = fixes[i]
            Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, result)
            if (result[0] > maxOf(a.accuracyM, b.accuracyM)) distanceM += result[0]
        }
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        stats.text = getString(
            R.string.map_stats,
            fixes.size,
            distanceM / 1000f,
            Instant.ofEpochMilli(fixes.first().timeMs).atZone(zone).format(timeFmt),
            Instant.ofEpochMilli(fixes.last().timeMs).atZone(zone).format(timeFmt),
        )

        val m = map ?: return
        val spread = fixes.any { it.lat != fixes[0].lat || it.lon != fixes[0].lon }
        if (spread) {
            val bounds = LatLngBounds.Builder()
            fixes.forEach { bounds.include(LatLng(it.lat, it.lon)) }
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90))
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fixes[0].lat, fixes[0].lon), 15.0))
        }
    }

    private fun onTap(m: MapLibreMap, latLng: LatLng): Boolean {
        val screen = m.projection.toScreenLocation(latLng)
        val touchArea = RectF(screen.x - 30f, screen.y - 30f, screen.x + 30f, screen.y + 30f)
        val hit = m.queryRenderedFeatures(touchArea, LAYER_POINTS).firstOrNull() ?: return false
        val time = hit.getNumberProperty("time")?.toLong() ?: return false
        val accuracy = hit.getNumberProperty("accuracy")?.toInt() ?: 0
        val when_ = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        Toast.makeText(this, getString(R.string.map_point_info, when_, accuracy), Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        private const val SRC_LINE = "fix-line-src"
        private const val SRC_POINTS = "fix-points-src"
        private const val LAYER_LINE = "fix-line"
        private const val LAYER_POINTS = "fix-points"
        private const val GOLD = "#ffc93c"
    }
}
