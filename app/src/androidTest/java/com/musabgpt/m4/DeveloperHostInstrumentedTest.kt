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
            val runtimeRoot = io.musab.arabicterminal.LinuxEnvironment.rootfs(activity.activity)
            val resultFile = File(runtimeRoot, "tmp/m4-language-receipt.json")
            resultFile.delete()
            // Trust only this test fixture, never change the production default.
            val settings = File(runtimeRoot, "root/.local/share/m4-code/user-data/User/settings.json")
            settings.writeText(org.json.JSONObject(settings.readText()).put("security.workspace.trust.enabled", false).toString())
            val archive = File(runtimeRoot, "root/.m4/m4-language-probe.vsix")
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("package.json", "extension.js")) {
                    zip.putNextEntry(java.util.zip.ZipEntry("extension/$name"))
                    InstrumentationRegistry.getInstrumentation().context.assets.open("developer-host-probe/$name").use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val installed = com.musabgpt.m4.scheduler.AlpineCommandRunner.run(activity.activity,
                DeveloperHost.BINARY + " --extensions-dir /root/.local/share/m4-code/extensions --install-extension /root/.m4/m4-language-probe.vsix") { false }
            assertEquals(installed.stdout + installed.stderr, "succeeded", installed.status)
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
            activity.waitUntil(120000) { resultFile.isFile && runCatching { org.json.JSONObject(resultFile.readText()).has("items") }.getOrDefault(false) }
            val receipt = org.json.JSONObject(resultFile.readText())
            val items = receipt.getJSONArray("items")
            for (label in listOf("name", "age")) assertTrue("Missing typed property: $label",
                (0 until items.length()).any { items.getJSONObject(it).optString("label") == label && items.getJSONObject(it).optInt("kind") == 4 })
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommandRw("dd of=/data/local/tmp/m4-developer-host.txt")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(receipt.toString(2).toByteArray()) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            activity.runOnIdle { host.stop() }
            activity.waitUntil(10000) { host.endpoint == null }
            assertFalse("Stop must close the listening socket", runCatching { connection("/login").let { try { it.responseCode } finally { it.disconnect() } } }.isSuccess)
        } finally { activity.runOnIdle { host.stop() } }
    }
}
