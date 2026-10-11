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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText

@RunWith(AndroidJUnit4::class)
class MergedRuntimeInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<M4ChatActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = "qa-" + UUID.randomUUID().toString()
    private fun evidence(name: String, text: String) {
        File(context.filesDir, "qa").apply { mkdirs() }.resolve(name).writeText(text)
        android.util.Log.i("M4QAReceipt", name + " " + text.take(3500))
    }

    @Test fun chatActionSheetExposesMediaAndTermuxWithoutArabicPty() {
        activity.onNodeWithTag("open-github").assertExists()
        activity.onNodeWithTag("chat-plus").performClick()
        activity.onNodeWithText("الكاميرا").assertExists()
        activity.onNodeWithTag("sheet-skills").assertExists()
        activity.onNodeWithTag("chat-terminal-toggle").performClick()
        activity.onNodeWithText("Termux — محرك M4").assertExists()
    }

    @Test fun doctorOnlyReportsInstalledTermuxWithoutStartingAlpine() {
        lateinit var vm: ChatViewModel
        activity.runOnIdle {
            vm = androidx.lifecycle.ViewModelProvider(activity.activity)[ChatViewModel::class.java]
            vm.newChat()
            vm.send("/doctor")
        }
        val response = vm.conversations.value.first { it.id == vm.currentId.value }.messages.last().content
        assertTrue(response.contains("Termux:"))
        assertTrue(response.contains("RUN_COMMAND"))
        assertFalse(response.contains("Alpine Linux"))
    }

    @Test fun unavailableTermuxCannotBeReportedAsSuccessfulScheduledWork() {
        val status = com.musabgpt.m4.terminal.TermuxClient.status(context)
        if (status.optBoolean("installed")) return // Device integration tested separately.
        val db = TaskDatabase.get(context)
        val task = db.create("termux absence", "printf 'M4_SHOULD_NOT_RUN'", owner, "once", 1, true)
        try {
            com.musabgpt.m4.scheduler.TaskScheduler.runNow(context, task)
            activity.waitUntil(90000) {
                db.lastRun(task.id) != "لم تُنفذ المهمة بعد"
            }
            val receipt = db.lastRun(task.id)
            assertFalse("Termux is missing; do not claim successful execution: " + receipt,
                receipt.startsWith("succeeded"))
            evidence("termux-scheduler-missing.json", JSONObject().put("receipt", receipt).toString(2))
        } finally {
            runCatching { com.musabgpt.m4.scheduler.TaskScheduler.pause(context, task.id) }
            runCatching { db.delete(task.id) }
        }
    }

    @Test fun visibleTermuxPanelNeverPretendsMissingTermuxExecutedACommand() {
        val status = com.musabgpt.m4.terminal.TermuxClient.status(context)
        if (status.optBoolean("installed")) return
        activity.onNodeWithTag("chat-plus").performClick()
        activity.onNodeWithTag("chat-terminal-toggle").performClick()
        activity.onNodeWithText("Termux — محرك M4").assertExists()
        activity.onNodeWithText("نفّذ في Termux").performClick()
        activity.waitUntil(25000) {
            activity.onAllNodesWithText("Termux غير مثبت", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun removedArabicTerminalCannotBeLaunchedOrOverlaid() {
        val component = android.content.ComponentName(context,
            io.musab.arabicterminal.TerminalService::class.java)
        val registered = runCatching {
            context.packageManager.getServiceInfo(component, 0)
        }.isSuccess
        assertFalse("Retired native PTY service must not be in the manifest", registered)
        val oldActivity = android.content.ComponentName(context, io.musab.arabicterminal.MainActivity::class.java)
        assertFalse("Retired Arabic terminal activity must not be registered",
            runCatching { context.packageManager.getActivityInfo(oldActivity, 0) }.isSuccess)
    }

    @Test fun missingTermuxIsReportedWithoutFallingBackToModelGateways() {
        if (com.musabgpt.m4.terminal.TermuxClient.status(context).optBoolean("installed")) return
        lateinit var vm: ChatViewModel
        activity.runOnIdle {
            vm = androidx.lifecycle.ViewModelProvider(activity.activity)[ChatViewModel::class.java]
            vm.newChat()
        }
        activity.onNodeWithTag("chat-input").performTextInput("مرحبا")
        activity.onNodeWithTag("chat-send").performClick()
        activity.waitUntil(25000) {
            !vm.streaming.value && vm.conversations.value.first { it.id == vm.currentId.value }
                .messages.last().content.isNotBlank()
        }
        val reply = vm.conversations.value.first { it.id == vm.currentId.value }.messages.last().content
        assertTrue(reply, reply.contains("Termux"))
        assertFalse(reply.contains("No available gateway"))
        assertFalse(reply.contains("provider_fallback"))
    }
    @Test fun termuxPendingIntentContractAcceptsActualResultOkMinusOne() {
        val commandId = UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "termux-results/" + commandId).apply { mkdirs() }
        File(folder, "request.json").writeText(
            JSONObject().put("owner", owner).put("nonce", nonce)
                .put("command", JSONObject().put("executable", "python")).toString()
        )
        try {
            val result = android.os.Bundle().apply {
                putInt("err", android.app.Activity.RESULT_OK)
                putInt("exitCode", 0)
                putString("stdout", "M4_TERMUX_OK")
                putString("stderr", "")
            }
            val incoming = android.content.Intent(context,
                com.musabgpt.m4.terminal.TermuxResultReceiver::class.java)
                .setData(android.net.Uri.parse("m4-termux://result/$commandId/$nonce"))
                .putExtra("result", result)
            com.musabgpt.m4.terminal.TermuxClient.receive(context, incoming)
            val receipt = com.musabgpt.m4.terminal.TermuxClient.output(context, owner, commandId)
            assertEquals("succeeded", receipt.getString("status"))
            assertEquals(0, receipt.getInt("exit_code"))
            assertEquals("M4_TERMUX_OK", receipt.getString("stdout"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun termuxPendingIntentMustRejectMissingOrErroneousReturnCode() {
        listOf(0, 17).forEach { badErr ->
            val commandId = UUID.randomUUID().toString()
            val nonce = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "termux-results/" + commandId).apply { mkdirs() }
            File(folder, "request.json").writeText(JSONObject()
                .put("owner", owner).put("nonce", nonce).put("command", JSONObject()).toString())
            try {
                val result = android.os.Bundle().apply {
                    putInt("err", badErr)
                    putInt("exitCode", 0)
                    putString("stdout", "UNTRUSTWORTHY")
                }
                com.musabgpt.m4.terminal.TermuxClient.receive(
                    context,
                    android.content.Intent(context,
                        com.musabgpt.m4.terminal.TermuxResultReceiver::class.java)
                        .setData(android.net.Uri.parse("m4-termux://result/$commandId/$nonce"))
                        .putExtra("result", result)
                )
                assertEquals("blocked",
                    com.musabgpt.m4.terminal.TermuxClient.output(context, owner, commandId)
                        .getString("status"))
            } finally {
                folder.deleteRecursively()
            }
        }
    }

    @Test fun youtubeCompositeIntentParsesWithoutLlmOrToolGateway() {
        val item = com.musabgpt.m4.phone.YouTubeActions.parse(
            "افتح يوتيوب وابحث عن مسلسل باب الحارة وشغلي الفيديو"
        )
        assertNotNull(item)
        assertEquals("مسلسل باب الحارة", item!!.query)
        assertTrue(item.play)
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
