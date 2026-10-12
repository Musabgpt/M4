package com.musabgpt.m4.runtime

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeveloperHostPanel(autoStart: Boolean = false, host: DeveloperHost = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(autoStart) { if (autoStart) host.open(context) }
    val base = host.endpoint
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Code-OSS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
            if (host.busy || base != null) TextButton(onClick = { host.stop() }) { Text("إيقاف") }
        }
        if (base == null) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("محرر وإضافات VS Code المتوافقة", style = MaterialTheme.typography.titleLarge)
                Text("تثبيت اختياري يشمل Code-OSS ومتجر Open VSX. خوادم اللغات تعمل عبر إضافاتها داخل هذا المحرر. يستخدم مشروع M4 نفسه، ولا يحتاج Termux.")
                Text("يتطلب تنزيلًا كبيرًا ومساحة إضافية واتصال إنترنت أثناء التثبيت. الإضافات التي تعتمد على برامج سطح المكتب قد لا تدعم هذه البيئة.", style = MaterialTheme.typography.bodySmall)
                if (host.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (host.status.isNotEmpty()) Text(host.status, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { host.open(context) }, enabled = !host.busy) { Text("تثبيت وفتح المحرر") }
            }
        } else key(base) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.rgb(24, 24, 24))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val target = request.url
                            val origin = Uri.parse(base)
                            if (target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port) return false
                            if (target.scheme == "https") runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                            return true
                        }
                    }
                    // Password is random per process, sent in a POST body, never in URLs or logs.
                    val destination = URLEncoder.encode("/?folder=/root/projects/M4", "UTF-8")
                    postUrl("$base/login?to=$destination", ("password=" + URLEncoder.encode(host.password, "UTF-8")).toByteArray(Charsets.UTF_8))
                }
            }, onRelease = { it.stopLoading(); it.destroy() })
        }
    }
}
