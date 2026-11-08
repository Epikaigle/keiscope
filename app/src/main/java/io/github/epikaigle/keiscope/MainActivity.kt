package io.github.epikaigle.keiscope

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller: KeiController by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return KeiController(applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KeiScopeApp(controller) }
    }

    override fun onResume() {
        super.onResume()
        controller.scanInstalled()
    }
}

private fun Context.openBrowser(url: String) {
    val uri = url.toUri()
    if (uri.scheme != "https") return
    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeiScopeApp(controller: KeiController) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    BackHandler(enabled = controller.selected != null) { controller.closeDetails() }

    LaunchedEffect(Unit) {
        launch { controller.refreshCatalog() }
        launch { controller.checkUpdate(silent = true) }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // Responsive NavigationRail on wide screens / tablets / landscape
                if (isWideScreen && controller.selected == null) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        header = {
                            Icon(
                                Icons.Default.AutoStories,
                                contentDescription = "KeiScope",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    ) {
                        NavigationRailItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                            label = { Text("Installées") }
                        )
                        NavigationRailItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("Catalogue") }
                        )
                        NavigationRailItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Réglages") }
                        )
                        Spacer(Modifier.weight(1f))
                        if (tab != 2) {
                            IconButton(onClick = { controller.toggleGridView() }) {
                                Icon(
                                    if (controller.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                    contentDescription = if (controller.isGridView) "Affichage liste" else "Affichage grille"
                                )
                            }
                            IconButton(onClick = { scope.launch { controller.refreshCatalog(force = true) } }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                            }
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    controller.selected?.name ?: "KeiScope",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                if (controller.selected != null) {
                                    IconButton(onClick = { controller.closeDetails() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                                    }
                                }
                            },
                            actions = {
                                if (controller.selected == null && tab != 2) {
                                    // Toggle between Grid and List view
                                    IconButton(onClick = { controller.toggleGridView() }) {
                                        Icon(
                                            if (controller.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                            contentDescription = if (controller.isGridView) "Afficher en liste" else "Afficher en grille"
                                        )
                                    }
                                    if (!isWideScreen) {
                                        IconButton(onClick = { scope.launch { controller.refreshCatalog(force = true) } }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                                        }
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        // Standard NavigationBar on phones in portrait
                        if (!isWideScreen && controller.selected == null) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                    label = { Text("Installées") }
                                )
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    label = { Text("Catalogue") }
                                )
                                NavigationBarItem(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("Réglages") }
                                )
                            }
                        }
                    }
                ) { insets ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(insets)
                    ) {
                        if (controller.loadingCatalog) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (controller.selected == null && tab != 2) {
                            controller.catalogError?.let { message ->
                                ErrorBanner(message) { scope.launch { controller.refreshCatalog(force = true) } }
                            }
                        }
                        if (controller.selected != null) {
                            ExtensionDetails(controller)
                        } else {
                            when (tab) {
                                0 -> MyExtensions(controller)
                                1 -> CatalogScreen(controller, query) { query = it }
                                else -> SettingsScreen(controller)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(
    ext: ExtensionInfo,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val context = LocalContext.current
    val localDrawable = remember(ext.pkg) {
        runCatching { context.packageManager.getApplicationIcon(ext.pkg) }.getOrNull()
    }
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        val model = ext.iconUrl ?: localDrawable
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = ext.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = ext.lang.uppercase().take(3),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = retry) {
                Text("Réessayer", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun MyExtensions(controller: KeiController) {
    val mine = controller.myExtensions
    val isGrid = controller.isGridView

    LazyVerticalGrid(
        columns = if (isGrid) GridCells.Adaptive(minSize = 150.dp) else GridCells.Adaptive(minSize = 340.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "${controller.installed.size} extension${if (controller.installed.size > 1) "s" else ""} installée${if (controller.installed.size > 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (mine.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("Aucune extension installée", "Installez des extensions depuis votre lecteur Mihon ou Tachiyomi pour les retrouver ici.")
            }
        }
        items(mine, key = { it.pkg }) { extension ->
            if (isGrid) {
                ExtensionGridCard(extension, controller)
            } else {
                ExtensionListCard(extension, controller)
            }
        }
    }
}

@Composable
private fun CatalogScreen(controller: KeiController, query: String, onQuery: (String) -> Unit) {
    val results = remember(query, controller.catalog) {
        val q = query.trim()
        if (q.isEmpty()) controller.catalog else controller.catalog.filter {
            it.name.contains(q, ignoreCase = true) ||
            it.pkg.contains(q, ignoreCase = true) ||
            it.lang.contains(q, ignoreCase = true)
        }
    }
    val isGrid = controller.isGridView

    Column(
        Modifier
            .fillMaxSize()
            .widthIn(max = 1200.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Rechercher une extension, une langue ou un paquet") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Effacer")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp)
        )
        LazyVerticalGrid(
            columns = if (isGrid) GridCells.Adaptive(minSize = 150.dp) else GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results, key = { it.pkg }) { extension ->
                if (isGrid) {
                    ExtensionGridCard(extension, controller)
                } else {
                    ExtensionListCard(extension, controller)
                }
            }
            if (results.isEmpty() && !controller.loadingCatalog) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("Aucun résultat", if (query.isNotBlank()) "Aucune extension trouvée pour « $query »" else "")
                }
            }
        }
    }
}

/** Card for List view (horizontal format with details) */
@Composable
private fun ExtensionListCard(ext: ExtensionInfo, controller: KeiController) {
    val installed = controller.installed[ext.pkg]
    val newer = installed != null && ext.code > installed.code && ext.code >= 0
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { controller.showDetails(ext) },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExtensionIcon(ext, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ext.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (ext.nsfw) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "18+",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${ext.lang.uppercase()} • v${ext.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (installed != null) {
                    Spacer(Modifier.height(3.dp))
                    Surface(
                        color = if (newer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (newer) "MàJ disponible (v${installed.version} → v${ext.version})"
                                   else if (ext.code < 0) "Installée • v${installed.version}"
                                   else "Installée",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (newer) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (newer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Compact card for Grid view */
@Composable
private fun ExtensionGridCard(ext: ExtensionInfo, controller: KeiController) {
    val installed = controller.installed[ext.pkg]
    val newer = installed != null && ext.code > installed.code && ext.code >= 0
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { controller.showDetails(ext) },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                ExtensionIcon(ext, size = 52.dp)
                if (ext.nsfw) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.offset(x = 4.dp, y = (-4).dp)
                    ) {
                        Text(
                            "18+",
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ext.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${ext.lang.uppercase()} • v${ext.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (installed != null) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = if (newer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (newer) "MàJ dispo" else "Installée",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (newer) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (newer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ExtensionDetails(controller: KeiController) {
    val ext = controller.selected ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commits = controller.history
    val displayVersion = if (ext.version == "?") controller.installed[ext.pkg]?.version ?: "?" else ext.version

    // Group commits by version to show clean section headers, but commits are directly displayed sequentially without collapsing!
    val groups = remember(commits) {
        commits.groupBy { it.versionAtCommit ?: "Version non identifiée" }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExtensionIcon(ext, size = 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "v$displayVersion",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            controller.installed[ext.pkg]?.takeIf { it.version != displayVersion }?.let {
                                Text(
                                    "Installée • v${it.version}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                ext.pkg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        ext.sourcePath?.let { path ->
                            IconButton(onClick = {
                                context.openBrowser("https://github.com/keiyoushi/extensions-source/tree/main/$path")
                            }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Code source sur GitHub")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Historique des commits",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (controller.loadingHistory) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            controller.historyError?.let { error ->
                item {
                    ErrorBanner(error) { scope.launch { controller.loadHistory(ext, reset = true) } }
                }
            }
            if (groups.isEmpty() && !controller.loadingHistory && controller.historyError == null) {
                item { EmptyState("Aucun historique", "Aucune modification Git répertoriée pour ce dossier.") }
            }

            // Directly show all commits in continuous stream, grouped by version sections without collapsing
            groups.forEach { (version, changes) ->
                item(key = "section-$version") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (version == "Version non identifiée") "Autres modifications" else "Version $version",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${changes.size} commit${if (changes.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(changes, key = { it.sha }) { change ->
                    CommitItemCard(change, context)
                }
            }

            if (controller.historyHasMore && !controller.loadingHistory) {
                item {
                    OutlinedButton(
                        onClick = { scope.launch { controller.loadMoreHistory(ext) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Charger les commits plus anciens")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitItemCard(change: CommitInfo, context: Context) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.openBrowser(change.url) },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    "Ouvrir sur GitHub",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${change.date} · ${change.shortSha}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                change.pullRequest?.let { link ->
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { context.openBrowser(link) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("PR", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: KeiController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp)
        ) {
            item {
                Text(
                    "KeiScope · v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Mises à jour de l'application",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Default.Update, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Button(
                            onClick = { scope.launch { controller.checkUpdate(silent = false) } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !controller.checkingUpdate
                        ) {
                            if (controller.checkingUpdate) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Rechercher une mise à jour")
                        }
                        controller.updateMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        controller.availableUpdate?.let { available ->
                            Text(
                                "Nouvelle version : ${available.tag}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = { scope.launch { controller.downloadAndInstall() } },
                                enabled = !controller.downloadingUpdate,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (controller.downloadingUpdate) "${controller.downloadProgress} %"
                                    else "Installer la mise à jour"
                                )
                            }
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "À propos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "KeiScope est un explorateur de changelog open source pour extensions Keiyoushi, indépendant de Mihon.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { context.openBrowser("https://github.com/Epikaigle/keiscope") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Dépôt GitHub (Epikaigle/keiscope)")
                        }
                    }
                }
            }
        }
    }
}
