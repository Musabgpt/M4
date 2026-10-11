package com.musabgpt.m4.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GitLabPanel(owner: String) {
    val context = LocalContext.current
    val client = remember { GitLabClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(context.getSharedPreferences("m4_gitlab", 0).getString("host", "https://gitlab.com").orEmpty()) }
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(client.status().toString()) }
    var busy by remember { mutableStateOf(false) }

    fun call(action: String, args: JSONObject = JSONObject()) {
        busy = true
        scope.launch {
            try { status = withContext(Dispatchers.IO) { client.call(action, args.put("project", project), owner).toString(2) } }
            catch (e: Exception) { status = e.message.orEmpty() }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GitLab • Cursor", style = MaterialTheme.typography.titleLarge)
        Text("المفتاح يُحفظ داخل Android Keystore ولا يصل إلى النموذج.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("خادم GitLab") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(username, { username = it }, label = { Text("اسم الحساب اختياري") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && token.isNotBlank(), onClick = {
            try { client.configureHost(host); client.connect(token, username); token = ""; status = client.status().put("status", "connected").toString(2) }
            catch (e: Exception) { status = e.message.orEmpty() }
        }) { Text("ربط GitLab") }
        TextButton(enabled = !busy, onClick = { client.disconnect(); status = client.status().put("status", "disconnected").toString(2) }) { Text("فصل GitLab") }
        HorizontalDivider()
        OutlinedTextField(project, { project = it }, label = { Text("المشروع: group/project أو project ID") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = !busy, onClick = { call("projects") }) { Text("المشاريع") }
            TextButton(enabled = !busy && project.isNotBlank(), onClick = { call("issues") }) { Text("Issues") }
            TextButton(enabled = !busy && project.isNotBlank(), onClick = { call("merge_requests") }) { Text("Merge Requests") }
            TextButton(enabled = !busy && project.isNotBlank(), onClick = { call("pipelines") }) { Text("Pipelines") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
