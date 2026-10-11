package com.musabgpt.m4.terminal

import android.content.Context
import com.musabgpt.m4.scheduler.AlpineCommandRunner
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.ProjectBootstrap

/**
 * Real, fixed-command runtime checks. No model, fake responses, API keys,
 * network requirement or user-supplied shell strings are involved.
 */
object RuntimeDoctor {
    data class Check(val name: String, val command: String, val expected: Regex)
    data class Result(val ready: Boolean, val report: String)

    private val checks = listOf(
        Check("Python", "python3 --version", Regex("Python\\s+\\d")),
        Check("Node.js", "node --version", Regex("v\\d+")),
        Check("Git", "git --version", Regex("git version\\s+\\d")),
        Check("npm", "npm --version", Regex("\\d+\\.\\d+")),
        Check("Bash", "bash --version", Regex("GNU bash, version\\s+\\d"))
    )

    fun inspect(context: Context, stopped: () -> Boolean): Result {
        if (stopped()) return Result(false, "تم إيقاف التشخيص.")
        try {
            LinuxEnvironment.install(context.applicationContext)
            ProjectBootstrap.ensure(LinuxEnvironment.rootfs(context))
        } catch (failure: Exception) {
            return Result(false, "تعذر تجهيز Linux المدمجة: " + (failure.message ?: "خطأ غير معروف"))
        }
        val lines = mutableListOf("فحص M4 المحلي — Alpine Linux + PRoot")
        var healthy = true
        for (check in checks) {
            if (stopped()) return Result(false, "تم إيقاف التشخيص.")
            val result = AlpineCommandRunner.run(context, check.command, stopped, timeoutSeconds = 12L)
            val output = (result.stdout + "\n" + result.stderr).trim().take(350)
            val success = result.status == "succeeded" && result.exitCode == 0 &&
                check.expected.containsMatchIn(output)
            if (!success) healthy = false
            lines.add(
                (if (success) "✅ " else "❌ ") + check.name + ": " +
                    (if (output.isEmpty()) result.status else output.lineSequence().first().take(180))
            )
        }
        lines.add(if (healthy) "المحرك المحلي يعمل بالأدوات الخمس المختبرة." else "يوجد فشل حقيقي؛ راجع نتائج الأدوات أعلاه.")
        lines.add("ملاحظة: هذه البيئة Alpine مدمجة، وليست حزمة Termux المستقلة.")
        return Result(healthy, lines.joinToString("\n"))
    }
}
