package io.github.jerome3o.starchart

import android.content.Context
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/** Goals API client (device-token auth via [Sync]). All calls block; run off the main thread. */
object GoalsApi {

    data class Goal(
        val id: Long,
        val name: String,
        val emoji: String?,
        val target: Double,
        val periodDays: Int,
        val hoursOffset: Double,
        val count: Int,
        val targetByNow: Double,
        val status: String,
        val daysLeft: Int,
        val periodStart: String,
        val periodEnd: String,
        /** What the goal is and why (used by Claude's chat and nudges). */
        val description: String? = null,
    ) {
        val isBehind get() = status == "behind"
        val complete get() = count >= target
    }

    data class Period(val days: Int, val dayOfPeriod: Int, val fraction: Double, val startDate: String, val endDate: String)

    data class Snapshot(val timezone: String, val periods: Map<Int, Period>, val goals: List<Goal>) {
        fun withGoal(updated: Goal) = copy(goals = goals.map { if (it.id == updated.id) updated else it })
    }

    fun parseSnapshot(json: JSONObject): Snapshot {
        val periods = mutableMapOf<Int, Period>()
        val pObj = json.optJSONObject("periods") ?: JSONObject()
        for (key in pObj.keys()) {
            val p = pObj.getJSONObject(key)
            periods[key.toInt()] = Period(
                days = p.getInt("periodDays"),
                dayOfPeriod = p.getInt("dayOfPeriod"),
                fraction = p.getDouble("fraction"),
                startDate = p.getString("startDate"),
                endDate = p.getString("endDate"),
            )
        }
        val arr = json.getJSONArray("goals")
        val goals = (0 until arr.length()).map { parseGoal(arr.getJSONObject(it)) }
        return Snapshot(json.optString("timezone", "UTC"), periods, goals)
    }

    fun parseGoal(g: JSONObject) = Goal(
        id = g.getLong("id"),
        name = g.getString("name"),
        emoji = if (g.isNull("emoji")) null else g.optString("emoji").ifEmpty { null },
        target = g.getDouble("target"),
        periodDays = g.getInt("period_days"),
        hoursOffset = g.optDouble("hours_offset", 0.0),
        count = g.getInt("count"),
        targetByNow = g.getDouble("target_by_now"),
        status = g.getString("status"),
        daysLeft = g.getInt("days_left"),
        periodStart = g.getString("period_start"),
        periodEnd = g.getString("period_end"),
        description = if (g.isNull("description")) null else g.optString("description").ifEmpty { null },
    )

    fun fetch(context: Context): Snapshot {
        val tz = ZoneId.systemDefault().id
        val json = Sync.call(context, "GET", "/api/goals?tz=${android.net.Uri.encode(tz)}")
        val snapshot = parseSnapshot(json)
        saveCache(context, snapshot)
        return snapshot
    }

    /** Persists the snapshot (read by the widget) and refreshes any widgets. */
    fun saveCache(context: Context, snapshot: Snapshot) {
        try {
            Prefs.get(context).edit()
                .putString(Prefs.KEY_GOALS_CACHE, toJson(snapshot).toString())
                .putLong(Prefs.KEY_GOALS_CACHE_TIME, System.currentTimeMillis())
                .commit()
        } catch (_: Exception) {}
        GoalsWidgetProvider.refresh(context)
    }

    private fun cacheGoal(context: Context, goal: Goal): Goal {
        cached(context)?.let { saveCache(context, it.withGoal(goal)) }
        return goal
    }

    private fun toJson(s: Snapshot): JSONObject {
        val periods = JSONObject()
        for ((days, p) in s.periods) {
            periods.put(days.toString(), JSONObject()
                .put("periodDays", p.days).put("dayOfPeriod", p.dayOfPeriod).put("fraction", p.fraction)
                .put("startDate", p.startDate).put("endDate", p.endDate))
        }
        val goals = org.json.JSONArray()
        for (g in s.goals) {
            goals.put(JSONObject()
                .put("id", g.id).put("name", g.name).put("emoji", g.emoji ?: JSONObject.NULL)
                .put("target", g.target).put("period_days", g.periodDays).put("hours_offset", g.hoursOffset)
                .put("count", g.count).put("target_by_now", g.targetByNow).put("status", g.status)
                .put("days_left", g.daysLeft).put("period_start", g.periodStart).put("period_end", g.periodEnd)
                .put("description", g.description ?: JSONObject.NULL))
        }
        return JSONObject().put("timezone", s.timezone).put("periods", periods).put("goals", goals)
    }

    fun cachedAt(context: Context): Long = Prefs.get(context).getLong(Prefs.KEY_GOALS_CACHE_TIME, 0L)

    fun cached(context: Context): Snapshot? =
        Prefs.get(context).getString(Prefs.KEY_GOALS_CACHE, null)?.let {
            try { parseSnapshot(JSONObject(it)) } catch (_: Exception) { null }
        }

    fun create(context: Context, name: String, emoji: String?, target: Double, periodDays: Int, hoursOffset: Double, description: String?): Goal =
        parseGoal(Sync.call(context, "POST", "/api/goals", goalBody(name, emoji, target, periodDays, hoursOffset, description)))

    fun update(context: Context, id: Long, name: String, emoji: String?, target: Double, periodDays: Int, hoursOffset: Double, description: String?): Goal =
        cacheGoal(context, parseGoal(Sync.call(context, "POST", "/api/goals/$id", goalBody(name, emoji, target, periodDays, hoursOffset, description))))

    fun archive(context: Context, id: Long) {
        Sync.call(context, "POST", "/api/goals/$id", JSONObject().put("archived", true))
    }

    /** [clientId] makes retries idempotent; pass the same one for the same tap. */
    fun logCompletion(context: Context, id: Long, clientId: String? = null): Goal =
        cacheGoal(
            context,
            parseGoal(
                Sync.call(
                    context, "POST", "/api/goals/$id/completions",
                    JSONObject().put("client_id", clientId ?: UUID.randomUUID().toString())
                )
            )
        )

    fun undo(context: Context, id: Long): Goal =
        cacheGoal(context, parseGoal(Sync.call(context, "POST", "/api/goals/$id/undo")))

    private fun goalBody(name: String, emoji: String?, target: Double, periodDays: Int, hoursOffset: Double, description: String?) =
        JSONObject()
            .put("description", description ?: JSONObject.NULL)
            .put("name", name)
            .put("emoji", emoji ?: JSONObject.NULL)
            .put("target", target)
            .put("period_days", periodDays)
            .put("hours_offset", hoursOffset)
}
