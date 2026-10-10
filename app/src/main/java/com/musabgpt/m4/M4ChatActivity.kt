package com.musabgpt.m4

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.musab.arabicterminal.MainActivity

/** Compose chat is the launcher; the original fully functional Term remains available. */
class M4ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatTheme {
                ChatScreen(
                    openTerminal = {
                        startActivity(Intent(this, MainActivity::class.java))
                    },
                    connectGithub = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_GITHUB_LOGIN,true))
                    },
                    runAgent = { goal ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_AGENT_GOAL, goal))
                    },
                    readAgentProgress = {
                        AgentProgressStore.read(this)
                    }
                )
            }
        }
    }
}
