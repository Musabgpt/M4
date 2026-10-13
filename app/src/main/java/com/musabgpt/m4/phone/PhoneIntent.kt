package com.musabgpt.m4.phone

/** Only an unambiguous user launcher request qualifies for the local fast path. */
object PhoneIntent {
    fun normalize(text: String) = text.trim().lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "").replace(Regex("\\s+"), " ")
    fun openTarget(text: String): String? {
        val clean = normalize(text).removePrefix("لو سمحت ").removePrefix("please ")
        val match = Regex("^(?:افتح|شغل تطبيق|open|launch)\\s+(?:تطبيق\\s+|برنامج\\s+|app\\s+)?(.{1,100})[.!؟?]?$", RegexOption.IGNORE_CASE).matchEntire(clean) ?: return null
        val target = match.groupValues[1].trim().trimEnd('.', '!', '؟', '?')
        if (target.contains(Regex("(?:\\sو(?:ا|ش|ن|ح|ب)|\\s(?:and|then)\\s)|[;/\\n]"))) return null
        return target.takeIf { it.isNotBlank() && (alias(it) != normalize(it) ||
            Regex("^(?:افتح|open|launch)\\s+(?:تطبيق|برنامج|app)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
            it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+){2,}"))) }
    }
    fun alias(text: String): String = when (normalize(text)) {
        "واتساب", "الواتساب", "واتس اب", "واتس", "whatsapp" -> "com.whatsapp"
        "واتساب بزنس", "whatsapp business" -> "com.whatsapp.w4b"
        "تيليجرام", "تلغرام", "تليجرام", "telegram" -> "org.telegram.messenger"
        "يوتيوب", "youtube" -> "com.google.android.youtube"
        "كروم", "chrome" -> "com.android.chrome"
        "جيميل", "gmail" -> "com.google.android.gm"
        "ترمكس", "تيرمكس", "termux" -> "com.termux"
        else -> normalize(text)
    }
}
