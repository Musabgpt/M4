package com.musabgpt.m4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Main Compose screen owns the interactive terminal sheet as part of the app. */
class M4ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatTheme {
                ChatScreen(readAgentProgress = { AgentProgressStore.read(this) })
            }
        }
    }
}
