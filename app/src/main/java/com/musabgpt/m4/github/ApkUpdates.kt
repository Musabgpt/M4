package com.musabgpt.m4.github

import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.android.apksig.ApkVerifier
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.runtime.CapabilityBroker
import io.musab.arabicterminal.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** No silent install, downgrade, new signing key, or mutable main branch. */
object ApkUpdates {
    private fun root(context: Context) = File(context.filesDir, "verified-updates").apply { mkdirs() }
    private const val PACKAGE = "dev.newal.code.lite.connectors"
    private fun packageVersion(info: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun validate(context: Context, file: File): JSONObject {
        val result = ApkVerifier.Builder(file).build().verify()
        require(result.isVerified) { "توقيع APK غير صالح" }
        val certificates = result.signerCertificates.map { digest(it.encoded) }
        require(certificates.size == 1 && certificates.single() == BuildConfig.ORIGINAL_CERT_SHA256) { "شهادة APK تختلف عن توقيع NewAl الأصلي؛ رُفض التحديث" }
        val incoming = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("APK غير قابل للقراءة")
        require(incoming.packageName == PACKAGE) { "حزمة التحديث غير مطابقة" }
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE, signatureFlags()) }.getOrNull()
        if (installed != null) {
            val signers = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            require(signers?.map { digest(it.toByteArray()) } == certificates) { "التطبيق المثبت يحمل شهادة أخرى" }
            require(packageVersion(incoming) > packageVersion(installed)) { "versionCode يجب أن يكون أكبر من النسخة المثبتة" }
        }
        return JSONObject().put("package", incoming.packageName).put("version_code", packageVersion(incoming)).put("version_name", incoming.versionName)
            .put("certificate_sha256", certificates.single()).put("sha256", ArtifactRepository.hash(file)).put("status", "verified")
    }
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun stage(context: Context, archive: File, owner: String, source: JSONObject): JSONObject {
        require(root(context).listFiles().orEmpty().count { it.isDirectory } < 3) { "يوجد ثلاثة تحديثات محفوظة؛ احذف غير المطلوب منها قبل تنزيل تحديث آخر" }
        val id = UUID.randomUUID().toString(); val folder = File(root(context), id).apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 1000 && entries.all { !it.name.startsWith('/') && !it.name.contains('\\') && it.name.split('/').none { p -> p == ".." } }) { "أرشيف CI غير آمن" }
                val apks = entries.filter { !it.isDirectory && it.name.endsWith(".apk") }
                require(apks.size == 1) { "يلزم APK واحد محدد في مرفق البناء" }
                val apk = apks.single(); require(apk.size in 1..200_000_000)
                val checksum = entries.firstOrNull { it.name == apk.name + ".sha256" } ?: error("بصمة APK مفقودة من نتيجة CI")
                require(checksum.size in 64..4096)
                val expected = zip.getInputStream(checksum).bufferedReader().use { it.readText() }.trim().substringBefore(' ').lowercase()
                require(expected.matches(Regex("[a-f0-9]{64}")))
                val file = File(folder, "update.apk")
                val crc = java.util.zip.CRC32(); var count = 0L
                zip.getInputStream(apk).use { input -> file.outputStream().use { output -> val bytes = ByteArray(16384); while (true) { val n = input.read(bytes); if (n < 0) break; count += n; require(count <= apk.size); crc.update(bytes,0,n); output.write(bytes,0,n) } } }
                require(count == apk.size && crc.value == apk.crc && ArtifactRepository.hash(file) == expected) { "بصمة/CRC APK غير مطابقة" }
                val row = validate(context, file).put("update_id", id).put("owner", owner).put("source", source)
                File(folder, "manifest.json").writeText(row.toString())
                return row
            }
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
        finally { archive.delete() }
    }
    fun install(context: Context, owner: String, id: String): JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val folder = File(root(context), id); val row = JSONObject(File(folder, "manifest.json").readText())
        require(row.getString("owner") == owner)
        val apk = File(folder, "update.apk")
        require(ArtifactRepository.hash(apk) == row.getString("sha256"))
        validate(context, apk)
        if (!CapabilityBroker.request(owner, "تثبيت تحديث موثّق", row)) return JSONObject().put("status", "denied").put("error", "رفض المستخدم تثبيت APK")
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return row.put("status", "permission_required").put("needs", "install_unknown_apps").put("error", "امنح إذن مصدر التثبيت ثم أعد طلب التحديث؛ لم يُثبت APK")
        }
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(PACKAGE); setSize(apk.length()) }
        val sessionId = installer.createSession(parameters)
        val nonce = UUID.randomUUID().toString()
        row.put("installer_nonce", nonce).put("installer_session", sessionId).put("status", "awaiting_android_confirmation")
        File(folder, "manifest.json").writeText(row.toString())
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk",0,apk.length()).use { output -> apk.inputStream().use { it.copyTo(output) }; session.fsync(output) }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setData(Uri.parse("m4-update://status/$id/$nonce"))
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            session.commit(pending.intentSender)
        }
        return row.apply { remove("installer_nonce") }
    }
    fun receive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 2 || !parts[0].matches(Regex("[a-f0-9-]{36}"))) return
        val manifest = File(root(context), "${parts[0]}/manifest.json")
        val row = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return
        if (row.optString("installer_nonce") != parts[1]) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
        } else {
            row.put("status", if (status == PackageInstaller.STATUS_SUCCESS) "installer_reported_success" else "failed")
                .put("installer_status", status).put("message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(1000))
            manifest.writeText(row.toString())
        }
    }
    fun status(context: Context, owner: String, id: String): JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val row = JSONObject(File(root(context), "$id/manifest.json").readText()); require(row.getString("owner") == owner); row.remove("installer_nonce")
        if (row.optString("status") == "installer_reported_success") {
            val installed = context.packageManager.getPackageInfo(PACKAGE, signatureFlags())
            val signers = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            val sameCertificate = signers?.map { digest(it.toByteArray()) } == listOf(row.getString("certificate_sha256"))
            row.put("status", if (sameCertificate && packageVersion(installed) == row.getLong("version_code")) "installed_version_observed" else "failed")
        }
        return row
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { runCatching { ApkUpdates.receive(context, intent) } }
}
