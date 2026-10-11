package com.musabgpt.m4.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONObject

/** Adapted from NewAl-Cloud's public-intent layer, with concrete approvals. */
object PhonePublicIntents {
    val actions = setOf("device", "battery", "open_url", "settings", "dial", "sms", "share")

    fun dispatch(context: Context, action: String, args: JSONObject, owner: String): JSONObject {
        if (action == "device") return JSONObject().put("status", "observed").put("platform", "Android")
            .put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        if (action == "battery") {
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return JSONObject().put("status", "observed").put("percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", battery.isCharging)
        }
        if (!CapabilityBroker.request(owner, "إجراء Android: $action", args))
            return JSONObject().put("status", "denied").put("error", "رفض المستخدم الإجراء")
        val intent = when (action) {
            "open_url" -> {
                val uri = Uri.parse(args.getString("url"))
                require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
                Intent(Intent.ACTION_VIEW, uri)
            }
            "settings" -> Intent(when (args.optString("page", "general")) {
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                "general" -> Settings.ACTION_SETTINGS
                else -> error("صفحة إعدادات غير مسجلة")
            }).apply { if (args.optString("page") == "app") data = Uri.parse("package:" + context.packageName) }
            "dial", "sms" -> {
                val number = args.getString("number")
                require(number.matches(Regex("[+0-9 ()-]{1,40}")))
                if (action == "dial") Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                else Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", args.getString("text").take(10000))
            }
            "share" -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, args.getString("text").take(10000)), "مشاركة")
            else -> error("إجراء غير مسجل")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return AndroidPhoneTools.onMain {
            try {
                context.startActivity(intent)
                JSONObject().put("status", "dispatched").put("screen_verified", false)
                    .put("message", if (action in setOf("dial", "sms", "share")) "فُتحت شاشة المراجعة؛ يؤكد المستخدم الإرسال أو الاتصال" else "أُرسل الإجراء إلى Android")
            } catch (e: android.content.ActivityNotFoundException) {
                JSONObject().put("status", "unavailable").put("error", "لا يوجد تطبيق يستقبل هذا الإجراء")
            }
        }
    }
}
