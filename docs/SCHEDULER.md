# Durable task engine

The Tasks panel stores once/hourly/daily jobs, their approved command and owner,
IANA timezone, next execution window, run IDs, outputs and locks in SQLite.
The current project path is `/root/projects/M4`. Start a real agent task first
to seed that checkout. Commands are bounded Alpine/PRoot batch processes with
a two-minute limit, captured stdout/stderr and real exit codes.

Creating a task from the model produces `waiting_permission`, `enabled=false`
and no claimed next run. The user reviews the actual command and recurrence
before activation. Approval waits for WorkManager's persistent enqueue result.
The panel supports activation, pause, run now, history and deletion. Deletion
requires an inactive execution lock. WorkManager handles ordinary boot/process
rescheduling; its standard WAKE_LOCK and RECEIVE_BOOT_COMPLETED permissions are
added by the library manifest. There is no exact-alarm permission or guarantee.

Hourly tasks use PeriodicWorkRequest. Daily tasks chain OneTimeWorkRequests so
their recorded local wall time follows DST in the creation timezone. The first
window is chosen as a delay in minutes; a clock-time input/parser is not yet
provided. Timezone changes do not silently rewrite existing jobs.

SQLite claims the due idempotency key and task lock atomically. Completed keys
cannot execute twice. A stale running lock is recorded as interrupted and
disabled for user review; uncertain side effects are not automatically replayed.
Manual run requests use separate keys. Missed windows skip backlog. Pausing
cancels both normal and manual WorkManager names. Reports are real Markdown
attachments in the owner conversation even when commands fail.

Android may defer work for Doze, battery restrictions, force-stop or resources.
WorkManager jobs are not indefinite interactive PTYs. Network/CI condition
watchers, monthly/weekly rules, exact alarms and autonomous GitHub credentials
are still missing. Device reboot and repeated execution tests are NOT_TESTED.
