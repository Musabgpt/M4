package com.musabgpt.m4.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.SearchManager
import android.view.accessibility.AccessibilityNodeInfo
import io.musab.arabicterminal.UiControlService
import org.json.JSONObject

/** Non-model Android routing for explicitly requested YouTube searches. */
object YouTubeActions {
    private val request = Regex(
        "^(?:افتح\\s+)?(?:تطبيق\\s+)?(?:يوتيوب|اليوتيوب|youtube)\\s+" +
        "(?:و?ابحث\\s+(?:عن\\s+)?|ابحث\\s+(?:عن\\s+)?)(.{1,150})$",
        RegexOption.IGNORE_CASE
    )
    data class Search(val query: String, val play: Boolean)

    fun parse(text: String): Search? {
        val normalized = PhoneIntent.normalize(text)
        // Covers "افتح يوتيوب وابحث عن ... وشغلي الفيديو" without losing Arabic titles.
        val match = Regex(
            "^(?:افتح\\s+)?(?:يوتيوب|اليوتيوب|youtube)\\s+(?:و)?ابحث\\s+عن\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(normalized) ?: return null
        val raw = match.groupValues[1].trim()
        val playSuffix = Regex("\\s+(?:و)?(?:شغل(?:لي|ي)?|شغّل(?:لي|ي)?|اعرض)\\s+(?:اول\\s+)?(?:الفيديو|فيديو|المقطع)(?:\\s+الاول)?\\s*$")
        val play = playSuffix.containsMatchIn(raw)
        val query = raw.replace(playSuffix, "").trim()
        if (query.isBlank() || query.length > 140) return null
        return Search(query, play)
    }

    fun search(context: Context, item: Search): JSONObject {
        val youtube = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube")
            .putExtra(SearchManager.QUERY, item.query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" +
                Uri.encode(item.query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return AndroidPhoneTools.onMain {
            try {
                context.startActivity(youtube)
                JSONObject().put("status", "dispatched")
                    .put("query", item.query).put("target", "youtube_search")
                    .put("message", "أرسلت البحث إلى يوتيوب: " + item.query)
            } catch (_: android.content.ActivityNotFoundException) {
                try {
                    context.startActivity(fallback)
                    JSONObject().put("status", "dispatched")
                        .put("query", item.query).put("target", "youtube_web_search")
                        .put("message", "فتحت نتائج بحث يوتيوب في المتصفح")
                } catch (e: Exception) {
                    JSONObject().put("status", "unavailable")
                        .put("error", "لا يوجد تطبيق لفتح بحث يوتيوب: " + e.message)
                }
            }
        }
    }

    /** Only attempt playback when Android exposes an actual YouTube result node. */
    fun tryPlayFirst(): JSONObject = AndroidPhoneTools.onMain {
        val service = UiControlService.current()
            ?: return@onMain JSONObject().put("status", "permission_required")
                .put("error", "البحث أُرسل، لكن تشغيل النتيجة يتطلب تفعيل خدمة تحكم الهاتف من إعدادات إمكانية الوصول.")
        val root = service.rootInActiveWindow
            ?: return@onMain JSONObject().put("status", "unavailable")
                .put("error", "لم يمكن قراءة نتائج يوتيوب بعد.")
        if (root.packageName?.toString() != "com.google.android.youtube")
            return@onMain JSONObject().put("status", "not_verified")
                .put("error", "لم تظهر شاشة تطبيق يوتيوب؛ لم أضغط على أي نتيجة.")
        fun candidate(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 18 || n.isPassword) return null
            val id = n.viewIdResourceName.orEmpty().lowercase()
            val label = (n.contentDescription?.toString() ?: "").lowercase()
            if (n.isClickable && n.isEnabled && (
                id.contains("video_item") || id.contains("video_thumbnail") ||
                id.contains("compact_video") || label.startsWith("video: ")
            )) return n
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                candidate(child, depth + 1)?.let { return it }
            }
            return null
        }
        val video = candidate(root, 0)
            ?: return@onMain JSONObject().put("status", "not_verified")
                .put("error", "ظهرت نتائج يوتيوب لكن تعذّر تحديد فيديو آمن للنقر عليه؛ اختر النتيجة يدوياً.")
        val clicked = video.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        JSONObject().put("status", if (clicked) "dispatched" else "failed")
            .put("screen_verified", false)
            .put("message", if (clicked) "تم الضغط على نتيجة فيديو؛ تعذّر تأكيد بدء التشغيل بعد." else "لم يستجب الفيديو للنقر")
    }
}
