package io.github.jerome3o.starchart

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Functions on-device agents (Gemini on Android 16+) can call. The KSP
 * compiler generates the concrete StarchartAppFunctionService declared in the
 * manifest, plus the XML describing these functions from their KDoc.
 *
 * Logging a goal deliberately returns the slider card instead of logging:
 * finishing the slide is the only way to check a goal off.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "StarchartAppFunctionService",
    appFunctionXmlFileName = "starchart_app_function_service",
)
abstract class BaseStarchartAppFunctionService : AppFunctionService() {

    /**
     * Lists the user's Starchart habit goals and how they're pacing in the
     * current period. Call this to answer questions like "how am I going on my
     * goals?" or to find a goal's exact name.
     *
     * @return Every active goal with its count, target and pace status.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getGoals(): List<GoalProgress> = snapshot().goals.map { it.toProgress() }

    /**
     * Opens the charge slider for one goal so the user can log a completion
     * by sliding it to the end. Goals can only be checked off this way, so
     * use this whenever the user says they did a goal (e.g. "log gym",
     * "I went for a run").
     *
     * @param goalName The goal's name, as returned by getGoals. Matching is case-insensitive and partial matches work.
     * @return An intent that shows the slider card over the current screen.
     * @throws AppFunctionElementNotFoundException If no goal matches goalName. If thrown, call getGoals and ask the user which one they meant.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun openGoalSlider(goalName: String): PendingIntent {
        val goal = findGoal(snapshot().goals, goalName)
            ?: throw AppFunctionElementNotFoundException("No goal called \"$goalName\"")
        val intent = Intent(this, WidgetActionActivity::class.java)
            .putExtra(GoalsWidgetProvider.EXTRA_GOAL_ID, goal.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            this, goal.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Fresh from the server when possible (also refreshes the cache the slider reads). */
    private suspend fun snapshot(): GoalsApi.Snapshot = withContext(Dispatchers.IO) {
        try {
            GoalsApi.fetch(this@BaseStarchartAppFunctionService)
        } catch (e: Exception) {
            GoalsApi.cached(this@BaseStarchartAppFunctionService)
                ?: throw AppFunctionElementNotFoundException("Starchart has no goals yet (is the app linked to the server?)")
        }
    }

    private fun findGoal(goals: List<GoalsApi.Goal>, query: String): GoalsApi.Goal? {
        val q = query.trim().lowercase()
        return goals.firstOrNull { it.name.lowercase() == q }
            ?: goals.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
    }

    private fun GoalsApi.Goal.toProgress() = GoalProgress(
        name = name,
        emoji = emoji ?: "",
        count = count,
        target = target,
        targetByNow = targetByNow,
        status = status,
        periodDays = periodDays,
        daysLeft = daysLeft,
    )
}

/** One goal's progress in its current period. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class GoalProgress(
    /** The goal's name. */
    val name: String,
    /** The goal's emoji, or empty. */
    val emoji: String,
    /** Completions logged so far this period. */
    val count: Int,
    /** Completions wanted by the end of the period. */
    val target: Double,
    /** Completions needed by now to be on pace. */
    val targetByNow: Double,
    /** Pace status reported by the server, "on_track" or "behind". */
    val status: String,
    /** Length of the goal's period in days (7 or 14). */
    val periodDays: Int,
    /** Whole days left in the current period. */
    val daysLeft: Int,
)
