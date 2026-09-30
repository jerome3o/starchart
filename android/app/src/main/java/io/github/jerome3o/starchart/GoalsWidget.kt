package io.github.jerome3o.starchart

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.view.View
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget: every goal with its count and a pace bar (now-line,
 * notches, expected-by-now band), plus a "+" per goal that opens the slider.
 * Rows are drawn straight into the widget's layout on every update — no
 * collection adapter, so there is no launcher-side row cache to go stale.
 * Reads the app's cached goal snapshot; re-renders whenever that changes,
 * every 30 minutes via the system timer, and after background fetches.
 */
class GoalsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        schedulePeriodicRefresh(context)
        for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onEnabled(context: Context) {
        schedulePeriodicRefresh(context)
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(networkConstraint()).build()
        )
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH_WORK)
    }

    companion object {
        const val EXTRA_GOAL_ID = "goal_id"
        const val KEY_CLIENT_ID = "client_id"
        private const val REFRESH_WORK = "widget-refresh"

        private fun networkConstraint() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun schedulePeriodicRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                REFRESH_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
            )
        }

        /**
         * Logs a completion tapped on the widget: bumps the cached count so the
         * widget updates instantly, then a worker posts it (idempotent client
         * id, retried until the server has it).
         */
        fun logFromWidget(context: Context, goalId: Long) {
            GoalsApi.cached(context)?.let { snap ->
                snap.goals.find { it.id == goalId }?.let { g ->
                    val bumped = g.copy(
                        count = g.count + 1,
                        status = if (g.count + 1 < g.targetByNow) "behind" else "on_track",
                    )
                    GoalsApi.saveCache(context, snap.withGoal(bumped))
                }
            }
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<WidgetLogWorker>()
                    .setConstraints(networkConstraint())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(workDataOf(EXTRA_GOAL_ID to goalId, KEY_CLIENT_ID to UUID.randomUUID().toString()))
                    .build()
            )
        }

        /** Re-render every placed widget from the cached snapshot. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GoalsWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
        }

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_goals)
            val d = context.resources.displayMetrics.density
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 180

            val snapshot = GoalsApi.cached(context)
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val period = snapshot?.periods?.get(14) ?: snapshot?.periods?.values?.firstOrNull()
            val synced = GoalsApi.cachedAt(context)
            views.setTextViewText(
                R.id.widget_period,
                if (period == null) "" else {
                    val start = LocalDate.parse(period.startDate)
                    val day = (ChronoUnit.DAYS.between(start, LocalDate.now(zone)) + 1).coerceIn(1, period.days.toLong())
                    val syncedText = if (synced > 0) DateTimeFormatter.ofPattern("HH:mm")
                        .format(Instant.ofEpochMilli(synced).atZone(zone)) else "—"
                    context.getString(R.string.widget_period, day.toInt(), period.days, syncedText)
                }
            )

            val goals = snapshot?.goals ?: emptyList()
            views.removeAllViews(R.id.widget_list)
            views.setViewVisibility(R.id.widget_empty, if (goals.isEmpty()) View.VISIBLE else View.GONE)
            views.setTextViewText(
                R.id.widget_empty,
                context.getString(if (Sync.isLinked(context)) R.string.widget_empty else R.string.goals_unlinked)
            )

            // Normal: header + ~50dp rows. If every goal fits once the header
            // goes (with tighter ~42dp rows if needed), drop it; otherwise keep
            // the header and show as many as fit, then "+N more".
            val inner = heightDp - 28
            val compact = goals.size * 50 > inner - 34 && goals.size * 42 <= heightDp - 20
            val noHeader = compact || (goals.size * 50 > inner - 34 && goals.size * 50 <= inner)
            val tight = compact && goals.size * 50 > inner
            val fits = if (noHeader) goals.size else ((inner - 34) / 50).coerceAtLeast(1)
            val shown = if (goals.size > fits) goals.take((fits - 1).coerceAtLeast(1)) else goals
            views.setViewVisibility(R.id.widget_header, if (noHeader) View.GONE else View.VISIBLE)
            val pad = (14 * d).toInt()
            val padV = if (tight) (10 * d).toInt() else pad
            views.setViewPadding(R.id.widget_root, pad, padV, pad, padV)
            val barWidthPx = ((widthDp - 28 - if (tight) 40 else 44) * d).toInt()
            val rowLayout = if (tight) R.layout.widget_goal_item_compact else R.layout.widget_goal_item
            for (g in shown) {
                views.addView(R.id.widget_list, rowViews(context, g, snapshot!!, barWidthPx, now, zone, rowLayout))
            }
            val hidden = goals.size - shown.size
            views.setViewVisibility(R.id.widget_more, if (hidden > 0) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_more, context.getString(R.string.widget_more, hidden))

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_header, open)
            views.setOnClickPendingIntent(R.id.widget_empty, open)
            views.setOnClickPendingIntent(R.id.widget_more, open)
            return views
        }

        private fun rowViews(
            context: Context, g: GoalsApi.Goal, snapshot: GoalsApi.Snapshot,
            barWidthPx: Int, now: Long, zone: ZoneId, layout: Int,
        ): RemoteViews {
            val row = RemoteViews(context.packageName, layout)
            row.setTextViewText(R.id.widget_goal_name, "${g.emoji ?: "⭐"}  ${g.name}")
            val target = if (g.target == Math.floor(g.target)) g.target.toLong().toString() else g.target.toString()
            row.setTextViewText(R.id.widget_goal_count, "${g.count}/$target")

            // "Now" and pace from the period dates and the clock, so they keep
            // moving between syncs; count comes from the last snapshot.
            val start = LocalDate.parse(g.periodStart).atStartOfDay(zone).toInstant().toEpochMilli()
            val length = g.periodDays * 86_400_000.0
            val fraction = ((now - start) / length).coerceIn(0.0, 1.0)
            val elapsedDays = (fraction * g.periodDays - g.hoursOffset / 24).coerceAtLeast(0.0)
            val expected = g.target * elapsedDays / g.periodDays
            val live = g.copy(
                targetByNow = expected,
                status = if (g.count < expected) "behind" else "on_track",
            )
            row.setTextColor(
                R.id.widget_goal_count,
                ContextCompat.getColor(context, if (live.isBehind && !live.complete) R.color.widget_behind else R.color.widget_text)
            )
            row.setImageViewBitmap(R.id.widget_goal_bar, WidgetBarRenderer.render(context, barWidthPx, live, fraction))

            // Row and "+" both open the slider for this goal: completions are
            // only ever logged by sliding. Distinct data URIs keep one
            // PendingIntent per goal.
            val slider = PendingIntent.getActivity(
                context, g.id.toInt(),
                Intent(context, WidgetActionActivity::class.java)
                    .setData(Uri.parse("starchart://widget/goal/${g.id}"))
                    .putExtra(EXTRA_GOAL_ID, g.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            row.setOnClickPendingIntent(R.id.widget_goal_row, slider)
            row.setOnClickPendingIntent(R.id.widget_goal_plus, slider)
            return row
        }
    }
}

/** Background refresh of the goal snapshot the widget shows. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Fetch if we can (which re-renders); either way re-render so the
        // now-line and pace keep moving while offline.
        if (Sync.isLinked(applicationContext) && Sync.hasNetwork(applicationContext)) {
            try { GoalsApi.fetch(applicationContext) } catch (_: Exception) {}
        }
        GoalsWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}

/** Logs a completion tapped on the widget; retries until the server has it. */
class WidgetLogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val goalId = inputData.getLong(GoalsWidgetProvider.EXTRA_GOAL_ID, -1)
        val clientId = inputData.getString(GoalsWidgetProvider.KEY_CLIENT_ID)
        if (goalId < 0 || !Sync.isLinked(applicationContext)) return Result.failure()
        return try {
            GoalsApi.logCompletion(applicationContext, goalId, clientId)
            GoalsApi.fetch(applicationContext) // authoritative counts and pace
            Result.success()
        } catch (_: Sync.UnauthorizedException) {
            Result.failure()
        } catch (_: Exception) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}
