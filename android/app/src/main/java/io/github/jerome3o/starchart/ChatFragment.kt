package io.github.jerome3o.starchart

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat with Claude. The server runs Claude with every Starchart MCP tool
 * (goals, nudges, location, phone commands) as this phone's user; the app
 * keeps the transcript locally and sends the text history each turn.
 */
class ChatFragment : Fragment() {

    private data class Message(val role: String, val text: String, val tools: List<String> = emptyList(), val error: Boolean = false)

    private val messages = mutableListOf<Message>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: Button
    private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.chat_list)
        input = view.findViewById(R.id.chat_input)
        send = view.findViewById(R.id.chat_send)
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.adapter = adapter
        load()
        send.setOnClickListener { sendMessage() }
        view.findViewById<Button>(R.id.chat_new).setOnClickListener {
            if (busy) return@setOnClickListener
            messages.clear()
            save()
            adapter.notifyDataSetChanged()
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!Sync.isLinked(requireContext())) {
            append(Message("assistant", getString(R.string.chat_not_linked), error = true))
            return
        }
        input.setText("")
        append(Message("user", text))
        save()
        setBusy(true)
        val app = requireContext().applicationContext
        val history = JSONArray().apply {
            messages.filter { !it.error }.takeLast(40).forEach {
                put(JSONObject().put("role", it.role).put("content", it.text))
            }
        }
        Thread {
            val result = try {
                val response = Sync.call(app, "POST", "/api/chat", JSONObject().put("messages", history), readTimeoutMs = 240_000)
                val tools = response.optJSONArray("tools")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                Message("assistant", response.optString("reply"), tools.distinct())
            } catch (e: Exception) {
                Message("assistant", getStringSafe(e), error = true)
            }
            // Claude may have logged or edited goals: refresh the cache and widget.
            if (!result.error && result.tools.isNotEmpty()) try { GoalsApi.fetch(app) } catch (_: Exception) {}
            main.post {
                if (!isAdded) return@post
                setBusy(false)
                append(result)
                if (!result.error) save()
            }
        }.start()
    }

    private fun getStringSafe(e: Exception) =
        (e.message ?: e.javaClass.simpleName).let { if (it.contains("503")) "Chat isn't set up on the server yet (no Anthropic API key)." else "Couldn't reach Claude: $it" }

    // While waiting, a "thinking" bubble sits at the end of the list.
    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        send.isEnabled = !value
        if (value) {
            adapter.notifyItemInserted(messages.size)
            list.scrollToPosition(messages.size)
        } else {
            adapter.notifyItemRemoved(messages.size)
        }
    }

    private fun append(message: Message) {
        messages += message
        adapter.notifyItemInserted(messages.size - 1)
        list.scrollToPosition(messages.size - 1 + if (busy) 1 else 0)
    }

    private fun load() {
        val raw = Prefs.get(requireContext()).getString(Prefs.KEY_CHAT_HISTORY, null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val tools = o.optJSONArray("tools")?.let { t -> List(t.length()) { t.getString(it) } } ?: emptyList()
                messages += Message(o.getString("role"), o.getString("text"), tools)
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val a = JSONArray()
        messages.filter { !it.error }.takeLast(100).forEach {
            a.put(JSONObject().put("role", it.role).put("text", it.text).put("tools", JSONArray(it.tools)))
        }
        Prefs.get(requireContext()).edit().putString(Prefs.KEY_CHAT_HISTORY, a.toString()).apply()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = messages.size + if (busy) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val density = parent.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val row = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, dp(4), 0, dp(4))
            }
            val bubble = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            bubble.addView(TextView(parent.context).apply {
                setTextIsSelectable(true)
                textSize = 15f
            })
            bubble.addView(TextView(parent.context).apply {
                textSize = 11f
                alpha = 0.7f
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(bubble, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val m = messages.getOrNull(position) ?: Message("assistant", getString(R.string.chat_thinking))
            val row = holder.itemView as FrameLayout
            val bubble = row.getChildAt(0) as LinearLayout
            val text = bubble.getChildAt(0) as TextView
            val caption = bubble.getChildAt(1) as TextView
            val density = row.resources.displayMetrics.density
            val mine = m.role == "user"
            val bg = MaterialColors.getColor(row, when {
                m.error -> com.google.android.material.R.attr.colorErrorContainer
                mine -> com.google.android.material.R.attr.colorPrimaryContainer
                else -> com.google.android.material.R.attr.colorSurfaceContainerHigh
            })
            val fg = MaterialColors.getColor(row, when {
                m.error -> com.google.android.material.R.attr.colorOnErrorContainer
                mine -> com.google.android.material.R.attr.colorOnPrimaryContainer
                else -> com.google.android.material.R.attr.colorOnSurface
            })
            bubble.background = GradientDrawable().apply { setColor(bg); cornerRadius = 18 * density }
            text.setTextColor(fg)
            caption.setTextColor(fg)
            text.text = m.text
            caption.text = if (m.tools.isEmpty()) "" else "🔧 " + m.tools.distinct().joinToString(", ")
            caption.visibility = if (m.tools.isEmpty()) View.GONE else View.VISIBLE
            bubble.layoutParams = (bubble.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                if (mine) { leftMargin = (48 * density).toInt(); rightMargin = 0 } else { rightMargin = (48 * density).toInt(); leftMargin = 0 }
            }
        }
    }
}
