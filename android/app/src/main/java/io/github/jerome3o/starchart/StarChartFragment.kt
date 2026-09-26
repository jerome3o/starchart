package io.github.jerome3o.starchart

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** The star chart: goals for the current period with pace bars and done/undo. */
class StarChartFragment : Fragment() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val sound = ChargeSound()
    private lateinit var haptics: Haptics
    private val shakeRandom = java.util.Random()
    private var snapshot: GoalsApi.Snapshot? = null
    private lateinit var adapter: GoalAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_starchart, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        haptics = Haptics(requireContext())
        adapter = GoalAdapter().apply { setHasStableIds(true) }
        val list = view.findViewById<RecyclerView>(R.id.goal_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh).setOnRefreshListener { load() }
        view.findViewById<ExtendedFloatingActionButton>(R.id.fab_new_goal).setOnClickListener { showGoalDialog(null) }

        GoalsApi.cached(requireContext())?.let { render(it) }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) load()
    }

    override fun onPause() {
        sound.stop()
        stopShake()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // --- Loading & rendering ---------------------------------------------------

    private fun load() {
        val view = view ?: return
        val context = requireContext()
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        val empty = view.findViewById<TextView>(R.id.empty_text)
        val fab = view.findViewById<ExtendedFloatingActionButton>(R.id.fab_new_goal)
        if (!Sync.isLinked(context)) {
            swipe.isRefreshing = false
            empty.text = getString(R.string.goals_unlinked)
            empty.visibility = View.VISIBLE
            fab.hide()
            adapter.submit(emptyList())
            return
        }
        fab.show()
        io.execute {
            try {
                val fresh = GoalsApi.fetch(context)
                main.post { if (isAdded) { render(fresh); swipe.isRefreshing = false } }
            } catch (e: Exception) {
                main.post {
                    if (!isAdded) return@post
                    swipe.isRefreshing = false
                    if (snapshot == null) {
                        empty.text = getString(R.string.goals_load_failed, e.message ?: e.javaClass.simpleName)
                        empty.visibility = View.VISIBLE
                    } else {
                        Toast.makeText(context, getString(R.string.goals_load_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun render(data: GoalsApi.Snapshot) {
        val view = view ?: return
        snapshot = data
        val period = data.periods[14] ?: data.periods.values.firstOrNull()
        view.findViewById<TextView>(R.id.period_label).text = if (period == null) "" else {
            val fmt = DateTimeFormatter.ofPattern("MMM d")
            getString(
                R.string.goals_period_label,
                LocalDate.parse(period.startDate).format(fmt),
                LocalDate.parse(period.endDate).format(fmt),
                period.dayOfPeriod + 1,
                period.days,
            )
        }
        val empty = view.findViewById<TextView>(R.id.empty_text)
        empty.visibility = if (data.goals.isEmpty()) View.VISIBLE else View.GONE
        if (data.goals.isEmpty()) empty.text = getString(R.string.goals_empty)
        adapter.submit(data.goals)
    }

    // --- Actions -----------------------------------------------------------------

    private fun mutate(optimistic: GoalsApi.Goal?, call: () -> GoalsApi.Goal?) {
        val context = requireContext()
        optimistic?.let { g -> snapshot?.let { render(it.withGoal(g)) } }
        io.execute {
            try {
                val result = call()
                main.post { if (isAdded) { if (result != null) snapshot?.let { render(it.withGoal(result)) } else load() } }
            } catch (e: Exception) {
                main.post {
                    if (!isAdded) return@post
                    Toast.makeText(context, getString(R.string.goal_action_failed, e.message), Toast.LENGTH_LONG).show()
                    load()
                }
            }
        }
    }

    private fun logCompletion(goal: GoalsApi.Goal) {
        val optimistic = goal.copy(count = goal.count + 1, status = if (goal.count + 1 < goal.targetByNow) "behind" else "on_track")
        mutate(optimistic) { GoalsApi.logCompletion(requireContext(), goal.id) }
    }

    private fun undo(goal: GoalsApi.Goal) {
        if (goal.count <= 0) return
        val optimistic = goal.copy(count = goal.count - 1, status = if (goal.count - 1 < goal.targetByNow) "behind" else "on_track")
        mutate(optimistic) { GoalsApi.undo(requireContext(), goal.id) }
    }

    private fun showMenu(anchor: View, goal: GoalsApi.Goal) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(0, 1, 0, R.string.menu_edit)
        menu.menu.add(0, 2, 1, R.string.menu_archive)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showGoalDialog(goal)
                2 -> MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.archive_confirm, goal.name))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.menu_archive) { _, _ ->
                        mutate(null) { GoalsApi.archive(requireContext(), goal.id); null }
                    }
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun showGoalDialog(existing: GoalsApi.Goal?) {
        val context = requireContext()
        val form = LayoutInflater.from(context).inflate(R.layout.dialog_goal, null)
        val name = form.findViewById<EditText>(R.id.input_name)
        val emoji = form.findViewById<EditText>(R.id.input_emoji)
        val target = form.findViewById<EditText>(R.id.input_target)
        val grace = form.findViewById<EditText>(R.id.input_grace)
        val periodToggle = form.findViewById<MaterialButtonToggleGroup>(R.id.period_toggle)

        if (existing != null) {
            name.setText(existing.name)
            emoji.setText(existing.emoji ?: "")
            target.setText(formatNumber(existing.target))
            if (existing.hoursOffset > 0) grace.setText(formatNumber(existing.hoursOffset))
            periodToggle.check(if (existing.periodDays == 7) R.id.period_7 else R.id.period_14)
        } else {
            periodToggle.check(R.id.period_14)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.dialog_new_goal else R.string.dialog_edit_goal)
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nameText = name.text.toString().trim()
                val targetValue = target.text.toString().trim().toDoubleOrNull()
                val graceValue = grace.text.toString().trim().toDoubleOrNull() ?: 0.0
                val emojiText = emoji.text.toString().trim().ifEmpty { null }
                val periodDays = if (periodToggle.checkedButtonId == R.id.period_7) 7 else 14
                when {
                    nameText.isEmpty() -> name.error = getString(R.string.error_goal_name)
                    targetValue == null || targetValue <= 0 -> target.error = getString(R.string.error_goal_target)
                    else -> {
                        dialog.dismiss()
                        mutate(null) {
                            if (existing == null) GoalsApi.create(context, nameText, emojiText, targetValue, periodDays, graceValue)
                            else GoalsApi.update(context, existing.id, nameText, emojiText, targetValue, periodDays, graceValue)
                            null
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    // --- Charge slider feedback ----------------------------------------------------

    private fun shake(progress: Float) {
        val root = view ?: return
        val amount = 12f * resources.displayMetrics.density * progress * progress
        root.translationX = (shakeRandom.nextFloat() * 2 - 1) * amount
        root.translationY = (shakeRandom.nextFloat() * 2 - 1) * amount * 0.6f
        root.rotation = (shakeRandom.nextFloat() * 2 - 1) * 0.8f * progress * progress
    }

    private fun stopShake() {
        view?.animate()?.translationX(0f)?.translationY(0f)?.rotation(0f)?.setDuration(160)?.start()
    }

    private fun chargeListener(goal: GoalsApi.Goal, slider: ChargeSliderView) = object : ChargeSliderView.Listener {
        override fun onChargeStart() {
            sound.level = 0f
            sound.start()
            haptics.tick(0.1f)
        }

        override fun onChargeProgress(progress: Float) {
            sound.level = progress
            shake(progress)
        }

        override fun onNotch(index: Int, progress: Float) {
            haptics.tick((index + 1f) / slider.notches.size)
        }

        override fun onChargeComplete() {
            haptics.celebrate()
            sound.chime()
        }

        override fun onChargeRelease(completed: Boolean) {
            sound.stop()
            stopShake()
            // Log on release, so the list isn't re-rendered under a moving finger.
            if (completed) logCompletion(goal)
        }
    }

    // --- List adapter ------------------------------------------------------------

    private inner class GoalAdapter : RecyclerView.Adapter<GoalAdapter.Holder>() {
        private var items: List<GoalsApi.Goal> = emptyList()

        fun submit(goals: List<GoalsApi.Goal>) {
            items = goals
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemId(position: Int) = items[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_goal, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val emoji = view.findViewById<TextView>(R.id.goal_emoji)
            private val name = view.findViewById<TextView>(R.id.goal_name)
            private val count = view.findViewById<TextView>(R.id.goal_count)
            private val bar = view.findViewById<PaceBarView>(R.id.goal_bar)
            private val status = view.findViewById<TextView>(R.id.goal_status)
            private val slider = view.findViewById<ChargeSliderView>(R.id.goal_slider)
            private val undoButton = view.findViewById<Button>(R.id.goal_undo)
            private val menu = view.findViewById<Button>(R.id.goal_menu)

            fun bind(goal: GoalsApi.Goal) {
                emoji.text = goal.emoji ?: "⭐"
                name.text = goal.name
                count.text = getString(R.string.goal_count, goal.count, formatNumber(goal.target))
                bar.set(goal.count, goal.target, goal.targetByNow, goal.isBehind && !goal.complete)
                status.text = when {
                    goal.complete -> getString(R.string.status_complete, goal.daysLeft)
                    goal.isBehind -> getString(R.string.status_behind, goal.targetByNow, goal.daysLeft)
                    else -> getString(R.string.status_on_track, goal.daysLeft)
                }
                undoButton.isEnabled = goal.count > 0
                slider.label = getString(R.string.slide_to_log)
                slider.listener = chargeListener(goal, slider)
                undoButton.setOnClickListener { undo(goal) }
                menu.setOnClickListener { showMenu(it, goal) }
            }
        }
    }
}
