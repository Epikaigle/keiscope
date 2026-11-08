package io.github.epikaigle.keiscope

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
    val apiUrl: String? = null,
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

        var foundApkName: String? = null
        var foundApkUrl: String? = null
        var foundApiUrl: String? = null
        var shaFromDigest: String? = null
        var shaAssetUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val apiUrl = asset.optString("url")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val uri = url.toUri()
                if (uri.scheme == "https" && uri.host == "github.com") {
                    foundApkName = name
                    foundApkUrl = url
                    foundApiUrl = apiUrl.takeIf { it.isNotBlank() }
                    val digest = asset.optString("digest", "")
                    if (digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) {
                        shaFromDigest = digest.substringAfter(':')
                    }
                }
            } else if (name.endsWith(".sha256", ignoreCase = true)) {
                val uri = url.toUri()
                if (uri.scheme == "https" && uri.host == "github.com") {
                    shaAssetUrl = url
                }
            }
        }

        if (foundApkName != null && foundApkUrl != null) {
            val resolvedSha = shaFromDigest ?: shaAssetUrl?.let { url ->
                runCatching {
                    Net.read(url).trim().split(Regex("\\s+")).firstOrNull()?.takeIf {
                        it.matches(Regex("[0-9a-fA-F]{64}"))
                    }
                }.getOrNull()
            }
            return@withContext AvailableUpdate(tag, foundApkName, foundApkUrl, resolvedSha, release.optString("html_url", RELEASES), foundApiUrl)
        }
        null
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val cleanRemote = remote.removePrefix("v").substringBefore('-')
        val cleanCurrent = current.removePrefix("v").substringBefore('-')
        val r = cleanRemote.split('.').map { it.toIntOrNull() ?: return false }
        val c = cleanCurrent.split('.').map { it.toIntOrNull() ?: return false }
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

        val candidates = listOfNotNull(update.apiUrl, update.apkUrl).distinct()
        var lastError: Exception? = null

        for (initialUrl in candidates) {
            try {
                var currentUrl = initialUrl
                var connection: HttpURLConnection
                var redirectCount = 0
                while (true) {
                    val parsed = URL(currentUrl)
                    require(parsed.protocol == "https") { "Téléchargement sécurisé HTTPS requis" }
                    connection = (parsed.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 25000
                        readTimeout = 35000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "KeiScope/1.0 (Android; github.com/Epikaigle/keiscope)")
                        if (parsed.host == "api.github.com") {
                            setRequestProperty("Accept", "application/octet-stream")
                        }
                    }
                    val code = connection.responseCode
                    if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location != null && redirectCount < 5) {
                            currentUrl = location
                            redirectCount++
                            continue
                        }
                    }
                    break
                }

                try {
                    require(connection.responseCode in 200..299) { "Téléchargement impossible (HTTP ${connection.responseCode})" }
                    val total = connection.contentLengthLong
                    val digest = MessageDigest.getInstance("SHA-256")
                    part.delete()
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
                        throw IllegalStateException("L'empreinte SHA-256 de l'APK ne correspond pas ($actual != ${update.sha256})")
                    }
                    check(part.length() > 32_000) { "Fichier APK trop petit" }
                    if (apk.exists()) apk.delete()
                    check(part.renameTo(apk)) { "Impossible de sauvegarder l'APK" }
                    return@withContext apk
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                part.delete()
            }
        }
        throw lastError ?: IllegalStateException("Échec du téléchargement")
    }

    fun requestInstallation(context: Context, apk: File) {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags.toInt())
        }
        require(archive?.packageName == context.packageName) { "L'APK ne correspond pas à KeiScope" }

        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, flags.toInt())
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            current.versionCode.toLong()
        }
        require(archiveVersionCode > currentVersionCode) { "La version téléchargée ($archiveVersionCode) n'est pas plus récente que l'actuelle ($currentVersionCode)" }

        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            current.signatures?.toSet().orEmpty()
        }
        val candidateSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.toSet().orEmpty()
        }
        require(installedSignatures.isNotEmpty() && installedSignatures == candidateSignatures) {
            "Signature APK différente : mise à jour refusée"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                throw IllegalStateException("Autorisez l'installation pour KeiScope dans les paramètres, puis appuyez de nouveau sur Installer.")
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
