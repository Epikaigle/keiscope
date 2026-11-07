package io.github.epikaigle.keiscope

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Data comes from the Keiyoushi catalogue and the Git history, not from guessed release notes. */
data class ExtensionInfo(
    val name: String,
    val pkg: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Boolean,
) {
    /** Keiyoushi uses src/{language}/{package suffix} for ordinary extensions. */
    val sourcePath: String?
        get() {
            val prefix = "eu.kanade.tachiyomi.extension."
            if (!pkg.startsWith(prefix)) return null
            val suffix = pkg.removePrefix(prefix)
            val parts = suffix.split('.')
            if (parts.size < 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) return null
            return "src/${parts[0]}/${parts.drop(1).joinToString("")}" // common standard package convention
        }
}

data class InstalledInfo(val pkg: String, val version: String, val code: Long)

data class CommitInfo(
    val sha: String,
    val title: String,
    val date: String,
    val url: String,
    val versionAtCommit: String?,
) {
    val shortSha get() = sha.take(7)
    val pullRequest: String?
        get() = Regex("\\(#(\\d+)\\)").find(title)?.groupValues?.getOrNull(1)?.let {
            "https://github.com/keiyoushi/extensions-source/pull/$it"
        }
}

data class HistoryPage(val commits: List<CommitInfo>, val hasMore: Boolean)

object Net {
    fun read(url: String, accept: String = "application/json"): String {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "HTTPS requis" }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.setRequestProperty("User-Agent", "KeiScope/1.0 (Android; github.com/Epikaigle/keiscope)")
        connection.setRequestProperty("Accept", accept)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(when (code) {
                    403, 429 -> "Limite de requêtes GitHub atteinte. Réessayez plus tard."
                    404 -> "Ressource introuvable (404)"
                    else -> "Erreur HTTP $code"
                })
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class KeiyoushiRepository(private val context: Context) {
    private val catalogCache = File(context.filesDir, "keiyoushi-index.json")

    suspend fun loadCatalog(force: Boolean = false): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        val cached = if (catalogCache.isFile) {
            runCatching { CatalogParser.parse(catalogCache.readText()) }.getOrNull()?.takeIf { it.size > 50 }
        } else null
        val fresh = catalogCache.isFile &&
            System.currentTimeMillis() - catalogCache.lastModified() in 0 until 6 * 3600_000L
        if (!force && fresh && cached != null) return@withContext cached

        try {
            val latest = Net.read("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json")
            val parsed = CatalogParser.parse(latest)
            require(parsed.size > 50) { "Catalogue Keiyoushi vide ou incomplet" }
            // Replace the cache only after successfully parsing its actual format.
            catalogCache.writeText(latest)
            parsed
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    /** Reads locally installed packages. Requires QUERY_ALL_PACKAGES on a sideloaded Android build. */
    @Suppress("DEPRECATION")
    fun installedExtensions(): Map<String, InstalledInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        return packages.mapNotNull { item ->
            val pkg = item.packageName
            if (!pkg.startsWith("eu.kanade.tachiyomi.extension.")) return@mapNotNull null
            pkg to InstalledInfo(pkg, item.versionName ?: "?", item.longVersionCode)
        }.toMap()
    }

    private val historyDir = File(context.filesDir, "history").apply { mkdirs() }

    /** Git commits are mapped to the version declared by build.gradle.kts at each commit SHA.
     * This is a history of SOURCE revisions, not guaranteed to match the contents of published APKs.
     */
    suspend fun loadHistory(ext: ExtensionInfo, page: Int = 1): HistoryPage = withContext(Dispatchers.IO) {
        val path = ext.sourcePath ?: throw IllegalArgumentException("Chemin source inconnu pour cette extension")
        val diskKey = ext.pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_") + "-p$page.json"
        val diskFile = File(historyDir, diskKey)
        val raw = if (diskFile.isFile && System.currentTimeMillis() - diskFile.lastModified() < 6 * 3600_000L) {
            diskFile.readText()
        } else {
            try {
                val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
                val loaded = Net.read("https://api.github.com/repos/keiyoushi/extensions-source/commits?path=$encodedPath&per_page=30&page=$page")
                require(JSONArray(loaded).length() <= 30) { "Historique GitHub invalide" }
                diskFile.writeText(loaded)
                loaded
            } catch (e: Exception) {
                if (diskFile.isFile) diskFile.readText() else throw e
            }
        }
        val list = JSONArray(raw)
        val tuples = (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val sha = item.optString("sha")
            val detail = item.optJSONObject("commit") ?: return@mapNotNull null
            val message = detail.optString("message").lineSequence().firstOrNull().orEmpty()
            val date = detail.optJSONObject("author")?.optString("date").orEmpty().take(10)
            Triple(sha, message, date)
        }
        val limiter = Semaphore(5)
        val commits = coroutineScope {
            tuples.map { (sha, message, date) ->
                async(Dispatchers.IO) {
                    val version = limiter.withPermit { getVersionAtCommit(path, sha) }
                    CommitInfo(
                        sha = sha,
                        title = message.ifBlank { "Modification du code" },
                        date = date,
                        url = "https://github.com/keiyoushi/extensions-source/commit/$sha",
                        versionAtCommit = version,
                    )
                }
            }.awaitAll()
        }
        HistoryPage(commits, list.length() == 30)
    }

    private fun getVersionAtCommit(path: String, sha: String): String? {
        // A single commit may modify several extensions. Cache keys must include the path.
        val safePath = path.replace('/', '-').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val versionKey = File(historyDir, "version-$safePath-$sha.txt")
        if (versionKey.exists()) return versionKey.readText().ifBlank { null }
        var unavailable = false
        for (filename in listOf("build.gradle.kts", "build.gradle")) {
            val rawUrl = "https://raw.githubusercontent.com/keiyoushi/extensions-source/$sha/$path/$filename"
            val source = try { Net.read(rawUrl, "text/plain") } catch (e: Exception) {
                if (e.message?.contains("404") != true) unavailable = true
                continue
            }
            val versionCode = Regex("(?:extVersionCode|versionCode)\\s*(?:=|\\s)\\s*(\\d+)").find(source)?.groupValues?.get(1)
            val libVersion = Regex("libVersion\\s*=\\s*\"([0-9.]+)\"").find(source)?.groupValues?.get(1)
            val directVersion = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(source)?.groupValues?.get(1)
            val version = when {
                libVersion != null && versionCode != null -> "$libVersion.$versionCode"
                versionCode != null -> null // no verified public version without libVersion
                directVersion != null -> directVersion
                else -> null
            }
            versionKey.writeText(version.orEmpty())
            return version
        }
        // Cache missing files, but never cache transient network/API failures as no history.
        if (!unavailable) versionKey.writeText("")
        return null
    }
}


/** Handles the current Keiyoushi repository index as well as older Tachiyomi-style indexes. */
internal object CatalogParser {
    private const val PREFIX = "eu.kanade.tachiyomi.extension."

    fun parse(input: String): List<ExtensionInfo> {
        val root = org.json.JSONTokener(input).nextValue()
        val entries = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONObject("extensionList")?.optJSONArray("extensions")
                ?: root.optJSONArray("extensions")
                ?: throw IllegalArgumentException("Format de catalogue Keiyoushi inconnu")
            else -> throw IllegalArgumentException("Catalogue Keiyoushi invalide")
        }
        val parsed = ArrayList<ExtensionInfo>(entries.length())
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val pkg = item.optString("packageName").ifBlank { item.optString("pkg") }
            if (!pkg.startsWith(PREFIX)) continue
            val locale = item.optString("lang").ifBlank {
                pkg.removePrefix(PREFIX).substringBefore('.', "all")
            }
            val version = item.optString("versionName").ifBlank {
                item.optString("version", "?")
            }
            val codeValue = item.opt("versionCode") ?: item.opt("code")
            val code = codeValue?.toString()?.toLongOrNull() ?: -1L
            val explicitNsfw = item.optInt("nsfw", 0) != 0
            val warning = item.optString("contentWarning", "")
            parsed += ExtensionInfo(
                name = item.optString("name", pkg).removePrefix("Tachiyomi: "),
                pkg = pkg,
                lang = locale,
                code = code,
                version = version,
                nsfw = explicitNsfw || warning.contains("NSFW", ignoreCase = true),
            )
        }
        return parsed.distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
    }
}
