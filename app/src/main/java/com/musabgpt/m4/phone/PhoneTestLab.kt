package com.musabgpt.m4.phone

import android.content.Context
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONArray
import org.json.JSONObject

/** Real launch → observation → interaction → assertions → screenshot receipts. */
object PhoneTestLab {
    fun run(context: Context, args: JSONObject, owner: String, turn: String): JSONObject {
        val steps = args.getJSONArray("steps")
        require(steps.length() in 1..30)
        require((0 until steps.length()).any { steps.getJSONObject(it).getString("action") == "assert_visible" }) { "Test requires a visible UI assertion" }
        if (!CapabilityBroker.request(owner, "مراجعة اختبار تطبيق Android", args)) return JSONObject().put("status", "denied").put("error", "رفض المستخدم حالة الاختبار")
        val receipts = JSONArray()
        val setup = PhoneAccessibilityTools.dispatch(context, "screen", JSONObject(), owner)
        val launch = if (setup.optString("status") == "observed") AndroidPhoneTools.onMain { AndroidPhoneTools.openApp(context, args.getString("app")) }
            else setup
        receipts.put(JSONObject().put("action", "launch").put("result", launch))
        var outcome = if (launch.optString("status") == "dispatched") "PASS" else "BLOCKED"
        val actions = setOf("screen", "wait_for", "assert_visible", "tap", "type", "swipe", "scroll", "long_press", "back", "home")
        if (outcome == "PASS") for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i); val action = step.getString("action"); require(action in actions)
            val observed = try { PhoneAccessibilityTools.dispatch(context, action, step, owner) }
                catch (e: Exception) { JSONObject().put("status", "failed").put("error", e.message) }
            receipts.put(JSONObject().put("step", i + 1).put("action", action).put("result", observed))
            val state = observed.optString("status")
            if (state !in setOf("observed", "verified", "dispatched")) { outcome = if (state in setOf("permission_required", "blocked", "unavailable", "denied")) "BLOCKED" else "FAIL"; break }
        }
        val screenshot = try { if (launch.optString("status") == "dispatched") PhoneScreenshot.capture(context, owner, "phone-test.png")
            else JSONObject().put("status", "skipped").put("reason", "لم يُفتح التطبيق المستهدف") }
            catch (e: Exception) { JSONObject().put("status", "failed").put("error", e.message) }
        if (outcome == "PASS" && screenshot.optString("status") != "verified") outcome = "BLOCKED"
        val report = "# ${args.optString("name", "Android test")}\n\nResult: $outcome\nExecution: Android Accessibility\nDevice: ${android.os.Build.MODEL}\nSDK: ${android.os.Build.VERSION.SDK_INT}\n\n## Recorded steps\n```json\n${receipts.toString(2)}\n```\n\n## Screenshot receipt\n```json\n${screenshot.toString(2)}\n```\n"
        val row = ArtifactRepository.saveReport(context, owner, "phone-test.md", report)
        return row.json().put("published", true).put("status", if (outcome == "PASS") "verified" else if (outcome == "FAIL") "failed" else "blocked")
            .put("outcome", outcome).put("steps", receipts).put("screenshot", screenshot)
    }
}
