package com.musabgpt.m4

import io.musab.arabicterminal.ArabicCommandRouter
import org.json.JSONObject

/**
 * On-device task decomposition BEFORE naive app-name shortcuts. In particular,
 * "افتح واتساب وابعت رسالة مرحبا الى مروة" is NOT an application title.
 * Contact names and message bodies stay local, never passed to an LLM gateway.
 */
internal object WhatsAppPlanner {
    data class Draft(val recipient: String, val body: String) {
        fun asAction(): String = "whatsapp_draft:" + JSONObject()
            .put("recipient", recipient).put("body", body).toString()
    }

    private val compound = Regex(
        """(?is)^(?:افتح\s+)?(?:تطبيق\s+)?(?:الواتساب|واتساب|واتس\s*اب|واتس|whatsapp)\s+(?:و\s*|ثم\s+)?(?:ارسل|ابعت|ابعث|ابريل|اريل|بعت)\s+(?:رسالة\s+|رساله\s+)?(.+?)\s+(?:الى|الي|ل)\s*(.+?)\s*[.!؟]*$"""
    )

    fun parse(request: String): Draft? {
        val normalized = ArabicCommandRouter.normalize(request)
        val match = compound.matchEntire(normalized) ?: return null
        val body = match.groupValues[1].trim()
        val recipient = match.groupValues[2].trim()
        if (body.isBlank() || recipient.isBlank() || body.length > 2000 ||
            recipient.length > 100) return null
        return Draft(recipient, body)
    }

    fun fromAction(action: String): Draft? {
        if (!action.startsWith("whatsapp_draft:")) return null
        return try {
            val data=JSONObject(action.removePrefix("whatsapp_draft:"))
            val who=data.getString("recipient").trim()
            val body=data.getString("body").trim()
            if (who.isBlank() || body.isBlank() || who.length > 100 || body.length > 2000)
                null else Draft(who, body)
        } catch (_: Exception) { null }
    }

    fun isPhone(recipient: String): Boolean {
        val clean=recipient.replace(Regex("""[\s\-()+]"""), "")
        return clean.length in 8..15 && clean.all { it.isDigit() }
    }
}
