package com.musabgpt.m4

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Only reads the app-private snapshot. It never runs commands or changes agent state. */
data class AgentProgress(
    val goal: String,
    val phase: String,
    val steps: Int,
    val summary: String
) {
    val canResume: Boolean
        get() = phase == "paused-step-limit" && goal.isNotBlank()

    val displayPhase: String get() = when (phase) {
        "running" -> "قيد التنفيذ"
        "complete" -> "مكتملة بعد الاختبارات"
        "paused-step-limit" -> "متوقفة مؤقتاً عند حد الخطوات"
        "blocked" -> "متوقفة بسبب خطأ"
        "needs-checks" -> "تحتاج اختبارات للتحقق"
        "stuck" -> "الوكيل عالق"
        else -> "الحالة: $phase"
    }
}

object AgentProgressStore {
    fun read(context: Context): AgentProgress? {
        // App-private filesDir; an external caller cannot select an arbitrary path.
        val state = File(context.filesDir,
            "alpine-rootfs/root/projects/M4/.term-agent/state.json")
        return try {
            if (!state.isFile || state.length() !in 1L..32768L) return null
            val obj = JSONObject(state.readText(Charsets.UTF_8))
            AgentProgress(
                goal = obj.optString("goal", "").take(6000),
                phase = obj.optString("phase", "unknown").take(80),
                steps = obj.optInt("step", 0).coerceAtLeast(0),
                summary = obj.optString("summary", "").take(3000)
            )
        } catch (_: Exception) {
            null
        }
    }
}
