package com.musabgpt.m4

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.browser.BrowserController
import com.musabgpt.m4.phone.AndroidPhoneTools
import com.musabgpt.m4.phone.PhoneAccessibilityTools
import com.musabgpt.m4.scheduler.TaskDatabase
import io.musab.arabicterminal.LinuxEnvironment
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText

@RunWith(AndroidJUnit4::class)
class MergedRuntimeInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<M4ChatActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = "qa-" + UUID.randomUUID().toString()
    private fun evidence(name: String, text: String) { File(context.filesDir, "qa").apply { mkdirs() }.resolve(name).writeText(text) }

    @Test fun chatActionSheetExposesActualMediaAndInlineTerminal() {
        activity.onNodeWithTag("chat-plus").performClick()
        activity.onNodeWithTag("chat-terminal-toggle").assertExists()
        activity.onNodeWithText("الكاميرا").assertExists()
        activity.onNodeWithText("المهارات والإضافات").assertExists()
    }

    @Test fun firstMessageUsesRealPackagedPythonThroughTheVisibleSendButton() {
        // Only the external model is replaced. Installation, checkout, native
        // PTY/PRoot, packaged Python, request/events/response and UI are real.
        val server = java.net.ServerSocket(1337, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 180000
        val received = java.util.concurrent.atomic.AtomicReference<String>()
        val fixture = Thread {
            runCatching { server.accept().use { socket ->
                socket.soTimeout = 15000
                val input = java.io.DataInputStream(socket.getInputStream())
                var length = 0
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                require(length in 1..1000000)
                val bytes = ByteArray(length); input.readFully(bytes); received.set(String(bytes, Charsets.UTF_8))
                val body = """{"model":"qa-protocol-fixture","choices":[{"message":{"role":"assistant","content":"مرحباً، المحادثة جاهزة."}}]}""".toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(body); output.flush()
            } }
        }.apply { isDaemon = true; start() }
        lateinit var vm: ChatViewModel
        activity.runOnIdle { vm = androidx.lifecycle.ViewModelProvider(activity.activity)[ChatViewModel::class.java]; vm.newChat(); vm.selectProvider(1) }
        try {
            activity.onNodeWithTag("chat-input").performTextInput("مرحبا")
            activity.onNodeWithTag("chat-send").performClick()
            try {
                activity.waitUntil(180000) {
                    !vm.streaming.value && vm.conversations.value.first { it.id == vm.currentId.value }.messages.any { it.role == "assistant" }
                }
            } catch (failure: Throwable) {
                val messages = vm.conversations.value.first { it.id == vm.currentId.value }.messages
                val diagnostics = JSONObject()
                    .put("streaming", vm.streaming.value)
                    .put("runtime", vm.terminal.state.value.status)
                    .put("message_roles", org.json.JSONArray(messages.map { it.role }))
                    .put("assistant_count", messages.count { it.role == "assistant" })
                    .put("fixture_received_request", received.get() != null)
                    .put("alpine_installed", LinuxEnvironment.installed(context))
                    .put("project_present", File(LinuxEnvironment.rootfs(context), "root/projects/M4/.git").isDirectory)
                    .put("exception", failure.javaClass.simpleName)
                evidence("first-message-failure.json", diagnostics.toString(2))
                throw failure
            }
            val messages = vm.conversations.value.first { it.id == vm.currentId.value }.messages
            val reply = messages.last { it.role == "assistant" }.content
            evidence("first-message.json", JSONObject().put("reply", reply).put("runtime", vm.terminal.state.value.status).put("model_request_received", received.get() != null).toString(2))
            assertEquals("مرحباً، المحادثة جاهزة.", reply)
            assertTrue(received.get().contains("مرحبا"))
            assertTrue(File(LinuxEnvironment.rootfs(context), "root/projects/M4/.git").isDirectory)
        } finally { server.close(); fixture.join(1000) }
    }

    @Test fun ownedHtmlIsClickedTypedAssertedAndCaptured() {
        val html = """<!doctype html><html lang="ar" dir="rtl"><style>body{background:white;color:black;font-size:24px}button,input{font-size:24px}</style><h1>اختبار M4</h1><input id="name"><button id="go" onclick="document.querySelector('#result').innerText='مرحبا '+document.querySelector('#name').value">تشغيل</button><p id="result">انتظار</p></html>"""
        val row = ArtifactRepository.saveText(context, owner, "qa.html", html)
        val browser = BrowserController.current ?: error("Owned WebView unavailable")
        assertEquals("loaded", browser.call("open_artifact", JSONObject().put("id", row.id), owner).getString("status"))
        assertEquals("dispatched", browser.call("type", JSONObject().put("selector", "#name").put("text", "مصعب"), owner).getString("status"))
        assertEquals("dispatched", browser.call("click", JSONObject().put("selector", "#go"), owner).getString("status"))
        val asserted = browser.call("wait_for", JSONObject().put("selector", "#result").put("text", "مرحبا مصعب"), owner)
        assertEquals("verified", asserted.getString("status"))
        assertTrue(browser.call("read_dom", JSONObject(), owner).getString("text").contains("مرحبا مصعب"))
        val shot = browser.call("screenshot", JSONObject().put("name", "qa-browser.png"), owner)
        assertEquals("verified", shot.getString("status"))
        val file = ArtifactRepository.file(context, shot.getString("artifact_id"), "qa-browser.png")
        file.copyTo(File(context.filesDir, "qa/browser.png").apply { parentFile!!.mkdirs() }, overwrite = true)
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        assertTrue(bitmap.width > 200 && bitmap.height > 200)
        var nonWhite = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) { val c = bitmap.getPixel(x,y); if (Color.alpha(c) > 0 && Color.red(c) < 200) nonWhite++ }
        bitmap.recycle(); assertTrue("Screenshot is blank", nonWhite > 30)
        evidence("browser.json", asserted.toString(2))
    }

    @Test fun arabicPdfContainsRenderedPagesAndImmutableBytes() {
        val id = UUID.randomUUID().toString().replace("-", "")
        val folder = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/artifacts/$id").apply { mkdirs() }
        try {
            val receipt = ArtifactRepository.createPdf(context, JSONObject().put("id", id).put("name", "arabic.pdf").put("content", "مرحبا مصعب\nتقرير الاختبار باللغة العربية\n١٢٣٤٥"))
            assertTrue(receipt.getInt("pages") > 0)
            val pdf = File(folder, "arabic.pdf")
            assertEquals(receipt.getString("sha256"), ArtifactRepository.hash(pdf))
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer -> renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var ink = 0
                for (y in 0 until 300) for (x in 0 until 595) if (Color.red(bitmap.getPixel(x,y)) < 160) ink++
                assertTrue("Arabic PDF rendered empty", ink > 100)
                File(context.filesDir, "qa/pdf.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            } } }
            val artifact = ArtifactRepository.saveBinary(context, owner, "qa.pdf", "application/pdf", pdf.readBytes())
            assertEquals("verified", ArtifactRepository.inspect(context, owner, artifact.id).getString("status"))
            assertThrows(IllegalStateException::class.java) { ArtifactRepository.inspect(context, "another-owner", artifact.id) }
            ArtifactRepository.file(context, artifact.id, artifact.name).appendText("tampered")
            assertThrows(IllegalArgumentException::class.java) { ArtifactRepository.file(context, artifact.id, artifact.name) }
            evidence("pdf.json", receipt.toString(2))
        } finally { folder.deleteRecursively() }
    }

    @Test fun disabledTaskSurvivesDatabaseReadsAndCloneHasNoGrant() {
        val db = TaskDatabase.get(context)
        val task = db.create("qa recurring", "python3 -V", owner, "weekly", 5, false)
        assertFalse(db.find(task.id)!!.enabled)
        assertTrue(db.list("another-owner").none { it.id == task.id })
        val clone = db.clone(task); assertFalse(clone.enabled); assertNotEquals(clone.id, task.id)
        assertEquals("لم تُنفذ المهمة بعد", db.lastRun(task.id))
        db.delete(task.id); db.delete(clone.id)
        evidence("scheduler.json", task.json().toString(2))
    }

    @Test fun launcherUsesAndroidAndReadOnlyPhoneNeverRequestsSetup() {
        val apps = AndroidPhoneTools.listApps(context).getJSONArray("apps")
        assertTrue(apps.length() > 0)
        val missing = AndroidPhoneTools.openApp(context, "com.m4.qa.not_installed")
        assertEquals("unavailable", missing.getString("status"))
        val screen = PhoneAccessibilityTools.dispatch(context, "screen", JSONObject(), owner, allowSetup = false)
        assertEquals("permission_required", screen.getString("status"))
        assertNull(com.musabgpt.m4.runtime.CapabilityBroker.pending.value)
        evidence("capabilities.json", JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("abi", android.os.Build.SUPPORTED_ABIS[0])
            .put("phone_permission", screen).put("alpine_runtime", if (LinuxEnvironment.runtimeAvailable(context)) "available_not_executed" else "BLOCKED: ARM64 runtime unavailable on this emulator").toString(2))
    }
    @Test fun developmentApkCannotMasqueradeAsOriginalSignedUpdate() {
        val file = File(context.applicationInfo.sourceDir)
        assertThrows(IllegalArgumentException::class.java) { com.musabgpt.m4.github.ApkUpdates.validate(context, file) }
        evidence("signing.json", JSONObject().put("result", "PASS").put("assertion", "Installed development APK rejected as an original NewAl update").toString(2))
    }
}
