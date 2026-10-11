package com.musabgpt.m4.scheduler

import android.content.Context
import androidx.work.*
import com.musabgpt.m4.artifacts.ArtifactRepository
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TaskScheduler {
    fun requestApproval(context: Context, args: JSONObject): JSONObject {
        val task = TaskDatabase.get(context).create(args.getString("name"), args.getString("command"),
            args.getString("owner_session_id"), args.getString("type"), args.optLong("delay_minutes", 60), false)
        return task.json().put("approval_required", true)
    }
    fun enable(context: Context, task: ScheduledTask) {
        val db = TaskDatabase.get(context)
        db.setState(task.id, true, "scheduled")
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val delay = (task.nextRun - System.currentTimeMillis()).coerceAtLeast(0)
        val input = workDataOf("task_id" to task.id)
        val operation = if (task.type == "once" || task.type == "daily") {
            WorkManager.getInstance(context).enqueueUniqueWork("m4-task-" + task.id, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TaskExecutor>().setInputData(input).setConstraints(constraints)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        } else {
            val interval = if (task.type == "hourly") 1L else 24L
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("m4-task-" + task.id, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TaskExecutor>(interval, TimeUnit.HOURS).setInputData(input)
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
        if (!task.enabled || task.type != "daily") return
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
}

class TaskExecutor(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val db = TaskDatabase.get(applicationContext)
        val task = db.find(inputData.getString("task_id") ?: return Result.failure()) ?: return Result.failure()
        if (!task.enabled) return Result.success()
        val key = inputData.getString("manual_key") ?: task.nextRun.toString()
        val run = db.claim(task, task.id + ":" + key) ?: return Result.success()
        val result = try { AlpineCommandRunner.run(applicationContext, task.command) { isStopped } }
            catch (e: Exception) { CommandResult("failed", null, "", e.message ?: "Task execution failed") }
        db.finish(task, run, result)
        if (inputData.getString("manual_key") == null) db.find(task.id)?.let { TaskScheduler.appendDaily(applicationContext, it) }
        val report = "# ${task.name}\n\nrun_id: $run\nidempotency_key: ${task.id}:$key\nstatus: ${result.status}\nexit_code: ${result.exitCode}\nbackend: alpine (batch)\ntruncated: ${result.truncated}\n\n## stdout\n${result.stdout}\n\n## stderr\n${result.stderr}\n"
        runCatching { ArtifactRepository.saveReport(applicationContext, task.owner, "task-$run.md", report) }
        // The durable run record is authoritative. Do not re-execute side effects
        // merely because a report export failed or a command returned nonzero.
        return Result.success()
    }
}
