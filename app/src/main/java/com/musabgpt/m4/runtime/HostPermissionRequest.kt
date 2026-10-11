package com.musabgpt.m4.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object HostPermissionRequest {
    private val state = MutableStateFlow<String?>(null)
    val pending = state.asStateFlow()
    private var result: CompletableFuture<Boolean>? = null
    @Synchronized fun resolve(granted: Boolean) { result?.complete(granted) }
    fun request(permission: String): Boolean {
        val future = synchronized(this) { if (result != null) return false; CompletableFuture<Boolean>().also { result = it; state.value = permission } }
        return try { future.get(60, TimeUnit.SECONDS) } catch (_: Exception) { false }
        finally { synchronized(this) { state.value = null; result = null } }
    }
}
