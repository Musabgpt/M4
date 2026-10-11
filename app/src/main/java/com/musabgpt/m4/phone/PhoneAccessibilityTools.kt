package com.musabgpt.m4.phone

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.musabgpt.m4.runtime.CapabilityBroker
import io.musab.arabicterminal.UiControlService
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded observations and user-consented actions through the real service. */
object PhoneAccessibilityTools {
    private fun missing() = JSONObject().put("status", "permission_required").put("needs", "accessibility")
        .put("error", "فعّل خدمة «M4 • التحكم بالهاتف» من إعدادات إمكانية الوصول")

    fun observe(): JSONObject = AndroidPhoneTools.onMain {
        val service = UiControlService.current() ?: return@onMain missing()
        val root = service.rootInActiveWindow ?: return@onMain JSONObject().put("status", "unavailable").put("error", "لا توجد نافذة قابلة للقراءة")
        val nodes = JSONArray()
        var protected = false
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (nodes.length() >= 300 || depth > 25) return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val password = node.isPassword
            protected = protected || password
            val text = if (password) "[REDACTED]" else node.text?.toString().orEmpty().take(400)
            val description = if (password) "" else node.contentDescription?.toString().orEmpty().take(400)
            if (Regex("password|passcode|verification code|one.time|بطاقة|كلمة المرور|رمز التحقق|دفع|credit card", RegexOption.IGNORE_CASE).containsMatchIn(text + description)) protected = true
            nodes.put(JSONObject().put("node", path).put("text", text).put("description", description)
                .put("resource_id", node.viewIdResourceName ?: "").put("clickable", node.isClickable)
                .put("editable", node.isEditable).put("password", password).put("enabled", node.isEnabled)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> visit(child, "$path/$index", depth + 1) }
        }
        visit(root, "0", 0)
        val result = JSONObject().put("status", "observed").put("package", root.packageName?.toString().orEmpty())
            .put("nodes", nodes).put("protected", protected).put("truncated", nodes.length() >= 300)
        val hash = MessageDigest.getInstance("SHA-256").digest(result.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        result.put("state_hash", hash)
    }

    private fun node(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        if (!path.matches(Regex("0(?:/[0-9]{1,3}){0,25}"))) return null
        var current: AccessibilityNodeInfo? = root
        path.split('/').drop(1).forEach { current = current?.getChild(it.toInt()) }
        return current
    }

    fun dispatch(context: Context, action: String, args: JSONObject, owner: String, allowSetup: Boolean = true): JSONObject {
        if (UiControlService.current() == null) {
            if (!allowSetup) return missing()
            val prefs = context.getSharedPreferences("m4_capabilities", 0)
            if (!prefs.getBoolean("accessibility_prompted", false)) {
                prefs.edit().putBoolean("accessibility_prompted", true).apply()
                if (CapabilityBroker.request(owner, "تفعيل التحكم بالهاتف", JSONObject().put("reason", "قراءة الشاشة والنقر والكتابة عبر خدمة إمكانية الوصول"))) {
                    AndroidPhoneTools.onMain { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    val end = System.nanoTime() + 90_000_000_000L
                    while (UiControlService.current() == null && System.nanoTime() < end) Thread.sleep(300)
                }
            }
            if (UiControlService.current() == null) return missing()
        }
        if (action == "screen") return observe()
        if (action in setOf("wait_for", "assert_visible")) {
            val timeout = if (action == "assert_visible") 0 else args.optInt("timeout", 8).coerceIn(0, 30)
            val until = System.nanoTime() + timeout * 1_000_000_000L
            var tree: JSONObject
            do {
                tree = observe()
                val expected = args.getString("text")
                val visible = tree.optJSONArray("nodes")?.let { nodes -> (0 until nodes.length()).any { nodes.getJSONObject(it).optString("text").contains(expected) || nodes.getJSONObject(it).optString("description").contains(expected) } } == true
                if (visible) return JSONObject().put("status", "verified").put("assertion", expected).put("state_hash", tree.optString("state_hash"))
                if (System.nanoTime() >= until) break
                Thread.sleep(250)
            } while (true)
            return JSONObject().put("status", "failed").put("error", "لم يظهر النص المطلوب على الشاشة").put("state_hash", tree.optString("state_hash"))
        }
        if (action == "screenshot") return PhoneScreenshot.capture(context, owner, args.optString("name", "phone.png"))
        val before = observe()
        if (before.optBoolean("protected")) return JSONObject().put("status", "blocked").put("error", "الشاشة حساسة؛ أكمل هذه الخطوة يدويًا")
        if (action in setOf("tap", "type", "swipe", "scroll", "long_press") &&
            !CapabilityBroker.request(owner, "إجراء على الهاتف: $action", args)) return JSONObject().put("status", "denied").put("error", "رفض المستخدم إجراء الهاتف")
        val dispatched = AndroidPhoneTools.onMain {
            val service = UiControlService.current() ?: return@onMain false
            val root = service.rootInActiveWindow ?: return@onMain false
            val target = if (args.has("node")) node(root, args.getString("node")) else null
            when (action) {
                "tap" -> if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else if (args.has("text")) service.clickLabel(args.getString("text")) else service.tap(args.getInt("x"), args.getInt("y"))
                "long_press" -> target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                "type" -> {
                    val editable = target ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    editable != null && editable.isEditable && !editable.isPassword && editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text")) })
                }
                "scroll" -> (target ?: root).performAction(if (args.optString("direction", "down") == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                "swipe" -> service.swipe(args.optString("direction", "up") == "up")
                "back" -> service.performGlobalAction(UiControlService.BACK)
                "home" -> service.performGlobalAction(UiControlService.HOME)
                "recents" -> service.performGlobalAction(UiControlService.RECENTS)
                "notifications" -> service.performGlobalAction(UiControlService.NOTIFICATIONS)
                else -> false
            }
        }
        Thread.sleep(300)
        val after = observe()
        return JSONObject().put("status", if (dispatched) "dispatched" else "failed").put("before_state_hash", before.optString("state_hash"))
            .put("after_state_hash", after.optString("state_hash")).put("changed", before.optString("state_hash") != after.optString("state_hash"))
            .put("action", action).put("screen_verified", false)
    }
}
