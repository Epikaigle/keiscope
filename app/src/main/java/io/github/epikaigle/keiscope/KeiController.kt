package io.github.epikaigle.keiscope

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class SortOption(val label: String) {
    NAME_ASC("Nom (A-Z)"),
    NAME_DESC("Nom (Z-A)"),
    UPDATES_FIRST("MàJ en premier"),
    DATE_DESC("Date (dernière MàJ)"),
    VERSION_CODE("Version récente")
}

/** Small state-holder deliberately kept independent from Mihon internals. */
class KeiController(private val context: Context) : ViewModel() {
    private val repo = KeiyoushiRepository(context)
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var catalog by mutableStateOf<List<ExtensionInfo>>(emptyList())
        private set
    var installed by mutableStateOf<Map<String, InstalledInfo>>(emptyMap())
        private set
    var isGridView by mutableStateOf(preferences.getBoolean("is_grid_view", false))
        private set
    var selectedLang by mutableStateOf<String?>(preferences.getString("selected_lang", null))
        private set
    var hideNsfw by mutableStateOf(preferences.getBoolean("hide_nsfw", false))
        private set
    var sortOption by mutableStateOf(
        runCatching {
            SortOption.valueOf(preferences.getString("sort_option", SortOption.NAME_ASC.name) ?: SortOption.NAME_ASC.name)
        }.getOrDefault(SortOption.NAME_ASC)
    )
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
    private var historyJob: Job? = null

    var checkingUpdate by mutableStateOf(false)
        private set
    var availableUpdate by mutableStateOf<AvailableUpdate?>(null)
        private set
    var updateMessage by mutableStateOf<String?>(null)
        private set
    var downloadingUpdate by mutableStateOf(false)
        private set
    var downloadProgress by mutableIntStateOf(0)
        private set
    private var downloadedApk: java.io.File? = null
    private var downloadedTag: String? = null

    init { scanInstalled() }

    val myExtensions: List<ExtensionInfo>
        get() {
            val fromCatalog = catalog.filter { it.pkg in installed }
            val known = fromCatalog.mapTo(mutableSetOf()) { it.pkg }
            val unknown = installed.keys.filter { it !in known }.map { pkg ->
                ExtensionInfo(
                    name = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    pkg = pkg,
                    lang = pkg.split('.').getOrNull(4) ?: "?",
                    code = -1,
                    version = "?",
                    nsfw = false,
                )
            }
            return sortList(fromCatalog + unknown)
        }

    val availableLanguages: List<String>
        get() {
            val langs = catalog.map { it.lang.lowercase() }.distinct().sorted()
            val priority = listOf("fr", "en", "all")
            val rest = langs.filter { it !in priority }
            return priority.filter { it in langs } + rest
        }

    fun scanInstalled() {
        installed = try { repo.installedExtensions() } catch (_: Exception) { emptyMap() }
        prefetchInstalledDates()
    }

    private fun prefetchInstalledDates() {
        scope.launch(Dispatchers.IO) {
            val installedPkgs = installed.keys.toSet()
            val mine = catalog.filter { it.pkg in installedPkgs }
            for (ext in mine) {
                if (repo.getCachedCommitDate(ext.pkg) == null && ext.sourcePath != null) {
                    runCatching {
                        repo.loadHistory(ext, page = 1)
                    }
                    kotlinx.coroutines.delay(100)
                }
            }
        }
    }

    fun toggleGridView() {
        isGridView = !isGridView
        preferences.edit { putBoolean("is_grid_view", isGridView) }
    }

    fun setLanguageFilter(lang: String?) {
        selectedLang = lang
        preferences.edit { putString("selected_lang", lang) }
    }

    fun toggleHideNsfw() {
        hideNsfw = !hideNsfw
        preferences.edit { putBoolean("hide_nsfw", hideNsfw) }
    }

    fun setSort(option: SortOption) {
        sortOption = option
        preferences.edit { putString("sort_option", option.name) }
    }

    fun getCachedCommitDate(pkg: String): String? {
        return repo.getCachedCommitDate(pkg)
    }

    fun getEffectiveDate(ext: ExtensionInfo): String? {
        return repo.getCachedCommitDate(ext.pkg) ?: ext.releaseDate
    }

    fun sortList(list: List<ExtensionInfo>): List<ExtensionInfo> {
        return when (sortOption) {
            SortOption.NAME_ASC -> list.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            SortOption.UPDATES_FIRST -> list.sortedWith(
                compareByDescending<ExtensionInfo> { ext ->
                    val inst = installed[ext.pkg]
                    inst != null && ext.code > inst.code && ext.code >= 0
                }.thenBy { it.name.lowercase() }
            )
            SortOption.DATE_DESC -> list.sortedWith(
                compareByDescending<ExtensionInfo> { ext ->
                    getEffectiveDate(ext) ?: ""
                }.thenByDescending { it.code }.thenBy { it.name.lowercase() }
            )
            SortOption.VERSION_CODE -> list.sortedWith(
                compareByDescending<ExtensionInfo> { it.code }.thenBy { it.name.lowercase() }
            )
        }
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

    fun showDetails(extension: ExtensionInfo) {
        historyJob?.cancel()
        loadingHistory = false
        selected = extension
        history = emptyList()
        historyError = null
        historyHasMore = false
        historyPage = 1
        historyJob = scope.launch { loadHistory(extension, reset = true) }
    }

    fun closeDetails() {
        historyJob?.cancel()
        selected = null
        loadingHistory = false
    }

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
            if (selected?.pkg == ext.pkg) loadingHistory = false
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

    override fun onCleared() {
        super.onCleared()
        dispose()
    }
}
