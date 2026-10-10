package io.github.jerome3o.starchart

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs

/**
 * Photos from the phone's gallery for one day, placed on the map. A photo's
 * own GPS tag wins; without one it borrows the tracked location nearest in
 * time (within [MAX_TRACK_GAP_MS]). Everything stays on the phone.
 */
object DayPhotos {

    enum class Source { PHOTO_GPS, TRACK, NONE }

    data class Photo(
        val id: Long,
        val uri: Uri,
        val takenMs: Long,
        val lat: Double?,
        val lon: Double?,
        val source: Source,
    )

    private const val MAX_TRACK_GAP_MS = 20 * 60_000L
    private const val MAX_PHOTOS = 150

    /** The permissions to ask for: images, plus media location so EXIF GPS isn't redacted. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** True when the app can read at least some photos (full or user-selected). */
    fun canRead(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun canReadLocation(context: Context) =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** Photos taken in [fromMs, toMs), oldest first. Call off the main thread. */
    fun load(context: Context, fromMs: Long, toMs: Long, fixes: List<LocationDb.StoredFix>): List<Photo> {
        if (!canRead(context)) return emptyList()
        val resolver = context.contentResolver
        val photos = mutableListOf<Photo>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf(fromMs.toString(), toMs.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            while (c.moveToNext() && photos.size < MAX_PHOTOS) {
                val id = c.getLong(0)
                val taken = c.getLong(1)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val exif = if (canReadLocation(context)) exifLatLon(context, uri) else null
                val track = if (exif == null) nearestFix(fixes, taken) else null
                photos += when {
                    exif != null -> Photo(id, uri, taken, exif.first, exif.second, Source.PHOTO_GPS)
                    track != null -> Photo(id, uri, taken, track.lat, track.lon, Source.TRACK)
                    else -> Photo(id, uri, taken, null, null, Source.NONE)
                }
            }
        }
        return photos
    }

    private fun exifLatLon(context: Context, uri: Uri): Pair<Double, Double>? = try {
        val original = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(original)?.use { stream ->
            ExifInterface(stream).latLong?.let { (lat, lon) ->
                if (lat == 0.0 && lon == 0.0) null else lat to lon
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun nearestFix(fixes: List<LocationDb.StoredFix>, timeMs: Long): LocationDb.StoredFix? {
        if (fixes.isEmpty()) return null
        var lo = 0
        var hi = fixes.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (fixes[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(fixes.getOrNull(lo - 1), fixes.getOrNull(lo)).minByOrNull { abs(it.timeMs - timeMs) }
        return best?.takeIf { abs(it.timeMs - timeMs) <= MAX_TRACK_GAP_MS }
    }

    /**
     * The real photo decoded at up to [maxPx] on its long edge (never upscaled),
     * with EXIF rotation applied. MediaStore thumbnails top out at a few hundred
     * pixels, so the full-screen viewer uses this instead.
     */
    fun fullImage(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return thumbnail(context, uri, maxPx)
        return try {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (long > maxPx) {
                    val scale = maxPx.toDouble() / long
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (_: Exception) {
            thumbnail(context, uri, maxPx)
        }
    }

    /** Square thumbnail, or null if the image can't be read. */
    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Round map marker: the thumbnail centre-cropped in a white ring with a dark edge. */
    fun marker(thumb: Bitmap, sizePx: Int, ringPx: Float): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF111111.toInt()
        canvas.drawCircle(r, r, r, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(r, r, r - ringPx * 0.4f, paint)
        val inner = r - ringPx
        val side = minOf(thumb.width, thumb.height)
        val scale = (inner * 2) / side
        val shader = BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply {
            postTranslate(-(thumb.width - side) / 2f, -(thumb.height - side) / 2f)
            postScale(scale, scale)
            postTranslate(r - inner, r - inner)
        })
        canvas.drawCircle(r, r, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return out
    }
}
