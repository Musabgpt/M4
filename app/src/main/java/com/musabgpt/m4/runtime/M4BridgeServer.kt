package com.musabgpt.m4.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.scheduler.TaskScheduler
import io.musab.arabicterminal.LinuxEnvironment
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Capability discovery and narrowly scoped actions over an authenticated private socket. */
class M4BridgeServer(private val context: Context) : Closeable {
    private val directory = File(LinuxEnvironment.rootfs(context), "root/.m4/run")
    private val socketFile = File(directory, "bridge.sock")
    private val config = File(directory, "bridge.json")
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var socket: LocalSocket? = null
    private var server: LocalServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    fun start() {
        directory.mkdirs()
        socketFile.delete()
        val bound = LocalSocket()
        bound.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = bound
        server = LocalServerSocket(bound.fileDescriptor)
        config.writeText(JSONObject().put("token", token).put("socket", "/root/.m4/run/bridge.sock").toString())
        config.setReadable(false, false); config.setWritable(false, false)
        config.setReadable(true, true); config.setWritable(true, true)
        executor.execute {
            while (!closed) {
                try { server?.accept()?.use { client -> handle(client) } }
                catch (_: Exception) { if (closed) break }
            }
        }
    }

    private fun handle(client: LocalSocket) {
        client.soTimeout = 30000
        val response = try {
            require(client.peerCredentials.uid == Process.myUid()) { "Unauthorized peer" }
            val buffer = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = client.inputStream.read()
                if (byte < 0 || byte == 10) break
                require(buffer.size() < 1000000) { "Request too large" }
                buffer.write(byte)
            }
            val request = JSONObject(buffer.toString("UTF-8"))
            require(MessageDigest.isEqual(token.toByteArray(), request.optString("token").toByteArray())) { "Unauthorized request" }
            val arguments = request.optJSONObject("arguments") ?: JSONObject()
            val result = when (request.getString("action")) {
                "capabilities" -> JSONObject().put("artifact_pdf", true).put("scheduler", true)
                    .put("phone", false).put("browser", false).put("termux", false)
                "artifact_pdf" -> ArtifactRepository.createPdf(context, arguments)
                "schedule_create" -> TaskScheduler.requestApproval(context, arguments)
                else -> error("Unregistered bridge action")
            }
            JSONObject().put("ok", true).put("result", result)
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message?.take(240) ?: "Bridge failure") }
        client.outputStream.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
        client.outputStream.flush()
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { socket?.close() }
        executor.shutdownNow()
        config.delete(); socketFile.delete()
    }
}
