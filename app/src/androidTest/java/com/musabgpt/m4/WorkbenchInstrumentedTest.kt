package com.musabgpt.m4

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musabgpt.m4.runtime.EditorDocument
import com.musabgpt.m4.runtime.WorkspaceEditorState
import com.musabgpt.m4.artifacts.ArtifactRepository
import io.musab.arabicterminal.LinuxEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkbenchInstrumentedTest {
    @get:Rule val ui = createAndroidComposeRule<M4ChatActivity>()

    private fun awaitWorkspace() {
        ui.waitUntil(180000) {
            var ready = false
            ui.runOnIdle { ready = ViewModelProvider(ui.activity)[WorkspaceEditorState::class.java].ready }
            ready
        }
    }

    @Test fun initialExplorerBootstrapsWithoutSendingChatAndNavigationIsVisible() {
        awaitWorkspace()
        ui.onNodeWithTag("workspace-ready").assertIsDisplayed()
        ui.onNodeWithTag("workbench-commands").assertIsDisplayed()
        ui.onNodeWithTag("workbench-files").assertIsDisplayed()
        ui.onNodeWithTag("workbench-chat").assertIsDisplayed()
        ui.onNodeWithTag("workbench-tools").assertIsDisplayed()
        val root = File(LinuxEnvironment.rootfs(ui.activity), "root/projects/M4")
        assertTrue(File(root, ".git").isDirectory)
        assertTrue(File(root, "agent/agent.py").isFile)
        val screen = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val evidence = File(ui.activity.filesDir, "qa").apply { mkdirs() }
        File(evidence, "workbench-first-open.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screen.recycle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/m4-workbench-first-open.png").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        ui.onNodeWithTag("workbench-chat").performClick()
        ui.onNodeWithTag("chat-input").assertIsDisplayed()
        ui.onNodeWithTag("workbench-tools").performClick()
        ui.onNodeWithText("GitHub").assertIsDisplayed()
    }

    @Test fun dirtyBufferSurvivesAgentNavigationAndOnlyReviewSaves() {
        awaitWorkspace()
        val file = File(LinuxEnvironment.rootfs(ui.activity), "root/projects/M4/workbench-qa.txt")
        file.parentFile!!.mkdirs()
        file.writeText("before")
        try {
            ui.runOnIdle {
                val state = ViewModelProvider(ui.activity)[WorkspaceEditorState::class.java]
                val document = EditorDocument(file, "before", ArtifactRepository.hash(file))
                state.documents.add(document)
                state.active = document
            }
            ui.onNodeWithTag("workbench-files").performClick()
            ui.onNodeWithTag("code-editor").performTextReplacement("after")
            ui.onNodeWithTag("workbench-chat").performClick()
            ui.onNodeWithTag("workbench-files").performClick()
            ui.onNodeWithTag("code-editor").assertTextEquals("after")
            assertEquals("before", file.readText())
            ui.onNodeWithText("Review").performClick()
            ui.onNodeWithText("− before").assertExists()
            ui.onNodeWithText("+ after").assertExists()
            ui.onNodeWithText("حفظ مع نسخة احتياطية").performClick()
            ui.waitUntil(10000) { file.readText() == "after" }
        } finally { file.delete() }
    }

    @Test fun inlineProposalChangesOnlyBufferAfterAcceptance() {
        awaitWorkspace()
        val file = File(LinuxEnvironment.rootfs(ui.activity), "root/projects/M4/inline-qa.txt")
        file.writeText("before")
        val server = java.net.ServerSocket(1337, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 30000
        val received = java.util.concurrent.atomic.AtomicReference<String>()
        val fixture = Thread {
            runCatching { server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = java.io.DataInputStream(socket.getInputStream())
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                require(length in 1..100000)
                val bytes = ByteArray(length); input.readFully(bytes); received.set(String(bytes, Charsets.UTF_8))
                val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(
                    org.json.JSONObject().put("message", org.json.JSONObject().put("content", "```text\nafter\n```"))
                )).toString().toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            ui.runOnIdle {
                val state = ViewModelProvider(ui.activity)[WorkspaceEditorState::class.java]
                state.active = EditorDocument(file, "before", ArtifactRepository.hash(file)).also { state.documents.add(it) }
                val chat = ViewModelProvider(ui.activity)[ChatViewModel::class.java]
                chat.selectProvider(chat.providers.value.indexOfFirst { it.id == "gpt4free" })
            }
            ui.onNodeWithText("Edit with AI").performScrollTo().performClick()
            ui.onNodeWithTag("inline-instruction").performTextInput("Replace before with after")
            ui.onNodeWithText("اقتراح تعديل").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("قبول في المحرر").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("before", file.readText())
            assertTrue(received.get().contains("before"))
            ui.onNodeWithText("قبول في المحرر").performClick()
            ui.onNodeWithTag("code-editor").assertTextEquals("after")
            assertEquals("before", file.readText())
        } finally { server.close(); fixture.join(1000); file.delete() }
    }
}
