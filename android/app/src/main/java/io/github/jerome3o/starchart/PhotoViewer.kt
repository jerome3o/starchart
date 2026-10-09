package io.github.jerome3o.starchart

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Full-screen, swipeable viewer for a day's photos. Swiping reports the photo
 * now on screen through [onPage] (the map follows along); ✕ or back closes it.
 */
class PhotoViewer(
    context: Context,
    private val photos: List<DayPhotos.Photo>,
    startIndex: Int,
    private val onPage: (DayPhotos.Photo) -> Unit,
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val screenPx = minOf(2048, maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels))
    // The current image and its neighbours; screen-sized bitmaps are large.
    private val cache = object : LruCache<Long, Bitmap>(3) {}
    private val caption = TextView(context)
    private var current = -1

    init {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val pager = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = PageAdapter()
        }
        val snap = PagerSnapHelper().also { it.attachToRecyclerView(pager) }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val v = snap.findSnapView(rv.layoutManager) ?: return
                pageShown(rv.getChildAdapterPosition(v))
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val pad = (16 * density).toInt()
        caption.apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(TextView(context).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(pad, pad / 2, pad, pad)
            contentDescription = "Close"
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        setContentView(root)
        pager.scrollToPosition(startIndex)
        pageShown(startIndex)
    }

    private fun pageShown(index: Int) {
        if (index !in photos.indices || index == current) return
        current = index
        val p = photos[index]
        val time = Instant.ofEpochMilli(p.takenMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val where = when (p.source) {
            DayPhotos.Source.PHOTO_GPS -> "📍"
            DayPhotos.Source.TRACK -> "≈ 📍"
            DayPhotos.Source.NONE -> ""
        }
        caption.text = "${index + 1} / ${photos.size}   $time  $where"
        onPage(p)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return object : RecyclerView.ViewHolder(image) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val image = holder.itemView as ImageView
            val p = photos[position]
            image.tag = p.id
            val cached = cache.get(p.id)
            image.setImageBitmap(cached)
            if (cached != null) return
            val app = context.applicationContext
            Thread {
                val bmp = DayPhotos.thumbnail(app, p.uri, screenPx) ?: return@Thread
                main.post {
                    cache.put(p.id, bmp)
                    if (image.tag == p.id) image.setImageBitmap(bmp)
                }
            }.start()
        }
    }
}
