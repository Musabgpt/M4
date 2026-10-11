package com.musabgpt.m4.browser

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.URI

object BrowserSecurity {
    fun allowed(raw: String): Boolean = try {
        val url = URI(raw)
        url.userInfo == null && url.host != null && (url.scheme == "https" ||
            (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost")))
    } catch (_: Exception) { false }
}

/** Only this owned WebView can be observed. No JavaScript bridge or file origins. */
@Composable
fun BrowserPanel() {
    var address by remember { mutableStateOf("https://github.com/Musabgpt/M4") }
    var status by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<WebView?>(null) }
    var observation by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { view?.destroy(); view = null } }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row {
            OutlinedTextField(address, { address = it }, label = { Text("رابط HTTPS أو خادم المشروع المحلي") }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (BrowserSecurity.allowed(address)) { view?.loadUrl(address); status = "جارٍ فتح الرابط" }
                else status = "الرابط غير مسموح؛ استخدم HTTPS أو loopback"
            }) { Text("فتح") }
        }
        Row {
            TextButton(onClick = { if (view?.canGoBack() == true) view?.goBack() }) { Text("رجوع") }
            TextButton(onClick = { view?.reload() }) { Text("تحديث") }
            TextButton(onClick = {
                view?.evaluateJavascript("JSON.stringify({url:location.href,title:document.title,text:(document.body?.innerText||'').slice(0,8000),links:Array.from(document.links).slice(0,30).map(x=>({text:x.innerText,href:x.href}))})") { result ->
                    observation = try { org.json.JSONTokener(result).nextValue().toString() } catch (_: Exception) { result }
                }
            }) { Text("قراءة DOM") }
        }
        Text(status, style = MaterialTheme.typography.labelSmall)
        AndroidView(factory = { context -> WebView(context).apply {
            view = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean =
                    !BrowserSecurity.allowed(request.url.toString())
                override fun onPageFinished(web: WebView, url: String) { status = "الصفحة: $url" }
                override fun onReceivedSslError(web: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); status = "رُفض الاتصال بسبب شهادة TLS"
                }
            }
        } }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
    observation?.let { data -> AlertDialog(onDismissRequest = { observation = null }, title = { Text("DOM الملاحظ فعلياً") },
        text = { Text(data.take(9000)) }, confirmButton = { TextButton(onClick = { observation = null }) { Text("إغلاق") } }) }
}
