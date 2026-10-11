package com.musabgpt.m4.github

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun GitHubPanel(owner: String) {
    val context = LocalContext.current
    val client = remember { GitHubClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(client.status().toString()) }
    var clientId by remember { mutableStateOf(context.getSharedPreferences("m4_github", 0).getString("client_id", "").orEmpty()) }
    var code by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("Musabgpt/M4") }
    var repoOwner by remember { mutableStateOf("Musabgpt") }
    var availableRepositories by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var canceled by remember { mutableStateOf(false) }
    var artifactId by remember { mutableStateOf("") }
    var updateId by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GitHub • M4 / NewAl", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(tokenText, { tokenText = it }, label = { Text("GitHub access token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && tokenText.isNotBlank(), onClick = {
            busy = true
            val supplied = tokenText
            tokenText = ""
            scope.launch {
                try {
                    status = withContext(Dispatchers.IO) { client.connectToken(supplied).toString() }
                } catch (e: Exception) {
                    status = e.message ?: "تعذر ربط GitHub"
                } finally {
                    busy = false
                }
            }
        }) { Text("ربط GitHub مباشرة") }

        if (!client.status().optBoolean("client_configured")) {
            Text("يحتاج المطور client_id لتطبيق OAuth المخصص لـ M4. حساب Codex المتصل هنا لا يرسل اعتماده إلى الهاتف.")
            OutlinedTextField(clientId, { clientId = it }, label = { Text("OAuth client_id (إعداد المطور)") })
            TextButton(onClick = { try { client.setClientId(clientId.trim()); status = client.status().toString() } catch (e: Exception) { status = e.message.orEmpty() } }) { Text("حفظ إعداد التطبيق") }
        }
        Button(enabled = !busy && client.status().optBoolean("client_configured"), onClick = {
            busy = true; canceled = false
            scope.launch {
                try {
                    val device = withContext(Dispatchers.IO) { client.beginDeviceFlow() }
                    code = device.getString("user_code")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device")))
                    status = withContext(Dispatchers.IO) { client.finishDeviceFlow(device) { canceled }.toString() }
                } catch (e: Exception) { status = e.message.orEmpty() }
                finally { busy = false; code = "" }
            }
        }) { Text("ربط الحساب عبر GitHub") }
        if (code.isNotBlank()) Text("رمز التفويض: $code", style = MaterialTheme.typography.headlineMedium)
        if (busy) TextButton(onClick = { canceled = true }) { Text("إلغاء التفويض") }
        Text("استكشاف مستودعات GitHub الحقيقية", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(repoOwner, { repoOwner = it.trim().take(39) },
            label = { Text("اسم حساب GitHub") })
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        client.call("repositories", org.json.JSONObject().put("owner", repoOwner), owner)
                    }
                    val items = result.getJSONArray("data")
                    availableRepositories = (0 until items.length()).map {
                        items.getJSONObject(it).getString("full_name")
                    }
                    status = "تم العثور على " + availableRepositories.size + " مستودع."
                } catch (e: Exception) { status = "تعذّر عرض المستودعات: " + e.message }
                finally { busy = false }
            }
        }) { Text("عرض المستودعات") }
        availableRepositories.forEach { fullName ->
            TextButton(onClick = { repository = fullName }) { Text(fullName) }
        }
        OutlinedTextField(repository, { repository = it }, label = { Text("المستودع") })
        TextButton(enabled = !busy, onClick = { scope.launch {
            try { status = withContext(Dispatchers.IO) { client.call("runs", org.json.JSONObject().put("repository", repository), owner).toString(2) } }
            catch (e: Exception) { status = e.message.orEmpty() }
        } }) { Text("قراءة نتائج Actions الفعلية") }
        OutlinedTextField(artifactId, { artifactId = it }, label = { Text("رقم مرفق APK في Actions") })
        TextButton(enabled = !busy && artifactId.toLongOrNull() != null, onClick = { busy = true; scope.launch {
            try { val row = withContext(Dispatchers.IO) { client.call("artifact_download", org.json.JSONObject().put("repository", repository).put("id", artifactId.toLong()), owner) }; updateId = row.getString("update_id"); status = row.toString(2) }
            catch (e: Exception) { status = e.message.orEmpty() }
            finally { busy = false }
        } }) { Text("تنزيل APK والتحقق من التوقيع والبصمة") }
        if (updateId.isNotBlank()) {
            TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
                try { status = withContext(Dispatchers.IO) { client.call("update_install", org.json.JSONObject().put("update_id", updateId), owner).toString(2) } }
                catch (e: Exception) { status = e.message.orEmpty() }
                finally { busy = false }
            } }) { Text("مراجعة التحديث وفتح مثبت Android") }
            TextButton(onClick = { scope.launch { try { status = withContext(Dispatchers.IO) { client.call("update_status", org.json.JSONObject().put("update_id", updateId), owner).toString(2) } } catch (e: Exception) { status = e.message.orEmpty() } } }) { Text("نتيجة التثبيت الفعلية") }
        }
        TextButton(onClick = { client.disconnect(); status = client.status().toString() }) { Text("حذف الاتصال من هذا الهاتف") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/applications"))) }) { Text("مراجعة تفويض الحساب وإلغاؤه في GitHub") }
    }
    DisposableEffect(Unit) { onDispose { canceled = true } }
}
