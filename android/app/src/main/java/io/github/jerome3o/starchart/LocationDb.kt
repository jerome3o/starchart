package io.github.jerome3o.starchart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocationDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "locations.db", null, 1) {

    data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fixes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                accuracy REAL NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(fix: Fix) {
        writableDatabase.insert("fixes", null, ContentValues().apply {
            put("time", fix.timeMs)
            put("lat", fix.lat)
            put("lon", fix.lon)
            put("accuracy", fix.accuracyM)
        })
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fixes", null).use {
            it.moveToFirst()
            it.getLong(0)
        }

    fun latest(): Fix? =
        readableDatabase.rawQuery(
            "SELECT time, lat, lon, accuracy FROM fixes ORDER BY time DESC LIMIT 1", null
        ).use {
            if (!it.moveToFirst()) return null
            Fix(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getFloat(3))
        }

    fun writeCsv(appendable: Appendable) {
        appendable.append("time_utc_ms,lat,lon,accuracy_m\n")
        readableDatabase.rawQuery(
            "SELECT time, lat, lon, accuracy FROM fixes ORDER BY time ASC", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                appendable
                    .append(cursor.getLong(0).toString()).append(',')
                    .append(cursor.getDouble(1).toString()).append(',')
                    .append(cursor.getDouble(2).toString()).append(',')
                    .append(cursor.getFloat(3).toString()).append('\n')
            }
        }
    }
}
