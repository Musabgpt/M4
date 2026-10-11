package com.musabgpt.m4.runtime

import android.os.Handler
import android.os.Looper

object AgentControl {
    @Volatile var onStop: (() -> Unit)? = null
    @JvmStatic fun stop() { CapabilityBroker.cancel(); Handler(Looper.getMainLooper()).post { onStop?.invoke() } }
}
