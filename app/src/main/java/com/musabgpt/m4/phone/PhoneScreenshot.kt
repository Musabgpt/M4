package com.musabgpt.m4.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.musabgpt.m4.artifacts.ArtifactRepository
import io.musab.arabicterminal.UiControlService
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object PhoneScreenshot {
    fun capture(context: Context, owner: String, name: String): JSONObject {
        if (Build.VERSION.SDK_INT < 30) return JSONObject().put("status", "unavailable").put("error", "التقاط الشاشة عبر الخدمة يتطلب Android 11 أو أحدث")
        if (PhoneAccessibilityTools.observe().optBoolean("protected")) return JSONObject().put("status", "blocked").put("error", "لن ألتقط شاشة حساسة")
        val service = UiControlService.current() ?: return JSONObject().put("status", "permission_required").put("needs", "accessibility")
        val result = CompletableFuture<JSONObject>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(value: AccessibilityService.ScreenshotResult) {
                val buffer = value.hardwareBuffer
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, value.colorSpace) ?: error("Screenshot bitmap unavailable")
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    val output = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle(); hardware.recycle()
                    result.complete(ArtifactRepository.saveBinary(context, owner, name.removeSuffix(".png") + ".png", "image/png", output.toByteArray()).json().put("status", "verified").put("published", true))
                } catch (e: Exception) { result.complete(JSONObject().put("status", "failed").put("error", e.message)) }
                finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) { result.complete(JSONObject().put("status", "failed").put("error_code", errorCode).put("error", "رفض أندرويد لقطة الشاشة؛ قد تكون النافذة محمية")) }
        })
        return result.get(10, TimeUnit.SECONDS)
    }
}
