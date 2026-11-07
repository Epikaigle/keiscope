package io.github.epikaigle.keiscope

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var controller: KeiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = KeiController(applicationContext)
        setContent { KeiScopeApp(controller) }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.scanInstalled()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.dispose()
        super.onDestroy()
    }
}

private fun Context.openBrowser(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return
    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeiScopeApp(controller: KeiController) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = controller.selected != null) { controller.closeDetails() }

    LaunchedEffect(Unit) {
        controller.refreshCatalog()
        controller.checkUpdate(silent = true)
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (controller.selected != null) controller.selected?.name ?: "Extension" else "KeiScope")
                            if (controller.selected == null) Text("Keiyoushi • Mihon", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        if (controller.selected != null) IconButton(onClick = { controller.closeDetails() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    actions = {
                        if (controller.selected == null && tab != 2) IconButton(onClick = {
                            scope.launch { controller.refreshCatalog(force = true) }
                        }) { Icon(Icons.Default.Refresh, contentDescription = "Actualiser") }
                    }
                )
            },
            bottomBar = {
                if (controller.selected == null) NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Apps, contentDescription = null) }, label = { Text("Mes extensions") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) }, label = { Text("Catalogue") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Réglages") })
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (controller.loadingCatalog) LinearProgressIndicator(Modifier.fillMaxWidth())
                controller.catalogError?.let { message ->
                    ErrorBanner(message, { scope.launch { controller.refreshCatalog(force = true) } })
                }
                if (controller.selected != null) {
                    ExtensionDetails(controller)
                } else when (tab) {
                    0 -> MyExtensions(controller)
                    1 -> CatalogScreen(controller, query, { query = it })
                    else -> SettingsScreen(controller)
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text("Réessayer") }
        }
    }
}

@Composable
private fun MyExtensions(controller: KeiController) {
    val mine = controller.myExtensions
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Sur votre Pixel", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("${controller.installed.size} extension(s) détectée(s) • ${controller.followed.size} sélectionnée(s)",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        if (mine.isEmpty()) item {
            EmptyState("Aucune extension sélectionnée", "Les extensions installées seront détectées automatiquement. Vous pouvez aussi en ajouter depuis le catalogue.")
        }
        items(mine, key = { it.pkg }) { extension -> ExtensionCard(extension, controller) }
        item {
            Spacer(Modifier.height(6.dp))
            Text("Les applications Keiyoushi sont détectées sur l'appareil. KeiScope ne lit pas les données privées de Mihon.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CatalogScreen(controller: KeiController, query: String, onQuery: (String) -> Unit) {
    val results = remember(query, controller.catalog) {
        val q = query.trim()
        if (q.isEmpty()) controller.catalog else controller.catalog.filter {
            it.name.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) || it.lang.contains(q, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            singleLine = true, label = { Text("Rechercher une extension") },
            leadingIcon = { Icon(Icons.Default.Search, null) })
        Text("${results.size} résultat(s)", modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(results, key = { it.pkg }) { extension -> ExtensionCard(extension, controller) }
            if (results.isEmpty() && !controller.loadingCatalog) item {
                EmptyState("Aucun résultat", "Vérifiez votre recherche ou actualisez le catalogue.")
            }
        }
    }
}

@Composable
private fun ExtensionCard(ext: ExtensionInfo, controller: KeiController) {
    val installed = controller.installed[ext.pkg]
    val followed = controller.followed.contains(ext.pkg)
    val newer = installed != null && ext.code > installed.code && ext.code >= 0
    ElevatedCard(Modifier.fillMaxWidth().clickable { controller.showDetails(ext) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.AutoStories, null,
                    modifier = Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ext.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text("${ext.lang.uppercase()} • Dernière v${ext.version}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (installed != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(if (newer) "Mise à jour de l'extension disponible (v${installed.version})" else "Installée : v${installed.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (newer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { controller.toggleFollow(ext.pkg) }) {
                Icon(if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (followed) "Retirer des favoris" else "Suivre cette extension",
                    tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Info, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExtensionDetails(controller: KeiController) {
    val ext = controller.selected ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commits = controller.history
    val groups = remember(commits) {
        commits.groupBy { it.versionAtCommit ?: "Version non identifiée" }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Dernière version connue", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(5.dp))
                    Text("v${ext.version}", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    controller.installed[ext.pkg]?.let {
                        Text("Installée : v${it.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(ext.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.Start) {
                        OutlinedButton(onClick = {
                            ext.sourcePath?.let { path -> context.openBrowser("https://github.com/keiyoushi/extensions-source/tree/main/$path") }
                        }, enabled = ext.sourcePath != null) { Text("Code source") }
                        TextButton(onClick = { controller.toggleFollow(ext.pkg) }) {
                            Text(if (controller.followed.contains(ext.pkg)) "Ne plus suivre" else "Suivre")
                        }
                    }
                }
            }
        }
        item {
            Text("Historique des versions", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text("Versions retrouvées à partir de l'état du code Git à chaque commit. Il ne s'agit pas forcément du changelog officiel des APK publiés. Les changements des bibliothèques partagées peuvent manquer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (controller.loadingHistory) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        controller.historyError?.let { error -> item { ErrorBanner(error) { scope.launch { controller.loadHistory(ext, reset = true) } } } }
        if (groups.isEmpty() && !controller.loadingHistory && controller.historyError == null) item {
            EmptyState("Aucun historique retrouvé", "Le dépôt peut avoir été déplacé ou aucune modification n'est accessible.")
        }
        groups.forEach { (version, changes) ->
            item(key = "group-$version") { VersionGroup(version, changes) }
        }
        if (controller.historyHasMore && !controller.loadingHistory) item {
            OutlinedButton(onClick = { scope.launch { controller.loadMoreHistory(ext) } },
                modifier = Modifier.fillMaxWidth()) { Text("Charger des modifications plus anciennes") }
        }
        item {
            TextButton(onClick = { context.openBrowser("https://github.com/keiyoushi/extensions-source/commits/main/${ext.sourcePath ?: "src"}") }) {
                Text("Voir l'historique complet sur GitHub")
                Spacer(Modifier.width(5.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun VersionGroup(version: String, changes: List<CommitInfo>) {
    val context = LocalContext.current
    var expanded by remember(version) { mutableStateOf(true) }
    ElevatedCard {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (version == "Version non identifiée") version else "Version $version",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${changes.size} modification(s) retrouvée(s)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "Réduire" else "Ouvrir", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                HorizontalDivider()
                changes.forEach { change ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(change.title, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(5.dp))
                        Text("${change.date} • ${change.shortSha}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp),
                            horizontalAlignment = Alignment.Start) {
                            TextButton(onClick = { context.openBrowser(change.url) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("Commit GitHub", style = MaterialTheme.typography.labelSmall)
                            }
                            change.pullRequest?.let { link ->
                                TextButton(onClick = { context.openBrowser(link) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text("Pull request", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: KeiController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Mises à jour de KeiScope", style = MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME} • Pixel 7 Pro • Android 17",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Update, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Vérifier les mises à jour", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Les nouvelles versions de l'application proviennent exclusivement des GitHub Releases du projet.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { controller.checkUpdate(silent = false) } },
                        enabled = !controller.checkingUpdate) {
                        if (controller.checkingUpdate) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Chercher une nouvelle version")
                    }
                    controller.updateMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    controller.availableUpdate?.let { available ->
                        Spacer(Modifier.height(10.dp))
                        Text("Nouvelle version : ${available.tag}", color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold)
                        Button(onClick = { scope.launch { controller.downloadAndInstall() } },
                            enabled = !controller.downloadingUpdate) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (controller.downloadingUpdate) "Téléchargement ${controller.downloadProgress}%" else "Télécharger et installer")
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Confidentialité", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(5.dp))
                    Text("La liste des applications installées et les favoris restent sur cet appareil. KeiScope contacte uniquement Keiyoushi et GitHub pour récupérer les données.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { context.openBrowser("https://github.com/Epikaigle/keiscope") }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Projet GitHub")
                    }
                }
            }
        }
        item { Text("KeiScope n'est affilié ni à Mihon ni à l'équipe Keiyoushi.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
