package io.github.jerome3o.starchart

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import org.maplibre.android.style.layers.SymbolLayer
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
 *
 * With photo access, the day's gallery photos appear as round thumbnails on
 * the map (at their GPS tag, or where the track was when they were taken)
 * and in a strip along the bottom: tap one to find it, tap again to open it.
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

    private val main = Handler(Looper.getMainLooper())
    private var photos: List<DayPhotos.Photo> = emptyList()
    private var thumbs: Map<Long, Bitmap> = emptyMap()
    private var photoIcons: Set<String> = emptySet()
    private var selectedPhoto: Long? = null
    private var photoLoad = 0
    private var photoStrip: RecyclerView? = null

    private val requestPhotos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { show(day) }

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

        view.findViewById<Button>(R.id.btn_photos).setOnClickListener {
            requestPhotos.launch(DayPhotos.permissions())
        }
        photoStrip = view.findViewById<RecyclerView>(R.id.photo_strip).also {
            it.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            it.adapter = stripAdapter
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
        s.addSource(GeoJsonSource(SRC_PHOTOS))
        photoIcons = emptySet() // a new style has none of the old images
        // A white casing under a black line stays visible on any basemap,
        // light or dark, instead of blending into yellow roads.
        s.addLayer(
            LineLayer(LAYER_LINE_CASING, SRC_LINE).withProperties(
                PropertyFactory.lineColor("#ffffff"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        )
        s.addLayer(
            LineLayer(LAYER_LINE, SRC_LINE).withProperties(
                PropertyFactory.lineColor("#111111"),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        )
        s.addLayer(
            CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.has("end"), Expression.literal(8f),
                        Expression.has("poor"), Expression.literal(2.5f),
                        Expression.literal(4f)
                    )
                ),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleOpacity(
                    Expression.switchCase(
                        Expression.has("poor"), Expression.literal(0.45f), Expression.literal(1f)
                    )
                ),
                PropertyFactory.circleStrokeColor("#111111"),
                PropertyFactory.circleStrokeWidth(
                    Expression.switchCase(
                        Expression.has("end"), Expression.literal(2.5f), Expression.literal(1.5f)
                    )
                ),
            )
        )
        s.addLayer(
            SymbolLayer(LAYER_PHOTOS, SRC_PHOTOS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(
                    Expression.switchCase(Expression.has("selected"), Expression.literal(1.6f), Expression.literal(1f))
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(
                    Expression.switchCase(Expression.has("selected"), Expression.literal(1f), Expression.literal(0f))
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

        loadPhotos(from, to, good)
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

    // --- Photos ------------------------------------------------------------------

    /** Loads the day's photos off the main thread and draws them when ready. */
    private fun loadPhotos(from: Long, to: Long, fixes: List<LocationDb.StoredFix>) {
        val context = requireContext().applicationContext
        val view = view ?: return
        val token = ++photoLoad
        val canRead = DayPhotos.canRead(context)
        view.findViewById<Button>(R.id.btn_photos).visibility = if (canRead) View.GONE else View.VISIBLE
        if (!canRead) {
            setPhotos(emptyList(), emptyMap(), emptyMap())
            return
        }
        val density = resources.displayMetrics.density
        val markerPx = (46 * density).toInt()
        Thread {
            val list = DayPhotos.load(context, from, to, fixes)
            val stripThumbs = HashMap<Long, Bitmap>()
            val markers = HashMap<String, Bitmap>()
            for (p in list) {
                if (token != photoLoad) return@Thread
                val thumb = DayPhotos.thumbnail(context, p.uri, (80 * density).toInt()) ?: continue
                stripThumbs[p.id] = thumb
                if (p.lat != null) markers[iconId(p.id)] = DayPhotos.marker(thumb, markerPx, 3 * density)
            }
            main.post { if (token == photoLoad && isAdded) setPhotos(list, stripThumbs, markers) }
        }.start()
    }

    private fun iconId(id: Long) = "photo-$id"

    private fun setPhotos(list: List<DayPhotos.Photo>, stripThumbs: Map<Long, Bitmap>, markers: Map<String, Bitmap>) {
        photos = list
        thumbs = stripThumbs
        selectedPhoto = null
        stripAdapter.notifyDataSetChanged()
        photoStrip?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val s = style ?: return
        photoIcons.forEach { s.removeImage(it) }
        if (markers.isNotEmpty()) s.addImages(HashMap(markers))
        photoIcons = markers.keys
        drawPhotoMarkers()

        val located = list.filter { it.lat != null }
        val stats = view?.findViewById<TextView>(R.id.map_stats)
        if (list.isNotEmpty() && stats != null) {
            val base = stats.text.toString().substringBefore("\n📷")
            stats.text = base + "\n📷 " + getString(R.string.map_photos_count, list.size, located.size)
        }
        // A day with photos but no tracked fixes: frame the photos instead.
        if (located.isNotEmpty() && db.fixesBetween(
                day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ).isEmpty()
        ) {
            val m = map ?: return
            if (located.size == 1) {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(located[0].lat!!, located[0].lon!!), 15.0))
            } else {
                val bounds = LatLngBounds.Builder()
                located.forEach { bounds.include(LatLng(it.lat!!, it.lon!!)) }
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
            }
        }
    }

    private fun drawPhotoMarkers() {
        val source = style?.getSourceAs<GeoJsonSource>(SRC_PHOTOS) ?: return
        val features = photos.filter { it.lat != null && iconId(it.id) in photoIcons }.map { p ->
            val props = JsonObject().apply {
                addProperty("photo", p.id)
                addProperty("icon", iconId(p.id))
                if (p.id == selectedPhoto) addProperty("selected", true)
            }
            Feature.fromGeometry(Point.fromLngLat(p.lon!!, p.lat!!), props)
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** First tap finds the photo on the map; a second tap opens the full-screen viewer. */
    private fun selectPhoto(p: DayPhotos.Photo, quiet: Boolean = false) {
        if (selectedPhoto == p.id) {
            if (!quiet) PhotoViewer(requireContext(), photos, photos.indexOf(p), thumbs) { shown -> selectPhoto(shown, quiet = true) }.show()
            return
        }
        val previous = photos.indexOfFirst { it.id == selectedPhoto }
        selectedPhoto = p.id
        val index = photos.indexOf(p)
        if (previous >= 0) stripAdapter.notifyItemChanged(previous)
        stripAdapter.notifyItemChanged(index)
        photoStrip?.smoothScrollToPosition(index)
        drawPhotoMarkers()
        val time = Instant.ofEpochMilli(p.takenMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val message = when (p.source) {
            DayPhotos.Source.PHOTO_GPS -> getString(R.string.map_photo_tap_again, time)
            DayPhotos.Source.TRACK -> getString(R.string.map_photo_approx, time)
            DayPhotos.Source.NONE -> getString(R.string.map_photo_no_location, time)
        }
        if (!quiet) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        val m = map ?: return
        if (p.lat != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon!!), maxOf(m.cameraPosition.zoom, 16.0)))
        }
    }

    private val stripAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = parent.resources.displayMetrics.density
            val frame = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams((80 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (6 * d).toInt()
                }
                setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            }
            frame.addView(ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(0xFF333333.toInt()) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(TextView(parent.context).apply {
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
                setPadding((5 * d).toInt(), 0, (5 * d).toInt(), (3 * d).toInt())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            return object : RecyclerView.ViewHolder(frame) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val p = photos[position]
            val frame = holder.itemView as FrameLayout
            val d = frame.resources.displayMetrics.density
            (frame.getChildAt(0) as ImageView).setImageBitmap(thumbs[p.id])
            val time = Instant.ofEpochMilli(p.takenMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            (frame.getChildAt(1) as TextView).text = when (p.source) {
                DayPhotos.Source.PHOTO_GPS -> "📍 $time"
                DayPhotos.Source.TRACK -> "≈ $time"
                DayPhotos.Source.NONE -> time
            }
            frame.background = if (p.id == selectedPhoto) GradientDrawable().apply {
                cornerRadius = 12 * d; setColor(0x00000000); setStroke((2.5f * d).toInt(), 0xFFFFC93C.toInt())
            } else null
            frame.setOnClickListener { selectPhoto(p) }
        }
    }

    private fun onTap(m: MapLibreMap, latLng: LatLng): Boolean {
        val screen = m.projection.toScreenLocation(latLng)
        val photoArea = RectF(screen.x - 40f, screen.y - 40f, screen.x + 40f, screen.y + 40f)
        m.queryRenderedFeatures(photoArea, LAYER_PHOTOS).firstOrNull()?.getNumberProperty("photo")?.toLong()?.let { id ->
            photos.firstOrNull { it.id == id }?.let { selectPhoto(it); return true }
        }
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
        private const val LAYER_LINE_CASING = "fix-line-casing"
        private const val LAYER_LINE = "fix-line"
        private const val LAYER_POINTS = "fix-points"
        private const val SRC_PHOTOS = "photos-src"
        private const val LAYER_PHOTOS = "photos"
        private const val GOLD = "#ffc93c"
    }
}
