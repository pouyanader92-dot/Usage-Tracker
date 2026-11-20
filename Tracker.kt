package com.example.usagetracker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

class UsageDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun insert(start: Long, end: Long) {
        if (end <= start) return
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("start_ms", start)
            put("end_ms", end)
        })
    }

    fun since(ms: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT start_ms, end_ms FROM sessions WHERE end_ms > ?", arrayOf(ms.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1)) }
        return out
    }
}

object Tracker {
    @Volatile private var dbInstance: UsageDb? = null

    private fun db(c: Context): UsageDb =
        dbInstance ?: synchronized(this) {
            dbInstance ?: UsageDb(c.applicationContext).also { dbInstance = it }
        }

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    fun openStart(c: Context): Long = sp(c).getLong("open_start", 0L)
    private fun lastSeen(c: Context): Long = sp(c).getLong("last_seen", 0L)

    /** سرویس پس‌زمینه را روشن نگه می‌دارد (چند بار صدا زدنش مشکلی ندارد). */
    fun ensureRunning(c: Context) {
        runCatching {
            val app = c.applicationContext
            app.startForegroundService(Intent(app, TrackerService::class.java))
        }
    }

    /** شروع جلسه: وقتی قفل گوشی باز شد. */
    @Synchronized
    fun begin(c: Context) {
        if (openStart(c) == 0L) {
            val t = System.currentTimeMillis()
            sp(c).edit().putLong("open_start", t).putLong("last_seen", t).apply()
        }
    }

    /** هر ۱۵ ثانیه صدا زده می‌شود تا اگر سرویس یا گوشی ناگهانی بسته شد، زمان اضافه ثبت نشود. */
    @Synchronized
    fun beat(c: Context) {
        if (openStart(c) > 0L) {
            sp(c).edit().putLong("last_seen", System.currentTimeMillis()).apply()
        }
    }

    @Synchronized
    fun endAt(c: Context, at: Long) {
        val s = openStart(c)
        if (s > 0L) {
            if (at > s) db(c).insert(s, at)
            sp(c).edit().putLong("open_start", 0L).apply()
        }
    }

    /** پایان جلسه: وقتی صفحه خاموش شد. */
    fun end(c: Context) = endAt(c, System.currentTimeMillis())

    /** اگر جلسه‌ای باز مانده بود (سرویس مرده یا گوشی ریستارت شده)، تا آخرین زمان معتبر می‌بندد. */
    fun recover(c: Context) {
        val s = openStart(c)
        if (s > 0L) endAt(c, maxOf(s, lastSeen(c)))
    }

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    /** جمع استفاده‌ی n روز اخیر؛ اولین عنصر امروز است. */
    fun dailyTotals(c: Context, days: Int = 7): List<Pair<Long, Long>> {
        val now = System.currentTimeMillis()
        val starts = (0 until days).map { startOfDay(it) }
        val sessions = db(c).since(starts.last()).toMutableList()
        val open = openStart(c)
        if (open > 0L) sessions.add(open to now)
        return starts.mapIndexed { i, dayStart ->
            val dayEnd = if (i == 0) Long.MAX_VALUE else starts[i - 1]
            val total = sessions.sumOf { (s, e) ->
                val a = maxOf(s, dayStart)
                val b = minOf(e, dayEnd)
                if (b > a) b - a else 0L
            }
            dayStart to total
        }
    }

    /** جلسه‌های امروز (شامل جلسه‌ی در حال انجام). */
    fun todaySessions(c: Context): List<Pair<Long, Long>> {
        val dayStart = startOfDay(0)
        val list = db(c).since(dayStart).map { (s, e) -> maxOf(s, dayStart) to e }.toMutableList()
        val open = openStart(c)
        if (open > 0L) list.add(maxOf(open, dayStart) to System.currentTimeMillis())
        return list.sortedBy { it.first }
    }
}
