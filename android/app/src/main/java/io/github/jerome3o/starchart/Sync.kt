package io.github.jerome3o.starchart

import android.content.Context
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
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    class UnauthorizedException : IOException("server rejected the device token")

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
        return response.getInt("accepted")
    }

    private fun request(method: String, path: String, token: String, body: JSONObject?): JSONObject {
        val connection = URL(SERVER_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code == 401) throw UnauthorizedException()
            if (code !in 200..299) throw IOException("HTTP $code from $path")
            val text = connection.inputStream.use { it.readBytes().decodeToString() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
