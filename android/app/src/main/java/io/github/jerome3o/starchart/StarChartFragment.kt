package io.github.jerome3o.starchart

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/** Placeholder star chart — the real goal list will come from the backend. */
class StarChartFragment : Fragment() {

    private val goals = listOf(
        Goal("water_plants", "Water the plants"),
        Goal("morning_run", "Go for a morning run"),
        Goal("read_book", "Read for 20 minutes"),
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_starchart, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.goal_container)
        val prefs = Prefs.get(requireContext())
        val inflater = LayoutInflater.from(requireContext())

        goals.forEach { goal ->
            val row = inflater.inflate(R.layout.item_goal, container, false)
            val stars = row.findViewById<TextView>(R.id.goal_stars)

            fun render(count: Int) {
                row.findViewById<TextView>(R.id.goal_name).text = goal.name
                stars.text = if (count == 0) {
                    getString(R.string.no_stars_yet)
                } else {
                    "⭐".repeat(count.coerceAtMost(10)) + if (count > 10) " ×$count" else ""
                }
            }

            render(prefs.getInt(goal.id, 0))

            row.findViewById<Button>(R.id.goal_add_star).setOnClickListener {
                val count = prefs.getInt(goal.id, 0) + 1
                prefs.edit().putInt(goal.id, count).apply()
                render(count)
            }

            container.addView(row)
        }
    }

    private data class Goal(val id: String, val name: String)
}
