package com.musabgpt.m4

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.musab.arabicterminal.ArabicCommandRouter
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.MainActivity
import io.musab.arabicterminal.PhoneController
import io.musab.arabicterminal.TerminalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Bridge from the CHAT MODEL to the SAME native phone and Alpine tool engines
 * that the Arabic Terminal already uses. Never a fake console or Termux API.
 */
class M4NativeTools(private val app: Application) {
    companion object {
        private val terminalOnlySuffix = Regex(
            """\s+(?:باستخدام|عبر|عن طريق|من خلال|بواسطة)\s+(?:الطرفية|الترمينال|طرفيتك|terminal)\s*[.!؟]*$""",
            RegexOption.IGNORE_CASE
        )

        fun quickAction(userText: String): String? {
            val cleaned = userText.trim().replace(terminalOnlySuffix, "").trim()
            val normalized = ArabicCommandRouter.normalize(cleaned)
            if (normalized in listOf("اربط github", "ربط github", "اتصل ب github",
                    "وصل github", "ربط جيت هاب", "اربط جيت هاب",
                    "connect github", "github login")) return "github_connect"
            val parsed = ArabicCommandRouter.parse(cleaned)
            return when (parsed.kind) {
                ArabicCommandRouter.Kind.OPEN_APP,
                ArabicCommandRouter.Kind.OPEN_SYSTEM_APP,
                ArabicCommandRouter.Kind.LIST_APPS,
                ArabicCommandRouter.Kind.OPEN_SETTINGS_PAGE,
                ArabicCommandRouter.Kind.SETTINGS -> "native:" + cleaned
                else -> null
            }
        }
    }

    suspend fun performQuick(action: String): String = withContext(Dispatchers.Main) {
        if (action == "github_connect") return@withContext githubConnect()
        if (!action.startsWith("native:")) return@withContext "أمر غير مدعوم."
        PhoneController.run(app, ArabicCommandRouter.parse(action.removePrefix("native:")))
    }

    suspend fun execute(name: String, args: JSONObject, userRequest: String,
                        approvedShell: Boolean): String {
        return when (name) {
            "open_app" -> {
                val target=args.optString("name","").trim().take(100)
                if (target.isEmpty()) "اسم التطبيق غير موجود."
                else if (!explicitOpenIntent(userRequest))
                    "لا يمكن فتح التطبيقات دون طلب واضح من المستخدم."
                else withContext(Dispatchers.Main) {
                    PhoneController.run(app,ArabicCommandRouter.parse("افتح تطبيق " + target))
                }
            }
            "list_apps" -> withContext(Dispatchers.Main) {
                PhoneController.run(app,ArabicCommandRouter.parse("اعرض التطبيقات")).take(5000)
            }
            "github_connect" -> {
                if (!explicitGithubIntent(userRequest)) "اطلب ربط GitHub بوضوح أولاً."
                else withContext(Dispatchers.Main) { githubConnect() }
            }
            "github_status" -> executeTerminal("gh auth status", 25)
            "terminal_command" -> {
                val command=args.optString("command","").trim()
                if (!approvedShell) "رفض المستخدم الإذن بتنفيذ الأمر."
                else if (command.isEmpty() || command.length>2000 || command.contains('\u0000'))
                    "الأمر فارغ أو أطول من المسموح."
                else if (Regex("""(?i)(gh\\s+auth\\s+token|cat\\s+.*(\\.ssh|hosts\\.yml|\\.env)|printenv|(^|\\s)env($|\\s))""").containsMatchIn(command))
                    "رفض قراءة أو عرض مفاتيح الاعتماد."
                else executeTerminal(command, 45)
            }
            else -> "هذه الأداة غير موجودة في M4: " + name
        }
    }

    private fun explicitOpenIntent(text: String): Boolean {
        val n=ArabicCommandRouter.normalize(text)
        return n.contains("افتح") || n.contains("شغل") || n.contains("شغّل") ||
               n.contains("open") || n.contains("launch")
    }

    private fun explicitGithubIntent(text: String): Boolean {
        val n=ArabicCommandRouter.normalize(text)
        return (n.contains("github") || n.contains("جيت هاب") || n.contains("غيتهاب")) &&
               (n.contains("اربط") || n.contains("ربط") || n.contains("وصل") ||
                n.contains("connect") || n.contains("login"))
    }

    private fun githubConnect(): String {
        app.startActivity(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_GITHUB_LOGIN,true))
        return "فُتحت الطرفية العربية لإجراء تسجيل دخول GitHub الرسمي عبر gh auth login. " +
               "اتبع تعليمات رمز الجهاز؛ لا تضع رمز وصولك في المحادثة."
    }

    private suspend fun executeTerminal(command: String, seconds: Int): String {
        // Prepare the same bundled Alpine filesystem used by MainActivity.
        // The first startup can take time while extracting verified assets.
        withContext(Dispatchers.IO) { LinuxEnvironment.install(app) }
        val ready=CompletableDeferred<TerminalService>()
        val intent=Intent(app,TerminalService::class.java)
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service=(binder as? TerminalService.LocalBinder)?.service
                if (service!=null) ready.complete(service)
                else ready.completeExceptionally(IllegalStateException("فشل الاتصال بخدمة الطرفية"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!ready.isCompleted) ready.completeExceptionally(
                    IllegalStateException("توقفت خدمة الطرفية"))
            }
        }
        var bound=false
        try {
            withContext(Dispatchers.Main) {
                app.startForegroundService(intent)
                bound=app.bindService(intent,connection,Context.BIND_AUTO_CREATE)
                if (!bound) throw IllegalStateException("لا يمكن ربط خدمة الطرفية.")
            }
            val service=withTimeout(10000) { ready.await() }
            return withContext(Dispatchers.IO) {
                service.runAgentCommand(command,seconds).take(25000)
            }
        } finally {
            if (bound) withContext(Dispatchers.Main) {
                try { app.unbindService(connection) } catch (_: IllegalArgumentException) {}
            }
        }
    }
}
