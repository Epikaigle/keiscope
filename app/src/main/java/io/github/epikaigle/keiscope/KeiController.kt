package io.github.epikaigle.keiscope

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Small state-holder deliberately kept independent from Mihon internals. */
class KeiController(private val context: Context) {
    private val repo = KeiyoushiRepository(context)
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var catalog by mutableStateOf<List<ExtensionInfo>>(emptyList())
        private set
    var installed by mutableStateOf<Map<String, InstalledInfo>>(emptyMap())
        private set
    var followed by mutableStateOf(preferences.getStringSet("followed", emptySet())?.toSet() ?: emptySet())
        private set
    var loadingCatalog by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set

    var selected by mutableStateOf<ExtensionInfo?>(null)
        private set
    var history by mutableStateOf<List<CommitInfo>>(emptyList())
        private set
    var historyHasMore by mutableStateOf(false)
        private set
    var historyError by mutableStateOf<String?>(null)
        private set
    var loadingHistory by mutableStateOf(false)
        private set
    private var historyPage = 1

    var checkingUpdate by mutableStateOf(false)
        private set
    var availableUpdate by mutableStateOf<AvailableUpdate?>(null)
        private set
    var updateMessage by mutableStateOf<String?>(null)
        private set
    var downloadingUpdate by mutableStateOf(false)
        private set
    var downloadProgress by mutableStateOf(0)
        private set
    private var downloadedApk: java.io.File? = null
    private var downloadedTag: String? = null

    init { scanInstalled() }

    val myExtensions: List<ExtensionInfo>
        get() {
            val fromCatalog = catalog.filter { it.pkg in installed || it.pkg in followed }
            val known = fromCatalog.mapTo(mutableSetOf()) { it.pkg }
            val unknown = (installed.keys + followed).filter { it !in known }.map { pkg ->
                ExtensionInfo(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    pkg, pkg.split('.').getOrNull(4) ?: "?", -1, "?", false)
            }
            return (fromCatalog + unknown).sortedBy { it.name.lowercase() }
        }

    fun scanInstalled() {
        installed = try { repo.installedExtensions() } catch (_: Exception) { emptyMap() }
    }

    suspend fun refreshCatalog(force: Boolean = false) {
        if (loadingCatalog) return
        loadingCatalog = true
        catalogError = null
        try {
            catalog = repo.loadCatalog(force)
            scanInstalled()
        } catch (e: Exception) {
            catalogError = e.message ?: "Impossible de récupérer le catalogue. Vérifiez la connexion Internet."
        } finally {
            loadingCatalog = false
        }
    }

    fun toggleFollow(pkg: String) {
        followed = if (pkg in followed) followed - pkg else followed + pkg
        preferences.edit().putStringSet("followed", followed.toSet()).apply()
    }

    fun showDetails(extension: ExtensionInfo) {
        selected = extension
        history = emptyList()
        historyError = null
        historyHasMore = false
        historyPage = 1
        scope.launch { loadHistory(extension, reset = true) }
    }

    fun closeDetails() { selected = null }

    suspend fun loadHistory(ext: ExtensionInfo, reset: Boolean) {
        if (loadingHistory) return
        loadingHistory = true
        historyError = null
        val requestedPage = if (reset) 1 else historyPage + 1
        try {
            val result = repo.loadHistory(ext, requestedPage)
            if (selected?.pkg != ext.pkg) return
            historyPage = requestedPage
            history = if (reset) result.commits else (history + result.commits).distinctBy { it.sha }
            historyHasMore = result.hasMore
        } catch (e: Exception) {
            if (selected?.pkg == ext.pkg) historyError = e.message ?: "Impossible de lire GitHub."
        } finally {
            loadingHistory = false
        }
    }

    suspend fun loadMoreHistory(ext: ExtensionInfo) {
        if (!historyHasMore || loadingHistory) return
        loadHistory(ext, reset = false)
    }

    suspend fun checkUpdate(silent: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        updateMessage = null
        try {
            val release = AppUpdates.check()
            if (release?.tag != downloadedTag) {
                downloadedApk = null
                downloadedTag = null
            }
            availableUpdate = release
            if (!silent) updateMessage = if (release == null) "KeiScope est à jour." else "Une mise à jour est disponible."
        } catch (e: Exception) {
            if (!silent) updateMessage = "Vérification impossible : ${e.message}"
        } finally {
            checkingUpdate = false
        }
    }

    suspend fun downloadAndInstall() {
        val release = availableUpdate ?: return
        if (downloadingUpdate) return
        val apk = downloadedApk
        try {
            if (apk != null && apk.exists()) {
                AppUpdates.requestInstallation(context, apk)
                return
            }
            downloadingUpdate = true
            downloadProgress = 0
            updateMessage = "Téléchargement de ${release.tag}…"
            val downloaded = AppUpdates.download(context, release) { pct -> downloadProgress = pct }
            downloadedApk = downloaded
            downloadedTag = release.tag
            updateMessage = "Téléchargement terminé. Confirmez l'installation Android."
            AppUpdates.requestInstallation(context, downloaded)
        } catch (e: Exception) {
            updateMessage = e.message ?: "Impossible de lancer l'installation."
        } finally {
            downloadingUpdate = false
        }
    }

    fun dispose() { scope.cancel() }
}
