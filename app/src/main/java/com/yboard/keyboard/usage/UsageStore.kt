package com.yboard.keyboard.usage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yboard.keyboard.settings.AiFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

enum class UsagePeriod(val label: String, val days: Long) {
    TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30);
    fun start(now: Instant, zone: ZoneId): Long = now.atZone(zone).toLocalDate().minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
}

data class UsageTotals(
    val input: Long = 0, val output: Long = 0, val cached: Long = 0, val writes: Long = 0,
    val seconds: Double = 0.0, val requests: Long = 0, val incomplete: Long = 0,
    val missingUsage: Long = 0, val estimatedSpeech: Long = 0,
    val inputCost: Long = 0, val outputCost: Long = 0, val cachedCost: Long = 0, val writeCost: Long = 0, val speechCost: Long = 0,
) {
    val totalCost get() = inputCost + outputCost + cachedCost + writeCost + speechCost
    val ordinaryInput get() = input - cached - writes
}
data class ModelUsage(val model: String, val totals: UsageTotals)
data class UsageOverview(val since: Long, val totals: UsageTotals, val models: List<ModelUsage>, val error: String? = null)

/** Private accounting data only, excluded from Android backups. Writes never block keyboard input. */
object UsageStore {
    private val worker = Executors.newSingleThreadExecutor()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Volatile private var helper: UsageDatabase? = null
    @Volatile private var failure = false

    @Synchronized fun init(context: Context) {
        if (helper != null) return
        val db = UsageDatabase(context.applicationContext)
        helper = db
        worker.execute {
            runCatching { db.writableDatabase }.onSuccess { failure = false }.onFailure { failure = true }
            revision.value++
        }
    }

    fun record(feature: AiFeature, model: String, usage: TokenUsage, serviceTier: String = "default") {
        val db = helper ?: return
        val time = System.currentTimeMillis()
        val charge = UsagePricing.estimate(model, usage, serviceTier)
        worker.execute {
            runCatching { db.add(time, feature.name, model, usage, charge) }.onSuccess { failure = false }.onFailure { failure = true }
            revision.value++
        }
    }

    fun overview(period: UsagePeriod, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): UsageOverview {
        if (failure) return UsageOverview(0, UsageTotals(), emptyList(), "Usage could not be saved or read on this device.")
        return runCatching { helper?.overview(period.start(now, zone), now.toEpochMilli())
            ?: UsageOverview(0, UsageTotals(), emptyList()) }
            .getOrElse { UsageOverview(0, UsageTotals(), emptyList(), "Usage could not be read on this device.") }
    }
}

internal class UsageDatabase(context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "yboard_ai_usage.db").absolutePath, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO meta VALUES ('since', ${System.currentTimeMillis()})")
        db.execSQL("""CREATE TABLE usage (at INTEGER NOT NULL, feature TEXT NOT NULL, model TEXT NOT NULL,
            input INTEGER NOT NULL, output INTEGER NOT NULL, cached INTEGER NOT NULL, writes INTEGER NOT NULL,
            seconds REAL NOT NULL, incomplete INTEGER NOT NULL, missing INTEGER NOT NULL, estimated INTEGER NOT NULL,
            input_cost INTEGER NOT NULL, output_cost INTEGER NOT NULL, cached_cost INTEGER NOT NULL,
            write_cost INTEGER NOT NULL, speech_cost INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX usage_at ON usage(at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private var lastPrunedDay = Long.MIN_VALUE
    fun add(at: Long, feature: String, model: String, u: TokenUsage, c: UsageCharge) {
        val db = writableDatabase
        db.insertOrThrow("usage", null, ContentValues().apply {
            put("at", at); put("feature", feature); put("model", model)
            put("input", u.input); put("output", u.output); put("cached", u.cached); put("writes", u.writes)
            put("seconds", u.seconds); put("incomplete", if (c.incomplete) 1 else 0)
            put("missing", if (!u.reported && u.seconds == 0.0) 1 else 0); put("estimated", if (u.estimatedSeconds) 1 else 0)
            put("input_cost", c.input); put("output_cost", c.output); put("cached_cost", c.cached)
            put("write_cost", c.writes); put("speech_cost", c.speech)
        })
        if (at / 86_400_000 != lastPrunedDay) {
            db.delete("usage", "at < ?", arrayOf((at - 90L * 86_400_000).toString()))
            lastPrunedDay = at / 86_400_000
        }
    }
    fun overview(start: Long, now: Long): UsageOverview {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
        val since = db.rawQuery("SELECT value FROM meta WHERE name='since'", null).use { it.moveToFirst(); it.getLong(0) }
        fun query(grouped: Boolean): List<ModelUsage> {
            val rows = mutableListOf<ModelUsage>()
            val cols = "COALESCE(SUM(input),0),COALESCE(SUM(output),0),COALESCE(SUM(cached),0),COALESCE(SUM(writes),0),COALESCE(SUM(seconds),0),COUNT(*),COALESCE(SUM(incomplete),0),COALESCE(SUM(missing),0),COALESCE(SUM(estimated),0),COALESCE(SUM(input_cost),0),COALESCE(SUM(output_cost),0),COALESCE(SUM(cached_cost),0),COALESCE(SUM(write_cost),0),COALESCE(SUM(speech_cost),0)"
            db.rawQuery("SELECT ${if (grouped) "model" else "''"},$cols FROM usage WHERE at>=? AND at<=? ${if (grouped) "GROUP BY model" else ""}", arrayOf(start.toString(), now.toString())).use { cur ->
                while (cur.moveToNext()) rows.add(ModelUsage(cur.getString(0), UsageTotals(cur.getLong(1), cur.getLong(2), cur.getLong(3), cur.getLong(4),
                    cur.getDouble(5), cur.getLong(6), cur.getLong(7), cur.getLong(8), cur.getLong(9), cur.getLong(10), cur.getLong(11), cur.getLong(12), cur.getLong(13), cur.getLong(14))))
            }
            return rows
        }
        val result = UsageOverview(since, query(false).first().totals, query(true).sortedByDescending { it.totals.totalCost })
        db.setTransactionSuccessful()
        return result
        } finally { db.endTransaction() }
    }
}
