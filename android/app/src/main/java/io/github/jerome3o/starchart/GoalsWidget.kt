package io.github.jerome3o.starchart

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget: every goal with its count and a pace bar (fill = done,
 * lighter band = expected by now), plus a "+" per goal that logs a completion
 * without opening the app. Reads the app's cached goal snapshot, refreshed by
 * the app itself and by a 30-minute background worker.
 */
class GoalsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        schedulePeriodicRefresh(context)
        for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        manager.updateAppWidget(id, buildViews(context, id))
        manager.notifyAppWidgetViewDataChanged(intArrayOf(id), R.id.widget_list)
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
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint())
                    .build()
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
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_goals)

            val snapshot = GoalsApi.cached(context)
            val period = snapshot?.periods?.get(14) ?: snapshot?.periods?.values?.firstOrNull()
            views.setTextViewText(
                R.id.widget_period,
                if (period == null) "" else {
                    val fmt = DateTimeFormatter.ofPattern("MMM d")
                    context.getString(
                        R.string.widget_period,
                        period.dayOfPeriod + 1, period.days,
                        LocalDate.parse(period.endDate).format(fmt),
                    )
                }
            )
            views.setTextViewText(
                R.id.widget_empty,
                context.getString(if (Sync.isLinked(context)) R.string.widget_empty else R.string.goals_unlinked)
            )

            // The list adapter; the data URI makes each widget's intent distinct.
            val serviceIntent = Intent(context, GoalsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // Rows and "+" buttons fill in the goal id and mode on this template;
            // the transparent activity plays the charge/celebration over the home screen.
            val actionTemplate = PendingIntent.getActivity(
                context, 0,
                Intent(context, WidgetActionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.widget_list, actionTemplate)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_header, open)
            views.setOnClickPendingIntent(R.id.widget_empty, open)
            return views
        }
    }
}

class GoalsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
    )

    private class Factory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
        private var goals: List<GoalsApi.Goal> = emptyList()
        private var fractions: Map<Int, Double> = emptyMap()
        private var barWidthPx = 0

        override fun onCreate() = Unit
        override fun onDestroy() = Unit

        override fun onDataSetChanged() {
            val snap = GoalsApi.cached(context)
            goals = snap?.goals ?: emptyList()
            fractions = snap?.periods?.mapValues { it.value.fraction } ?: emptyMap()
            barWidthPx = barWidth()
        }

        /** Bar width from the widget's current size: minus padding and the + button. */
        private fun barWidth(): Int {
            val d = context.resources.displayMetrics.density
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
            return ((widthDp - 28 - 44) * d).toInt()
        }

        override fun getCount() = goals.size
        override fun getItemId(position: Int) = goals[position].id
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 1
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val g = goals[position]
            val views = RemoteViews(context.packageName, R.layout.widget_goal_item)
            views.setTextViewText(R.id.widget_goal_name, "${g.emoji ?: "⭐"}  ${g.name}")
            val target = if (g.target == Math.floor(g.target)) g.target.toLong().toString() else g.target.toString()
            views.setTextViewText(R.id.widget_goal_count, "${g.count}/$target")
            val behind = g.isBehind && !g.complete
            views.setTextColor(
                R.id.widget_goal_count,
                ContextCompat.getColor(context, if (behind) R.color.widget_behind else R.color.widget_text)
            )

            val fraction = fractions[g.periodDays] ?: 0.0
            views.setImageViewBitmap(R.id.widget_goal_bar, WidgetBarRenderer.render(context, barWidthPx, g, fraction))

            // Both open the slider card: completions are only ever logged by sliding.
            val openSlider = Intent().putExtra(GoalsWidgetProvider.EXTRA_GOAL_ID, g.id)
            views.setOnClickFillInIntent(R.id.widget_goal_plus, openSlider)
            views.setOnClickFillInIntent(R.id.widget_goal_row, openSlider)
            return views
        }
    }
}

/** Background refresh of the goal snapshot the widget shows. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!Sync.isLinked(applicationContext)) return Result.success()
        return try {
            GoalsApi.fetch(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
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
