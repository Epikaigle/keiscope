package io.github.epikaigle.keiscope

import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AvailableUpdate(
    val tag: String,
    val name: String,
    val apkUrl: String,
    val sha256: String?,
    val releaseUrl: String,
)

object AppUpdates {
    const val RELEASES = "https://github.com/Epikaigle/keiscope/releases"
    private const val LATEST_API = "https://api.github.com/repos/Epikaigle/keiscope/releases/latest"

    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val release = try {
            JSONObject(Net.read(LATEST_API))
        } catch (e: Exception) {
            // First launch may precede the first GitHub Release.
            if (e.message?.contains("404") == true) return@withContext null
            throw e
        }
        val tag = release.optString("tag_name", "")
        val current = BuildConfig.VERSION_NAME
        if (!isNewer(tag, current)) return@withContext null
        val assets = release.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "github.com") continue
            val digest = asset.optString("digest", "")
            val sha = digest.takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }?.substringAfter(':')
            return@withContext AvailableUpdate(tag, name, url, sha, release.optString("html_url", RELEASES))
        }
        null
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
        val c = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(outputDir, "keiscope.apk.part")
        val apk = File(outputDir, "keiscope.apk")
        part.delete()
        try {
            val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "KeiScope/1.0")
            try {
                require(connection.responseCode in 200..299) { "Téléchargement impossible (HTTP ${connection.responseCode})" }
                val total = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count: Int
                        var received = 0L
                        var lastProgress = -1
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            received += count
                            if (total > 0) {
                                val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(100) }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (update.sha256 != null && !actual.equals(update.sha256, true)) {
                    throw IllegalStateException("L'empreinte SHA-256 de l'APK ne correspond pas")
                }
                check(part.length() > 32_000) { "Fichier APK trop petit" }
                if (apk.exists()) apk.delete()
                check(part.renameTo(apk)) { "Impossible de sauvegarder l'APK" }
                apk
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun requestInstallation(context: Context, apk: File) {
        val packageManager = context.packageManager
        // Prevent an unrelated APK from ever reaching the platform installer.
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        require(archive?.packageName == context.packageName) { "L'APK ne correspond pas à KeiScope" }
        val current = packageManager.getPackageInfo(context.packageName, flags)
        require(archive.longVersionCode > current.longVersionCode) { "La version téléchargée n'est pas plus récente" }
        val installedSignatures = current.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val candidateSignatures = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        require(installedSignatures.isNotEmpty() && installedSignatures == candidateSignatures) {
            "Signature APK différente : mise à jour refusée"
        }
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            throw IllegalStateException("Autorisez l'installation pour KeiScope dans les paramètres, puis appuyez de nouveau sur Installer.")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
