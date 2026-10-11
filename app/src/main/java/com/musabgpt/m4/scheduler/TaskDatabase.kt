package com.musabgpt.m4.scheduler

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

data class ScheduledTask(val id: String, val name: String, val owner: String, val command: String,
    val type: String, val enabled: Boolean, val state: String, val nextRun: Long, val timezone: String,
    val intervalMinutes: Long = 60, val trigger: String = "{}") {
    fun json() = JSONObject().put("task_id", id).put("name", name).put("owner_session_id", owner)
        .put("command", command).put("type", type).put("enabled", enabled).put("status", state)
        .put("interval_minutes", intervalMinutes).put("trigger", JSONObject(trigger))
        .put("next_run_window", if (enabled && type != "on_app_event") JSONObject().put("not_before_utc_ms", nextRun)
            .put("precision", "android_workmanager_window") else JSONObject.NULL).put("timezone", timezone)
}

class TaskDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "m4_tasks.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scheduled_tasks(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner TEXT NOT NULL,command TEXT NOT NULL,type TEXT NOT NULL,enabled INTEGER NOT NULL,state TEXT NOT NULL,next_run INTEGER NOT NULL,timezone TEXT NOT NULL,interval_minutes INTEGER NOT NULL DEFAULT 60,trigger TEXT NOT NULL DEFAULT '{}')")
        db.execSQL("CREATE TABLE task_runs(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES scheduled_tasks(id),idempotency_key TEXT UNIQUE NOT NULL,status TEXT NOT NULL,started INTEGER NOT NULL,ended INTEGER,exit_code INTEGER,stdout TEXT NOT NULL DEFAULT '',stderr TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE execution_locks(task_id TEXT PRIMARY KEY REFERENCES scheduled_tasks(id),run_id TEXT NOT NULL,started INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN interval_minutes INTEGER NOT NULL DEFAULT 60")
            db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN trigger TEXT NOT NULL DEFAULT '{}'")
        } else error("Unrecognized task schema migration")
    }

    private fun row(cursor: Cursor) = ScheduledTask(cursor.getString(0), cursor.getString(1), cursor.getString(2),
        cursor.getString(3), cursor.getString(4), cursor.getInt(5) != 0, cursor.getString(6), cursor.getLong(7), cursor.getString(8), cursor.getLong(9), cursor.getString(10))
    fun find(id: String): ScheduledTask? = readableDatabase.rawQuery("SELECT * FROM scheduled_tasks WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) row(it) else null }
    fun list(owner: String): List<ScheduledTask> = readableDatabase.rawQuery("SELECT * FROM scheduled_tasks WHERE owner=? ORDER BY next_run", arrayOf(owner)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(row(cursor)) }
    }

    fun create(name: String, command: String, owner: String, type: String, delayMinutes: Long, approved: Boolean,
        intervalMinutes: Long = 60, trigger: JSONObject = JSONObject(), timezone: String = ZoneId.systemDefault().id): ScheduledTask {
        require(name.isNotBlank() && name.length <= 100 && command.isNotBlank() && command.length <= 6000)
        require(owner.matches(Regex("[A-Za-z0-9-]{1,80}")) && type in setOf("once", "hourly", "daily", "weekly", "monthly", "interval", "condition_watch", "on_app_event", "on_ci_event"))
        require(intervalMinutes in 15..525600 && trigger.toString().length <= 10000)
        ZoneId.of(timezone)
        if (type == "on_app_event") require(trigger.optString("event") in setOf("app_started", "chat_finished", "artifact_created"))
        if (type == "condition_watch") require(trigger.optString("condition") in setOf("charging", "battery_above", "network_connected"))
        if (type == "on_ci_event") require(trigger.optString("repository").matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(delayMinutes in 1..525600 && list(owner).size < 30)
        if (type == "monthly" && !trigger.has("month_day")) trigger.put("month_day", java.time.Instant.ofEpochMilli(System.currentTimeMillis() + delayMinutes * 60000).atZone(ZoneId.of(timezone)).dayOfMonth)
        val task = ScheduledTask(UUID.randomUUID().toString(), name, owner, command, type, approved,
            if (approved) "scheduled" else "waiting_permission", System.currentTimeMillis() + delayMinutes * 60000, timezone, intervalMinutes, trigger.toString())
        writableDatabase.insertOrThrow("scheduled_tasks", null, ContentValues().apply {
            put("id", task.id); put("name", name); put("owner", owner); put("command", command)
            put("type", type); put("enabled", if (approved) 1 else 0); put("state", task.state)
            put("next_run", task.nextRun); put("timezone", task.timezone)
            put("interval_minutes", intervalMinutes); put("trigger", task.trigger)
        })
        return task
    }
    fun allEnabled(): List<ScheduledTask> = readableDatabase.rawQuery("SELECT * FROM scheduled_tasks WHERE enabled=1", null).use { cursor -> buildList { while (cursor.moveToNext()) add(row(cursor)) } }
    fun clone(task: ScheduledTask): ScheduledTask = create(task.name + " (نسخة)", task.command, task.owner, task.type, 1, false, task.intervalMinutes, JSONObject(task.trigger), task.timezone)
    fun updateTrigger(id: String, trigger: JSONObject) { writableDatabase.update("scheduled_tasks", ContentValues().apply { put("trigger", trigger.toString()) }, "id=?", arrayOf(id)) }
    fun setState(id: String, enabled: Boolean, state: String) {
        writableDatabase.update("scheduled_tasks", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("state", state) }, "id=?", arrayOf(id))
    }

    /** SQLite transaction + unique due key prevent duplicate work across restarts. */
    fun claim(task: ScheduledTask, key: String): String? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (find(task.id)?.enabled != true) return null
            val now = System.currentTimeMillis()
            db.rawQuery("SELECT run_id,started FROM execution_locks WHERE task_id=?", arrayOf(task.id)).use { lock ->
                if (lock.moveToFirst()) {
                    if (now - lock.getLong(1) < 180000) return null
                    db.execSQL("UPDATE task_runs SET status='interrupted',ended=? WHERE id=?", arrayOf(now, lock.getString(0)))
                    db.delete("execution_locks", "task_id=?", arrayOf(task.id))
                    setState(task.id, false, "interrupted")
                    db.setTransactionSuccessful()
                    return null
                }
            }
            db.rawQuery("SELECT id FROM task_runs WHERE idempotency_key=?", arrayOf(key)).use { if (it.moveToFirst()) return null }
            val run = UUID.randomUUID().toString()
            db.insertOrThrow("task_runs", null, ContentValues().apply {
                put("id", run); put("task_id", task.id); put("idempotency_key", key); put("status", "running"); put("started", now)
            })
            db.insertOrThrow("execution_locks", null, ContentValues().apply { put("task_id", task.id); put("run_id", run); put("started", now) })
            setState(task.id, true, "running")
            db.setTransactionSuccessful()
            return run
        } finally { db.endTransaction() }
    }

    fun finish(task: ScheduledTask, run: String, result: CommandResult) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("task_runs", ContentValues().apply {
                put("status", result.status); put("ended", System.currentTimeMillis())
                if (result.exitCode != null) put("exit_code", result.exitCode) else putNull("exit_code")
                put("stdout", result.stdout.take(32000)); put("stderr", result.stderr.take(32000))
            }, "id=?", arrayOf(run))
            db.delete("execution_locks", "task_id=? AND run_id=?", arrayOf(task.id, run))
            val enabled = find(task.id)?.enabled == true
            if (task.type == "once") setState(task.id, false, result.status)
            else if (task.type == "on_app_event") setState(task.id, enabled, if (enabled) "waiting_event" else "paused")
            else {
                val next = TaskTiming.next(task.type, task.nextRun, System.currentTimeMillis(), task.timezone, task.intervalMinutes, JSONObject(task.trigger).optInt("month_day", 0))
                db.update("scheduled_tasks", ContentValues().apply { put("next_run", next)
                    put("state", if (enabled) "scheduled" else "paused") }, "id=?", arrayOf(task.id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun lastRun(id: String): String = readableDatabase.rawQuery("SELECT status,started,ended,exit_code,stdout,stderr FROM task_runs WHERE task_id=? ORDER BY started DESC LIMIT 1", arrayOf(id)).use {
        if (!it.moveToFirst()) "لم تُنفذ المهمة بعد" else "${it.getString(0)} • exit_code=${if (it.isNull(3)) "غير متاح" else it.getInt(3)}\n${it.getString(4)}\n${it.getString(5)}"
    }
    fun delete(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT task_id FROM execution_locks WHERE task_id=?", arrayOf(id)).use { require(!it.moveToFirst()) { "انتظر انتهاء التشغيل قبل الحذف" } }
            db.delete("task_runs", "task_id=?", arrayOf(id))
            db.delete("scheduled_tasks", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    companion object {
        @Volatile private var instance: TaskDatabase? = null
        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: TaskDatabase(context.applicationContext).also { instance = it }
        }
    }
}
