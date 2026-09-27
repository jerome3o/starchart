package io.github.jerome3o.starchart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server pairing state and a tiny HTTP client for the sync API. The device
 * token lives in its own prefs file ("sync") which is excluded from Android
 * backups, so it never leaves this device.
 */
object Sync {

    const val SERVER_URL = "https://starchart.fly.dev"

    private const val PREFS = "sync"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_LAST_SYNCED_ID = "last_synced_id"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_UPLOADED = "uploaded_count"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_EVENT_ID = "last_event_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    fun isLinked(context: Context): Boolean = !token(context).isNullOrEmpty()

    fun storeToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun unlink(context: Context) {
        // Keep last_synced_id: everything up to it is already on the server.
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }

    fun lastSyncedId(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNCED_ID, 0L)

    fun uploadedCount(context: Context): Long = prefs(context).getLong(KEY_UPLOADED, 0L)

    fun lastSyncTime(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)

    fun recordProgress(context: Context, lastSyncedId: Long, uploadedNow: Int) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNCED_ID, lastSyncedId)
            .putLong(KEY_UPLOADED, uploadedCount(context) + uploadedNow)
            .apply()
    }

    fun recordSuccess(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    class UnauthorizedException : IOException("server rejected the device token")

    enum class Outcome { OK, UNAUTHORIZED, FAILED }

    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Uploads every fix not yet on the server, in batches, recording progress
     * after each accepted batch. Safe to call from any background thread;
     * callers serialise it themselves.
     */
    fun uploadPending(context: Context): Outcome {
        val token = token(context) ?: return Outcome.OK
        val db = LocationDb(context)
        return try {
            while (true) {
                val batch = db.fixesAfter(lastSyncedId(context), BATCH_SIZE)
                if (batch.isEmpty()) break
                val accepted = uploadFixes(token, batch)
                recordProgress(context, batch.last().id, accepted)
            }
            uploadEvents(context, token, db)
            recordSuccess(context)
            Outcome.OK
        } catch (e: UnauthorizedException) {
            // Token revoked server-side; unlink so the UI says so.
            unlink(context)
            recordError(context, "server rejected this device's token — unlinked")
            Outcome.UNAUTHORIZED
        } catch (e: Exception) {
            recordError(context, "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            Outcome.FAILED
        }
    }

    private const val BATCH_SIZE = 500

    /** Set when the server says phone commands are queued (see PhoneCommands). */
    @Volatile
    var commandsWaiting = false

    /** Tracking diagnostics, uploaded after the fixes they explain. */
    private fun uploadEvents(context: Context, token: String, db: LocationDb) {
        while (true) {
            val batch = db.eventsAfter(prefs(context).getLong(KEY_LAST_EVENT_ID, 0L), BATCH_SIZE)
            if (batch.isEmpty()) return
            val body = JSONObject().put("events", JSONArray().apply {
                batch.forEach { e ->
                    put(
                        JSONObject()
                            .put("clientId", e.id)
                            .put("time", e.timeMs)
                            .put("kind", e.kind)
                            .put("detail", e.detail ?: JSONObject.NULL)
                    )
                }
            })
            request("POST", "/api/events", token, body)
            prefs(context).edit().putLong(KEY_LAST_EVENT_ID, batch.last().id).apply()
        }
    }

    /** Uploads a batch of fixes; returns the number the server newly accepted. */
    fun uploadFixes(token: String, fixes: List<LocationDb.StoredFix>): Int {
        val body = JSONObject().put("fixes", JSONArray().apply {
            fixes.forEach { fix ->
                put(
                    JSONObject()
                        .put("clientId", fix.id)
                        .put("time", fix.timeMs)
                        .put("lat", fix.lat)
                        .put("lon", fix.lon)
                        .put("accuracy", fix.accuracyM)
                )
            }
        })
        val response = request("POST", "/api/fixes", token, body)
        commandsWaiting = response.optInt("pendingCommands", 0) > 0
        return response.getInt("accepted")
    }

    /** Authenticated JSON call to the server; throws on non-2xx. */
    fun call(context: Context, method: String, path: String, body: JSONObject? = null, readTimeoutMs: Int = 30_000): JSONObject {
        val token = token(context) ?: throw UnauthorizedException()
        return request(method, path, token, body, readTimeoutMs)
    }

    private fun request(method: String, path: String, token: String, body: JSONObject?, readTimeoutMs: Int = 30_000): JSONObject {
        val connection = URL(SERVER_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code == 401) throw UnauthorizedException()
            if (code !in 200..299) {
                val detail = try {
                    connection.errorStream?.use { it.readBytes().decodeToString() }?.take(200)
                } catch (_: IOException) { null }
                throw IOException("HTTP $code from $path${if (detail.isNullOrBlank()) "" else ": $detail"}")
            }
            val text = connection.inputStream.use { it.readBytes().decodeToString() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
