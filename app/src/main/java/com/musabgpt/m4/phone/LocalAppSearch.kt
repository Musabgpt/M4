package com.musabgpt.m4.phone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Explicit app/search requests are Android actions, not model prompts.
 * Never claim to have searched without observing a real editable search field.
 * Searching here never sends a WhatsApp message to another person.
 */
object LocalAppSearch {
    data class Request(val app: String, val query: String)
    fun parse(text: String): Request? {
        val normalized = PhoneIntent.normalize(text)
        val match = Regex(
            "^(?:افتح|شغل)\\s+(?:تطبيق\\s+)?(واتساب|الواتساب|واتس\\s*اب|whatsapp)" +
                "\\s+(?:و|ثم\\s+)?ابحث\\s+عن\\s+(.{1,140})$"
        ).matchEntire(normalized) ?: return null
        return Request("com.whatsapp", match.groupValues[2].trim())
    }

    suspend fun search(context: Context, owner: String, request: Request): JSONObject {
        val launched = AndroidPhoneTools.openApp(context, request.app)
        if (launched.optString("status") !in setOf("dispatched", "verified"))
            return JSONObject().put("status", "failed")
                .put("content", launched.optString("error", "تعذّر فتح واتساب"))
        delay(900)
        val visible = withContext(Dispatchers.IO) { PhoneAccessibilityTools.observe() }
        if (visible.optString("status") != "observed" ||
            visible.optString("package") !in setOf("com.whatsapp", "com.whatsapp.w4b"))
            return JSONObject().put("status", "partial")
                .put("content", "فتحت واتساب، لكن لا أستطيع قراءة حقل البحث. فعّل خدمة M4 للتحكم بالهاتف وأكمل البحث يدوياً.")
        if (visible.optBoolean("protected"))
            return JSONObject().put("status", "partial")
                .put("content", "فتحت واتساب، لكن الشاشة تحتوي بيانات حساسة. أكمل البحث يدوياً.")
        val nodes = visible.optJSONArray("nodes")
        val searchNode = (0 until (nodes?.length() ?: 0)).mapNotNull { index ->
            nodes?.optJSONObject(index)
        }.firstOrNull { node ->
            val label = node.optString("description").lowercase() + " " +
                node.optString("text").lowercase()
            val id = node.optString("resource_id").lowercase()
            (node.optBoolean("enabled") && node.optBoolean("clickable")) &&
                (id.contains("search") || Regex("\\bsearch\\b|بحث").containsMatchIn(label))
        }
        if (searchNode == null)
            return JSONObject().put("status", "partial")
                .put("content", "فتحت واتساب، لكن لم أجد زر بحث يمكن تحديده بأمان. لم أكتب أو أرسل أي شيء.")
        val clicked = withContext(Dispatchers.IO) {
            PhoneAccessibilityTools.dispatch(context, "tap",
                JSONObject().put("node", searchNode.getString("node")), owner, false)
        }
        if (clicked.optString("status") != "dispatched")
            return JSONObject().put("status", "partial")
                .put("content", "فتحت واتساب، لكن لم يُسمح بالضغط على زر البحث.")
        delay(300)
        val screen = withContext(Dispatchers.IO) { PhoneAccessibilityTools.observe() }
        val inputs = screen.optJSONArray("nodes")
        val editable = (0 until (inputs?.length() ?: 0)).mapNotNull { i ->
            inputs?.optJSONObject(i)
        }.firstOrNull { it.optBoolean("editable") && it.optBoolean("enabled") && !it.optBoolean("password") }
        if (editable == null)
            return JSONObject().put("status", "partial")
                .put("content", "فتحت بحث واتساب، لكن لم أجد خانة كتابة متاحة.")
        val typed = withContext(Dispatchers.IO) {
            PhoneAccessibilityTools.dispatch(context, "type",
                JSONObject().put("node", editable.getString("node")).put("text", request.query),
                owner, false)
        }
        val after = withContext(Dispatchers.IO) { PhoneAccessibilityTools.observe() }
        val rows = after.optJSONArray("nodes")
        val verified = (0 until (rows?.length() ?: 0)).any { i ->
            rows?.optJSONObject(i)?.optString("text")?.contains(request.query) == true
        }
        return if (typed.optString("status") == "dispatched" && verified)
            JSONObject().put("status", "verified")
                .put("content", "فتحت واتساب وكتبت عبارة البحث: " + request.query +
                    ". لم أرسل أي رسالة إلى أي شخص.")
        else JSONObject().put("status", "partial")
            .put("content", "فتحت واتساب، لكن لم أتأكد من كتابة البحث. لم أرسل أي رسالة.")
    }
}
