package com.musabgpt.m4.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class PendingApproval(val id: String, val owner: String, val action: String, val details: String)

/** Decisions belong to the user and a concrete action, never to model prose. */
object CapabilityBroker {
    private val mutable = MutableStateFlow<PendingApproval?>(null)
    val pending = mutable.asStateFlow()
    private val lock = Any()
    private var decision: CompletableFuture<Boolean>? = null
    private val scopes = mutableSetOf<String>()
    private fun scope(owner: String, action: String, details: String) = owner + ":" + action + ":" + java.security.MessageDigest.getInstance("SHA-256").digest(details.toByteArray()).joinToString("") { "%02x".format(it) }

    fun request(owner: String, action: String, arguments: JSONObject): Boolean {
        val future = CompletableFuture<Boolean>()
        val approval = PendingApproval(UUID.randomUUID().toString(), owner, action, arguments.toString().take(16000))
        synchronized(lock) {
            if (scope(owner, action, approval.details) in scopes) return true
            if (decision != null) return false
            decision = future
            mutable.value = approval
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            io.musab.arabicterminal.UiControlService.current()?.showApproval(approval.id, approval.action, approval.details)
        }
        return try { future.get(120, TimeUnit.SECONDS) } catch (_: Exception) { false }
        finally {
            synchronized(lock) { if (decision === future) { decision = null; mutable.value = null } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { io.musab.arabicterminal.UiControlService.current()?.hideApproval(approval.id) }
        }
    }

    @JvmStatic fun resolve(id: String, allow: Boolean, scoped: Boolean = false) = synchronized(lock) {
        val request = mutable.value
        if (request?.id == id) {
            if (allow && scoped) scopes.add(scope(request.owner, request.action, request.details))
            decision?.complete(allow)
        }
        Unit
    }
    fun revoke(owner: String) = synchronized(lock) { scopes.removeAll { it.startsWith(owner + ":") }; Unit }
    fun cancel() = synchronized(lock) { decision?.complete(false); Unit }

    fun explicitGoal(context: Context, owner: String, turn: String): String? {
        if (!owner.matches(Regex("[A-Za-z0-9-]{1,80}")) || !turn.matches(Regex("[A-Za-z0-9-]{1,80}"))) return null
        return try {
            val file = java.io.File(io.musab.arabicterminal.LinuxEnvironment.rootfs(context), "root/.m4/chats/$owner/$turn/request.json")
            if (!file.isFile || file.length() > 500000) null
            else JSONObject(file.readText()).getJSONArray("messages").let { messages ->
                messages.getJSONObject(messages.length() - 1).getString("content")
            }
        } catch (_: Exception) { null }
    }
}
