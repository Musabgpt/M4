package com.musabgpt.m4

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.musabgpt.m4.runtime.DeveloperHost
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Network-heavy optional-host test, enabled explicitly by the runtime QA workflow. */
class DeveloperHostInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<M4ChatActivity>()

    @Test fun installsRealHostAndRequiresAuthentication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("developerHost") == "true")
        lateinit var host: DeveloperHost
        activity.runOnIdle {
            host = ViewModelProvider(activity.activity)[DeveloperHost::class.java]
            host.open(activity.activity)
        }
        try {
            activity.waitUntil(1_800_000) { !host.busy }
            assertNotNull(host.status, host.endpoint)
            val base = host.endpoint!!
            fun connection(path: String) = (URL(base + path).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 30000
            }
            val anonymous = connection("/")
            try { assertEquals(302, anonymous.responseCode); assertTrue(anonymous.getHeaderField("Location").contains("login")) }
            finally { anonymous.disconnect() }
            val login = connection("/login")
            val cookie: String
            try {
                login.requestMethod = "POST"; login.doOutput = true
                login.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                login.outputStream.use { it.write(("password=" + URLEncoder.encode(host.password, "UTF-8")).toByteArray()) }
                assertEquals(302, login.responseCode)
                cookie = login.getHeaderField("Set-Cookie").substringBefore(';')
                assertTrue(cookie.isNotBlank())
            } finally { login.disconnect() }
            val editor = connection("/?folder=/root/projects/M4")
            try {
                editor.setRequestProperty("Cookie", cookie)
                assertEquals(200, editor.responseCode)
                assertTrue(editor.inputStream.bufferedReader().use { it.readText().contains("workbench") })
            } finally { editor.disconnect() }
            activity.onNodeWithTag("workbench-tools").performClick()
            activity.onNodeWithText("تثبيت وفتح").performClick()
            val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
            fun findBrowser(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    findBrowser(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            activity.waitUntil(120000) {
                activity.activity.runOnUiThread {
                    findBrowser(activity.activity.window.decorView)?.evaluateJavascript(
                        "document.querySelector('.monaco-workbench') !== null"
                    ) { rendered.set(it == "true") }
                }
                rendered.get()
            }
            assertTrue("Authenticated editor must render inside the Android WebView", rendered.get())
            File(activity.activity.filesDir, "qa").apply { mkdirs() }.resolve("developer-host.txt")
                .writeText("Actual Android PRoot install, authenticated HTTP and Android WebView workbench rendering passed.\nExtension activation and language-server responses are separate checks.\n")
        } finally { activity.runOnIdle { host.stop() } }
    }
}
