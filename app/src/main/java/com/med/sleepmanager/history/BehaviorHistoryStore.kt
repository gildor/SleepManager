package com.med.sleepmanager.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.time.ZoneId

/**
 * Lightweight append-only journal, independent of diagnostic logging and battery averages.
 * Call off the main thread. The recorder serializes writes; chart readers can use WAL reads.
 */
object BehaviorHistoryStore {
    @Volatile private var database: BehaviorHistoryDatabase? = null

    private fun database(context: Context): BehaviorHistoryDatabase =
        database ?: synchronized(this) {
            database ?: BehaviorHistoryDatabase(context.applicationContext).also { database = it }
        }

    internal fun append(context: Context, observation: BehaviorObservation) {
        database(context).append(observation)
    }

    fun observations(context: Context): List<BehaviorObservation> = database(context).read()

    fun clear(context: Context) = database(context).clearHistory()
}

internal class BehaviorHistoryDatabase(
    context: Context,
    name: String = "behavior_history.db"
) : SQLiteOpenHelper(context, name, null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "run_id TEXT NOT NULL, wall_time INTEGER NOT NULL, " +
                "elapsed_time INTEGER NOT NULL, payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun append(
        observation: BehaviorObservation,
        nowWallTimeMs: Long = observation.wallTimeMs,
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        val db = writableDatabase
        db.transaction {
            db.insertOrThrow("observations", null, ContentValues().apply {
                put("run_id", observation.runId)
                put("wall_time", observation.wallTimeMs)
                put("elapsed_time", observation.elapsedTimeMs)
                put("payload", BehaviorHistoryCodec.encode(observation))
            })
            val cutoff = BehaviorHistoryRetention.cutoff(nowWallTimeMs, zone)
            // Retain one predecessor so a session crossing the oldest day has its boundary.
            db.execSQL(
                "DELETE FROM observations WHERE id < (" +
                    "SELECT MAX(id) FROM observations WHERE id < (" +
                    "SELECT MIN(id) FROM observations WHERE wall_time >= ?))",
                arrayOf(cutoff)
            )
            db.execSQL(
                "DELETE FROM observations WHERE id <= (" +
                    "SELECT id FROM observations ORDER BY id DESC LIMIT 1 OFFSET ?)",
                arrayOf(BehaviorHistoryRetention.MAX_OBSERVATIONS)
            )
        }
    }

    fun read(): List<BehaviorObservation> = readableDatabase.query(
        "observations", null, null, null, null, null, "id ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val sequence = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val runId = cursor.getString(cursor.getColumnIndexOrThrow("run_id"))
                val wall = cursor.getLong(cursor.getColumnIndexOrThrow("wall_time"))
                val elapsed = cursor.getLong(cursor.getColumnIndexOrThrow("elapsed_time"))
                val payload = cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                add(runCatching {
                    BehaviorHistoryCodec.decode(payload, sequence, runId, wall, elapsed)
                }.getOrElse {
                    // Skipping corruption would silently bridge an unobserved state change.
                    BehaviorObservation(
                        sequence = sequence,
                        runId = runId,
                        wallTimeMs = wall,
                        elapsedTimeMs = elapsed,
                        event = BehaviorEvent.GAP,
                        state = BehaviorState.STANDBY
                    )
                })
            }
        }
    }

    fun clearHistory() {
        writableDatabase.delete("observations", null, null)
    }
}
