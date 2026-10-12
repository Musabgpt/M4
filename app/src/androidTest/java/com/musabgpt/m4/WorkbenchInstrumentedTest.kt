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
}
