package com.musabgpt.m4.phone

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.musabgpt.m4.runtime.CapabilityBroker
import io.musab.arabicterminal.PhoneController
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Public Android intents. Dispatch is reported separately from screen verification. */
object AndroidPhoneTools {
    fun listApps(context: Context): JSONObject = JSONObject().put("status", "observed").put("apps", JSONArray().apply {
        PhoneController.launchers(context).take(200).forEach { app -> put(JSONObject().put("name", app.label).put("package", app.packageName)) }
    })

    fun openApp(context: Context, target: String): JSONObject {
        require(target.isNotBlank() && target.length <= 100)
        val alias = PhoneIntent.alias(target)
        val exact = PhoneController.launchers(context).filter {
            it.packageName == alias || PhoneIntent.normalize(it.label) == PhoneIntent.normalize(target)
        }
        if (exact.isEmpty()) return JSONObject().put("status", "unavailable").put("error", "لم أجد تطبيقًا مثبتًا باسم «$target»")
        if (exact.map { it.packageName }.distinct().size > 1) return JSONObject().put("status", "blocked").put("error", "الاسم يطابق أكثر من تطبيق؛ حدّد اسم الحزمة")
        val app = exact.first()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JSONObject().put("status", "dispatched").put("package", app.packageName).put("name", app.label)
            .put("screen_verified", false).put("message", "أرسلت أمر فتح «${app.label}» إلى أندرويد")
    }

    fun dispatch(context: Context, action: String, arguments: JSONObject, owner: String, turn: String, allowSetup: Boolean = true): JSONObject {
        if (action == "list_apps") return listApps(context)
        if (action in PhonePublicIntents.actions) return PhonePublicIntents.dispatch(context, action, arguments, owner)
        val goal = CapabilityBroker.explicitGoal(context, owner, turn).orEmpty()
        if (action == "open_app") {
            val target = arguments.getString("app")
            val explicit = PhoneIntent.openTarget(goal)?.let { PhoneIntent.alias(it) == PhoneIntent.alias(target) } == true
            if (!explicit && !CapabilityBroker.request(owner, "فتح تطبيق", arguments))
                return JSONObject().put("status", "denied").put("error", "لم يسمح المستخدم بفتح هذا التطبيق")
            return onMain { openApp(context, target) }
        }
        return PhoneAccessibilityTools.dispatch(context, action, arguments, owner, allowSetup)
    }

    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        Handler(Looper.getMainLooper()).post { try { future.complete(block()) } catch (e: Throwable) { future.completeExceptionally(e) } }
        return future.get(20, TimeUnit.SECONDS)
    }
}
