package com.musabgpt.m4.browser

import android.content.Context
import com.musabgpt.m4.artifacts.ArtifactRepository
import org.json.JSONArray
import org.json.JSONObject

object BrowserTestLab {
    fun run(context: Context, args: JSONObject, owner: String): JSONObject {
        val controller = BrowserController.current ?: error("المتصفح غير متاح")
        val steps = args.getJSONArray("steps"); require(steps.length() in 1..30)
        require((0 until steps.length()).any { steps.getJSONObject(it).getString("action") == "assert_visible" })
        val receipts = JSONArray()
        val opening = controller.call("open_artifact", JSONObject().put("id", args.getString("id")), owner)
        receipts.put(JSONObject().put("action", "open_artifact").put("result", opening))
        var outcome = if (opening.optString("status") == "loaded") "PASS" else "BLOCKED"
        if (outcome == "PASS") for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i); val action = step.getString("action")
            require(action in setOf("click", "type", "scroll", "wait_for", "assert_visible", "read_dom"))
            val observed = try { controller.call(action, step, owner) } catch (e: Exception) { JSONObject().put("status", "failed").put("error", e.message) }
            receipts.put(JSONObject().put("step", i + 1).put("action", action).put("result", observed))
            if (observed.optString("status") !in setOf("verified", "observed", "dispatched")) { outcome = "FAIL"; break }
        }
        val console = controller.call("console", JSONObject(), owner)
        val logs = console.getJSONArray("events")
        if ((0 until logs.length()).any { logs.getJSONObject(it).optString("level") == "ERROR" }) outcome = "FAIL"
        val screenshot = controller.call("screenshot", JSONObject().put("name", "browser-test.png"), owner)
        if (screenshot.optString("status") != "verified") outcome = "BLOCKED"
        val report = "# Browser test\n\nResult: $outcome\nBackend: owned Android WebView\n\n```json\n${receipts.toString(2)}\n```\n\n## Console\n```json\n${console.toString(2)}\n```\n\n## Screenshot\n```json\n${screenshot.toString(2)}\n```\n"
        return ArtifactRepository.saveReport(context, owner, "browser-test.md", report).json().put("published", true)
            .put("status", if (outcome == "PASS") "verified" else "failed").put("outcome", outcome).put("screenshot", screenshot).put("console", console)
    }
}
