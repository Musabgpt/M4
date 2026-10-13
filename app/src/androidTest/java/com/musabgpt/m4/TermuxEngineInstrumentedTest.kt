package com.musabgpt.m4

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.musabgpt.m4.runtime.CapabilityBroker
import com.musabgpt.m4.terminal.TermuxExecutionBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in network test using the official Termux APK, not a substitute service. */
class TermuxEngineInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<M4ChatActivity>()
    @Test fun officialRunCommandStartsPersistentPtyAndFilesystemMcp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("termuxEngine") == "true")
        val context = activity.activity.applicationContext
        val owner = "termux-qa-" + UUID.randomUUID().toString()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} com.termux.permission.RUN_COMMAND").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        val done = AtomicBoolean(false)
        val consent = Thread {
            while (!done.get()) {
                CapabilityBroker.pending.value?.takeIf { it.owner == owner && it.action == "تفعيل محرك Termux للوكيل" }?.let {
                    CapabilityBroker.resolve(it.id, true)
                }
                Thread.sleep(50)
            }
        }.apply { isDaemon = true; start() }
        fun call(action: String, args: JSONObject = JSONObject()) = TermuxExecutionBridge.call(context, owner, action, args)
        fun poll(initial: JSONObject, predicate: (JSONObject) -> Boolean): JSONObject {
            var row = initial
            val end = System.nanoTime() + 30_000_000_000L
            while (!predicate(row) && System.nanoTime() < end) {
                row = call("read_output", JSONObject().put("session_id", row.getString("session_id")).put("yield_time_ms", 500))
            }
            assertTrue(row.toString(), predicate(row)); return row
        }
        try {
            val starting = TermuxExecutionBridge.setup(context, owner)
            assertTrue(starting.toString(), starting.optString("status") in setOf("starting", "ready"))
            var observed = JSONObject()
            val deadline = System.nanoTime() + 900_000_000_000L
            do {
                observed = TermuxExecutionBridge.status(context, owner)
                if (observed.optString("status") in setOf("ready", "failed")) break
                Thread.sleep(1500)
            } while (System.nanoTime() < deadline)
            assertEquals(observed.toString(), "ready", observed.optString("status"))
            var first = call("exec_command", JSONObject().put("command", "printf ready; read answer; printf 'reply:%s' \"\$answer\"; printf warning >&2; exit 7").put("yield_time_ms", 1000))
            first = poll(first) { it.optString("stdout").contains("ready") }
            assertTrue(first.toString(), first.isNull("exit_code"))
            val finished = call("write_stdin", JSONObject().put("session_id", first.getString("session_id")).put("chars", "M4\n").put("cursor", first.getInt("next_cursor")).put("yield_time_ms", 1000))
            assertEquals(finished.toString(), 7, finished.getInt("exit_code"))
            assertEquals("reply:M4", finished.getString("stdout")); assertEquals("warning", finished.getString("stderr"))
            var pty = call("exec_command", JSONObject().put("command", "test -t 0 && printf pty-ok; exec sleep 60").put("tty", true).put("yield_time_ms", 1000))
            pty = poll(pty) { it.optString("stdout").contains("pty-ok") }
            assertTrue(pty.toString(), pty.getString("stdout").contains("pty-ok"))
            val stopped = call("terminate_process", JSONObject().put("session_id", pty.getString("session_id")))
            assertFalse(stopped.toString(), stopped.isNull("exit_code"))
            val tools = call("filesystem_tools").getJSONArray("tools")
            assertTrue((0 until tools.length()).any { tools.getJSONObject(it).getString("name") == "edit_file" })
            val path = "m4-qa-${UUID.randomUUID()}.txt"
            val written = call("filesystem_call", JSONObject().put("name", "write_file").put("arguments", JSONObject().put("path", path).put("content", "M4 actual Android MCP")))
            assertFalse(written.toString(), written.optBoolean("isError"))
            val read = call("filesystem_call", JSONObject().put("name", "read_text_file").put("arguments", JSONObject().put("path", path)))
            assertTrue(read.toString(), read.toString().contains("M4 actual Android MCP"))
            var versions = call("exec_command", JSONObject().put("command", "git --version && gh --version && node --version").put("yield_time_ms", 1000))
            versions = poll(versions) { !it.isNull("exit_code") }
            assertEquals(versions.toString(), 0, versions.getInt("exit_code"))
            assertTrue(versions.getString("stdout").contains("gh version"))
            android.util.Log.i("M4QA", "Official Termux RUN_COMMAND, persistent input, PTY, process stop, git/gh and official Filesystem MCP passed")
        } finally {
            done.set(true); consent.join(1000)
            runCatching { call("close_owner") }
        }
    }
}
