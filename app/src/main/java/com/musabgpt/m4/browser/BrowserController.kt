package com.musabgpt.m4.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.phone.AndroidPhoneTools
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Pages are data: no native JavaScript bridge, file origins or credentials. */
class BrowserController(private val context: Context) {
    val view = WebView(context)
    private val lock = Any()
    private val origins = mutableSetOf<String>()
    private val console = ArrayDeque<JSONObject>()
    private val network = ArrayDeque<JSONObject>()
    @Volatile private var loading: CompletableFuture<JSONObject>? = null
    @Volatile private var artifactOwner: String? = null
    var onNavigate: (() -> Unit)? = null
    @Volatile var status = "المتصفح جاهز"; private set
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false; safeBrowsingEnabled = true
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                synchronized(lock) { console.addLast(JSONObject().put("level", m.messageLevel().name).put("message", m.message().take(2000)).put("line", m.lineNumber())); while (console.size > 100) console.removeFirst() }; return true
            }
            override fun onPermissionRequest(r: PermissionRequest) { r.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !permitted(r.url.toString())
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                synchronized(lock) { network.addLast(JSONObject().put("url", r.url.toString().take(1500)).put("method", r.method)); while (network.size > 150) network.removeFirst() }
                return if (permitted(r.url.toString())) null else WebResourceResponse("text/plain", "UTF-8", 403, "Scope denied", emptyMap(), "Origin requires consent".byteInputStream())
            }
            override fun onPageFinished(v: WebView, url: String) { status = "الصفحة: $url"; loading?.complete(JSONObject().put("status", "loaded").put("url", url)) }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) loading?.complete(JSONObject().put("status", "failed").put("error", e.description.toString())) }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.cancel(); loading?.complete(JSONObject().put("status", "failed").put("error", "TLS certificate rejected")) }
        }
        view.layout(0, 0, 1080, 1600); current = this
    }
    private fun origin(url: String) = URI(url).let { "${it.scheme}://${it.host}:${if (it.port >= 0) it.port else if (it.scheme == "https") 443 else 80}" }
    private fun permitted(url: String) = BrowserSecurity.allowed(url) && synchronized(lock) { origin(url) in origins }
    fun openByUser(url: String) { require(BrowserSecurity.allowed(url)); synchronized(lock) { origins.add(origin(url)) }; artifactOwner = null; view.loadUrl(url) }
    private fun evaluate(script: String): Any? {
        val result = CompletableFuture<Any?>()
        Handler(Looper.getMainLooper()).post { view.evaluateJavascript(script) { try { result.complete(JSONTokener(it).nextValue()) } catch (e: Exception) { result.completeExceptionally(e) } } }
        return result.get(20, TimeUnit.SECONDS)
    }
    private fun json(script: String) = JSONObject(evaluate("JSON.stringify((()=>{$script})())").toString())
    fun call(action: String, args: JSONObject, owner: String): JSONObject {
        require(Looper.myLooper() != Looper.getMainLooper())
        if (action in setOf("open", "open_artifact")) {
            synchronized(lock) { console.clear(); network.clear() }
            AndroidPhoneTools.onMain { onNavigate?.invoke() }
            val next = CompletableFuture<JSONObject>(); loading = next
            if (action == "open_artifact") {
                ArtifactRepository.importPublished(context)
                val row = ArtifactRepository.list(context, owner).firstOrNull { it.id == args.getString("id") && it.mime == "text/html" } ?: error("لا يوجد مرفق HTML لهذه المحادثة")
                val html = ArtifactRepository.file(context, row.id, row.name).readText(); val base = "https://m4.local.invalid/artifacts/${row.id}/"
                synchronized(lock) { origins.add(origin(base)) }; artifactOwner = owner
                AndroidPhoneTools.onMain { view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null) }
            } else {
                val url = args.getString("url"); require(BrowserSecurity.allowed(url)); val scope = origin(url)
                if (!synchronized(lock) { scope in origins } && !CapabilityBroker.request(owner, "فتح موقع", JSONObject().put("origin", scope).put("url", url))) return JSONObject().put("status", "denied").put("error", "رفض نطاق الموقع")
                synchronized(lock) { origins.add(scope) }; artifactOwner = null; AndroidPhoneTools.onMain { view.loadUrl(url) }
            }
            return try { next.get(args.optInt("timeout", 25).coerceIn(1, 30).toLong(), TimeUnit.SECONDS) } catch (_: Exception) { JSONObject().put("status", "timed_out").put("error", "لم يكتمل التحميل") } finally { if (loading === next) loading = null }
        }
        if (action in setOf("console", "network")) return synchronized(lock) { JSONObject().put("status", "observed").put("events", JSONArray((if (action == "console") console else network).toList())) }
        if (action == "read_dom") return json("return {status:'observed',url:location.href,title:document.title,text:(document.body?.innerText||'').slice(0,12000),elements:Array.from(document.querySelectorAll('button,input,a,select,textarea')).slice(0,100).map(e=>({tag:e.tagName,id:e.id,text:e.innerText?.slice(0,200),type:e.type,password:e.type==='password'}))};")
        if (action in setOf("wait_for", "assert_visible")) {
            val selector = JSONObject.quote(args.getString("selector")); val text = JSONObject.quote(args.optString("text", ""))
            val end = System.nanoTime() + (if (action == "assert_visible") 0 else args.optInt("timeout", 8).coerceIn(0, 30)) * 1_000_000_000L
            do {
                val data = json("const e=document.querySelector($selector);return {status:e&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0&&(e.innerText||'').includes($text)?'verified':'waiting',selector:$selector,url:location.href};")
                if (data.optString("status") == "verified") return data
                if (System.nanoTime() >= end) return data.put("status", "failed").put("error", "DOM assertion failed")
                Thread.sleep(150)
            } while (true)
        }
        if (artifactOwner != owner && !CapabilityBroker.request(owner, "إجراء داخل الموقع: $action", args)) return JSONObject().put("status", "denied").put("error", "رفض الإجراء")
        if (action == "screenshot") return AndroidPhoneTools.onMain {
            val bitmap = Bitmap.createBitmap(view.width.coerceIn(1, 1440), view.height.coerceIn(1, 2400), Bitmap.Config.ARGB_8888); view.draw(android.graphics.Canvas(bitmap))
            val output = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
            ArtifactRepository.saveBinary(context, owner, args.optString("name", "browser.png"), "image/png", output.toByteArray()).json().put("status", "verified").put("published", true)
        }
        if (action in setOf("click", "type")) {
            val selector = JSONObject.quote(args.getString("selector")); val text = JSONObject.quote(args.optString("text", ""))
            return json("const e=document.querySelector($selector);if(!e)return {status:'failed',error:'Selector not found'};if(e.type==='password'||e.autocomplete?.includes('cc-'))return {status:'blocked',error:'Protected field'};" + (if (action == "click") "e.click();" else "e.focus();e.value=$text;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));") + "return {status:'dispatched',verified:false,url:location.href};")
        }
        if (action == "scroll") return json("window.scrollBy(0,${args.optInt("pixels", 600).coerceIn(-2400, 2400)});return {status:'observed',scrollY:window.scrollY};")
        if (action == "evaluate") { val script = args.getString("script"); require(script.length <= 20000); return JSONObject().put("status", "observed").put("value", evaluate(script)) }
        error("Browser action not registered")
    }
    fun close() { if (current === this) current = null; view.destroy() }
    companion object { @Volatile var current: BrowserController? = null; private set }
}
