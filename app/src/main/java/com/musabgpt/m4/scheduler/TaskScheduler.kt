package com.musabgpt.m4.scheduler

import android.content.Context
import androidx.work.*
import com.musabgpt.m4.artifacts.ArtifactRepository
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TaskScheduler {
    fun control(context: Context, owner: String, args: JSONObject): JSONObject {
        val db = TaskDatabase.get(context)
        if (args.getString("action") == "list") return JSONObject().put("status", "observed").put("tasks", org.json.JSONArray().apply { db.list(owner).forEach { put(it.json()) } })
        val task = db.find(args.getString("id"))?.takeIf { it.owner == owner } ?: error("المهمة غير موجودة في هذه المحادثة")
        return when (args.getString("action")) {
            "logs" -> task.json().put("last_run", db.lastRun(task.id))
            "pause" -> { pause(context, task.id); db.find(task.id)!!.json() }
            "clone" -> db.clone(task).json().put("approval_required", true)
            "run", "enable" -> {
                if (!task.enabled) {
                    require(com.musabgpt.m4.runtime.CapabilityBroker.request(owner, "تفعيل نطاق المهمة", task.json())) { "رفض المستخدم نطاق المهمة" }
                    enable(context, task)
                }
                if (args.getString("action") == "run") runNow(context, db.find(task.id)!!)
                db.find(task.id)!!.json().put("queued", true)
            }
            else -> error("إجراء مهمة غير مسجل")
        }
    }
    fun requestApproval(context: Context, args: JSONObject): JSONObject {
        val task = TaskDatabase.get(context).create(args.getString("name"), args.getString("command"),
            args.getString("owner_session_id"), args.getString("type"), args.optLong("delay_minutes", 60), false,
            args.optLong("interval_minutes", 60), args.optJSONObject("trigger") ?: JSONObject(), args.optString("timezone", java.time.ZoneId.systemDefault().id))
        val approved = com.musabgpt.m4.runtime.CapabilityBroker.request(task.owner, "تفعيل مهمة مجدولة", task.json())
        if (approved) enable(context, task)
        return (TaskDatabase.get(context).find(task.id) ?: task).json().put("approval_required", !approved)
    }
    fun enable(context: Context, task: ScheduledTask) {
        val db = TaskDatabase.get(context)
        db.setState(task.id, true, "scheduled")
        if (task.type == "on_app_event") { db.setState(task.id, true, "waiting_event"); return }
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val delay = (task.nextRun - System.currentTimeMillis()).coerceAtLeast(0)
        val input = workDataOf("task_id" to task.id)
        val operation = if (task.type in setOf("once", "daily", "weekly", "monthly")) {
            WorkManager.getInstance(context).enqueueUniqueWork("m4-task-" + task.id, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TaskExecutor>().setInputData(input).setConstraints(constraints)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        } else {
            val interval = if (task.type == "hourly") 60L else task.intervalMinutes
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("m4-task-" + task.id, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TaskExecutor>(interval, TimeUnit.MINUTES).setInputData(input)
                    .setConstraints(constraints).setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        }
        try { operation.result.get(10, TimeUnit.SECONDS) }
        catch (e: Exception) { db.setState(task.id, false, "failed"); throw e }
    }
    fun pause(context: Context, id: String) {
        TaskDatabase.get(context).setState(id, false, "paused")
        WorkManager.getInstance(context).cancelUniqueWork("m4-task-" + id)
        WorkManager.getInstance(context).cancelUniqueWork("m4-task-now-" + id)
    }
    fun delete(context: Context, id: String) {
        pause(context, id)
        TaskDatabase.get(context).delete(id)
    }
    fun appendDaily(context: Context, task: ScheduledTask) {
        if (!task.enabled || task.type !in setOf("daily", "weekly", "monthly")) return
        WorkManager.getInstance(context).enqueueUniqueWork("m4-task-" + task.id, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<TaskExecutor>().setInputData(workDataOf("task_id" to task.id))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay((task.nextRun - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
    }
    fun runNow(context: Context, task: ScheduledTask) {
        require(task.enabled) { "وافق على نطاق المهمة أولاً" }
        WorkManager.getInstance(context).enqueueUniqueWork("m4-task-now-" + task.id, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TaskExecutor>().setInputData(workDataOf("task_id" to task.id,
                "manual_key" to java.util.UUID.randomUUID().toString())).build())
    }
    fun event(context: Context, event: String, eventId: String, owner: String? = null) {
        TaskDatabase.get(context).allEnabled().filter { it.type == "on_app_event" && (owner == null || it.owner == owner) && JSONObject(it.trigger).optString("event") == event }.forEach { task ->
            WorkManager.getInstance(context).enqueueUniqueWork("m4-event-${task.id}-$eventId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TaskExecutor>().setInputData(workDataOf("task_id" to task.id, "manual_key" to "event:$eventId")).build())
        }
    }
}

class TaskExecutor(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val db = TaskDatabase.get(applicationContext)
        val task = db.find(inputData.getString("task_id") ?: return Result.failure()) ?: return Result.failure()
        if (!task.enabled) return Result.success()
        val key = inputData.getString("manual_key") ?: task.nextRun.toString()
        val run = db.claim(task, task.id + ":" + key) ?: return Result.success()
        val result = try {
            val trigger = JSONObject(task.trigger)
            val matches = when (task.type) {
                "condition_watch" -> when (trigger.getString("condition")) {
                    "charging" -> (applicationContext.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager).isCharging
                    "battery_above" -> (applicationContext.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) >= trigger.optInt("percent", 30)
                    "network_connected" -> (applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetwork != null
                    else -> false
                }
                "on_ci_event" -> checkCi(task, trigger)
                else -> true
            }
            if (matches) {
                val receipt = com.musabgpt.m4.terminal.TermuxClient.runApprovedTask(
                    applicationContext, task
                )
                CommandResult(
                    receipt.optString("status", "failed"),
                    if (receipt.has("exit_code") && !receipt.isNull("exit_code"))
                        receipt.getInt("exit_code") else null,
                    receipt.optString("stdout", ""),
                    receipt.optString("stderr", receipt.optString("error", "")),
                    receipt.optBoolean("truncated")
                )
            } else CommandResult("condition_not_met", null, "لم يتحقق الشرط؛ لم يُشغل الأمر", "")
        }
            catch (e: Exception) { CommandResult("failed", null, "", e.message ?: "Task execution failed") }
        db.finish(task, run, result)
        if (inputData.getString("manual_key") == null) db.find(task.id)?.let { TaskScheduler.appendDaily(applicationContext, it) }
        val report = "# ${task.name}\n\nrun_id: $run\nidempotency_key: ${task.id}:$key\nstatus: ${result.status}\nexit_code: ${result.exitCode}\nbackend: termux (RUN_COMMAND)\ntruncated: ${result.truncated}\n\n## stdout\n${result.stdout}\n\n## stderr\n${result.stderr}\n"
        runCatching { ArtifactRepository.saveReport(applicationContext, task.owner, "task-$run.md", report) }
        // The durable run record is authoritative. Do not re-execute side effects
        // merely because a report export failed or a command returned nonzero.
        return Result.success()
    }
    private fun checkCi(task: ScheduledTask, trigger: JSONObject): Boolean {
        val repository = trigger.getString("repository")
            val data = com.musabgpt.m4.github.GitHubClient(applicationContext).call("runs", JSONObject().put("repository", repository), task.owner)
            val runs = data.getJSONObject("data").getJSONArray("workflow_runs")
            if (runs.length() == 0) return false
            val run = runs.getJSONObject(0); val id = run.getLong("id")
            if (run.optString("status") != "completed" || trigger.optLong("last_run", -1) == id) return false
            TaskDatabase.get(applicationContext).updateTrigger(task.id, trigger.put("last_run", id))
            return trigger.optString("conclusion", "any") == "any" || trigger.optString("conclusion") == run.optString("conclusion")
    }
}
