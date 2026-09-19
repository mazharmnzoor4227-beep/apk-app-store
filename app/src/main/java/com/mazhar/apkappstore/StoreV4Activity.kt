package com.mazhar.apkappstore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

private val StoreBg = Color(0xFF07090D)
private val StorePanel = Color(0xFF10141B)
private val StorePanel2 = Color(0xFF151A23)
private val StoreAccent = Color(0xFF7B61FF)
private val StoreBlue = Color(0xFF3398FF)
private val StoreMuted = Color(0xFFA8AFBC)
private val StoreLine = Color(0xFF232A35)
private val StoreGood = Color(0xFF78E29A)
private val StoreGold = Color(0xFFFFC83D)

class StoreV4Activity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "apk-store-update-check",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        )
        setContent { StoreTheme { StoreRoot() } }
    }
}

@Composable
private fun StoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StoreAccent,
            secondary = StoreBlue,
            background = StoreBg,
            surface = StorePanel,
            surfaceVariant = StorePanel2,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class StoreTab { Home, Categories, Search, Downloads, Profile }

@Composable
private fun StoreRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(StoreTab.Home) }
    var apps by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<StoreApp?>(null) }
    var purchase by remember { mutableStateOf<StoreApp?>(null) }
    val progress = remember { mutableStateMapOf<String, Int>() }
    val downloaded = remember { mutableStateMapOf<String, File>() }

    fun reload() { refresh++ }

    BackHandler(enabled = purchase != null || details != null || tab != StoreTab.Home) {
        when {
            purchase != null -> purchase = null
            details != null -> details = null
            tab != StoreTab.Home -> tab = StoreTab.Home
        }
    }

    LaunchedEffect(refresh) {
        loading = true
        error = ""
        runCatching { StoreApi.apps(context) }
            .onSuccess { apps = it }
            .onFailure { error = it.message ?: "Could not load the store." }
        loading = false
    }

    fun action(app: StoreApp) {
        val state = StoreApi.installedState(context, app)
        if (state.installed && !state.updateAvailable) {
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
            return
        }
        if (app.isPaid && !app.owned) {
            purchase = app
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        scope.launch {
            progress[app.slug] = 1
            runCatching { AppDownloader.download(context, app) { p -> progress[app.slug] = p.coerceAtLeast(1) } }
                .onSuccess { file ->
                    progress[app.slug] = 100
                    downloaded[app.slug] = file
                    AppDownloader.requestInstall(context, file)
                }
                .onFailure {
                    progress.remove(app.slug)
                    error = it.message ?: "Download failed."
                }
        }
    }

    if (details != null) {
        AppDetailsScreen(details!!, progress[details!!.slug], { details = null }, { action(details!!) })
    } else {
        Scaffold(
            containerColor = StoreBg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B0F15), tonalElevation = 0.dp) {
                    BottomItem(tab == StoreTab.Home, "Home", Icons.Default.Home) { tab = StoreTab.Home }
                    BottomItem(tab == StoreTab.Categories, "Categories", Icons.Default.GridView) { tab = StoreTab.Categories }
                    BottomItem(tab == StoreTab.Search, "Search", Icons.Default.Search) { tab = StoreTab.Search }
                    BottomItem(tab == StoreTab.Downloads, "Downloads", Icons.Default.Download) { tab = StoreTab.Downloads }
                    BottomItem(tab == StoreTab.Profile, "Profile", Icons.Default.Person) { tab = StoreTab.Profile }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    StoreTab.Home -> HomeScreen(apps, loading, error, progress, { details = it }, ::action, ::reload, { tab = StoreTab.Search })
                    StoreTab.Categories -> CategoriesScreen(apps, { details = it }, ::action) { tab = StoreTab.Home }
                    StoreTab.Search -> SearchScreen(apps, query, { query = it }, { details = it }, ::action) { query = ""; tab = StoreTab.Home }
                    StoreTab.Downloads -> DownloadsScreenV4(apps, progress, downloaded, { details = it }, ::action) { tab = StoreTab.Home }
                    StoreTab.Profile -> ProfileScreen(apps, { details = it }, ::action, { tab = StoreTab.Home }, { tab = StoreTab.Downloads })
                }
            }
        }
    }

    purchase?.let { app ->
        PaymentDialog(app, { purchase = null }, { purchase = null; reload() })
    }
}

@Composable
private fun BottomItem(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label, fontSize = 10.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = StoreBlue,
            selectedTextColor = StoreBlue,
            indicatorColor = Color.Transparent,
            unselectedIconColor = StoreMuted,
            unselectedTextColor = StoreMuted
        )
    )
}

@Composable
private fun HomeScreen(
    apps: List<StoreApp>, loading: Boolean, error: String,
    progress: SnapshotStateMap<String, Int>, onOpen: (StoreApp) -> Unit,
    onAction: (StoreApp) -> Unit, onRefresh: () -> Unit, onSearch: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val featured = apps.firstOrNull { it.featured } ?: apps.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("APK App Store", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Apps for a smarter you", color = StoreMuted, fontSize = 12.sp)
                }
                FilledTonalIconButton(onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) }) { Icon(Icons.Default.Person, "Account") }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onSearch),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171D26)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = StoreMuted)
                    Spacer(Modifier.width(12.dp))
                    Text("Search apps, games, tools...", color = StoreMuted)
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
        if (apps.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { CategoryChip("All", Icons.Default.Apps) }
                    items(apps.map { it.category }.filter { it.isNotBlank() }.distinct()) { CategoryChip(it, Icons.Default.Category) }
                }
            }
        }
        featured?.let { app -> item { HeroCard(app, { onOpen(app) }, { onAction(app) }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recommended for you", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (error.isNotBlank()) item { Notice(error) }
        items(apps, key = { it.slug }) { app -> AppRow(app, StoreApi.installedState(context, app), progress[app.slug], { onOpen(app) }, { onAction(app) }) }
        if (!loading && error.isBlank() && apps.isEmpty()) item { Notice("No apps published yet.") }
    }
}

@Composable
private fun CategoryChip(label: String, icon: ImageVector) {
    Surface(color = StorePanel2, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = StoreAccent)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HeroCard(app: StoreApp, onOpen: () -> Unit, onAction: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF4024A7), Color(0xFF7E27C8)))).padding(20.dp)) {
            Column {
                Text("FEATURED APP", color = Color(0xFFE3DBFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 78)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.shortDescription, color = Color(0xFFE5E2EE), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text("★ 5.0   •   ${if (app.isPaid) "PKR ${app.pricePkr}" else "Free"}", color = StoreGold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF17121E)), shape = RoundedCornerShape(18.dp)) {
                    Text(actionText(app, StoreApi.installedState(context, app)), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: StoreApp, state: InstalledState, pct: Int?, onOpen: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, 72)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.category, color = StoreMuted, fontSize = 11.sp)
            Text("★ 5.0  •  ${app.versionName}  •  ${formatSize(app.fileSize)}", color = StoreGold, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (pct != null && pct in 1..99) CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
        else FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)) { Text(actionText(app, state), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 106.dp, end = 20.dp), color = StoreLine)
}

@Composable
private fun AppDetailsScreen(app: StoreApp, pct: Int?, onBack: () -> Unit, onAction: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = StoreApi.installedState(context, app)
    LazyColumn(Modifier.fillMaxSize().background(StoreBg), contentPadding = PaddingValues(bottom = 34.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { }) { Icon(Icons.Default.Share, "Share") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 98)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(app.category, color = StoreMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (app.isPaid) "Premium • PKR ${app.pricePkr}" else "Free download", color = if (app.isPaid) StoreGold else StoreGood, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("5.0", "★★★★★")
                Stat(formatSize(app.fileSize), "Size")
                Stat("${app.minSdk}+", "Android API")
            }
        }
        item {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = StoreBlue)) {
                if (pct != null && pct in 1..99) Text("Downloading $pct%") else Text(actionText(app, state), fontWeight = FontWeight.Bold)
            }
        }
        if (app.screenshots.isNotEmpty()) {
            item { SectionTitle("Screenshots") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots) { shot -> AsyncImage(model = shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(158.dp).height(280.dp).clip(RoundedCornerShape(18.dp)).background(StorePanel)) }
                }
            }
        }
        item { SectionTitle("About this app") }
        item { Text(app.description.ifBlank { app.shortDescription }, color = Color(0xFFD4D8E1), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        item { SectionTitle("Ratings & reviews") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("5.0", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("★★★★★", color = StoreGold, fontSize = 18.sp)
                    Text("Store display", color = StoreMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) { RatingBar(5, 1f); RatingBar(4, .12f); RatingBar(3, .04f); RatingBar(2, .02f); RatingBar(1, .01f) }
            }
        }
        item { SectionTitle("What's new") }
        item { Text(app.changelog.ifBlank { "Version ${app.versionName}" }, color = StoreMuted, modifier = Modifier.padding(horizontal = 20.dp)) }
    }
}

@Composable private fun Stat(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Color.White, fontWeight = FontWeight.Bold); Text(label, color = StoreMuted, fontSize = 10.sp) } }
@Composable private fun RatingBar(stars: Int, fraction: Float) { Row(verticalAlignment = Alignment.CenterVertically) { Text(stars.toString(), color = StoreMuted, fontSize = 10.sp, modifier = Modifier.width(16.dp)); LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = StoreBlue, trackColor = StoreLine) }; Spacer(Modifier.height(5.dp)) }
@Composable private fun SectionTitle(title: String) { Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp)) }

@Composable
private fun CategoriesScreen(apps: List<StoreApp>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories = apps.map { it.category.ifBlank { "Apps" } }.distinct()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("Categories", "Browse by type", onBack) }
        items(categories) { category ->
            SectionTitle(category)
            apps.filter { it.category.ifBlank { "Apps" } == category }.forEach { app -> AppRow(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
        }
        if (categories.isEmpty()) item { Notice("Categories will appear after apps are published.") }
    }
}

@Composable
private fun SearchScreen(apps: List<StoreApp>, query: String, onQuery: (String) -> Unit, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val result = apps.filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) || it.shortDescription.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("Search", "Find your favorite apps", onBack) }
        item { OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text("Search apps & games") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(22.dp)) }
        item { Spacer(Modifier.height(16.dp)) }
        items(result, key = { it.slug }) { app -> AppRow(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
        if (result.isEmpty()) item { Notice("No matching apps.") }
    }
}

@Composable
private fun DownloadsScreenV4(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, downloaded: SnapshotStateMap<String, File>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val visible = apps.filter { progress.containsKey(it.slug) || downloaded.containsKey(it.slug) || StoreApi.installedState(context, it).installed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("My Apps", "Downloads, installed apps and updates", onBack) }
        items(visible, key = { it.slug }) { app -> AppRow(app, StoreApi.installedState(context, app), progress[app.slug], { onOpen(app) }, { onAction(app) }) }
        if (visible.isEmpty()) item { Notice("Your downloaded and installed apps will appear here.") }
    }
}

@Composable
private fun ProfileScreen(apps: List<StoreApp>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onBack: () -> Unit, onDownloads: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val account = StoreConfig.account(context)
    val owned = apps.filter { it.owned && it.isPaid }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("My Profile", "Account, purchases and settings", onBack) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { context.startActivity(Intent(context, AccountActivity::class.java)) }, colors = CardDefaults.cardColors(containerColor = StorePanel), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(StoreAccent, StoreBlue))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(account?.name?.ifBlank { "APK App Store User" } ?: "Sign in to your account", fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(account?.email ?: "Sync purchases and profile", color = StoreMuted, fontSize = 12.sp) }
                    Icon(Icons.Default.ChevronRight, null, tint = StoreMuted)
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
        item { ProfileTile("Account & security", Icons.Default.Security) { context.startActivity(Intent(context, AccountActivity::class.java)) } }
        item { ProfileTile("Purchased apps", Icons.Default.ShoppingBag) { context.startActivity(Intent(context, AccountActivity::class.java)) } }
        item { ProfileTile("Updates", Icons.Default.SystemUpdate, onDownloads) }
        item { ProfileTile("Help & support", Icons.Default.Help) { context.startActivity(Intent(context, AccountActivity::class.java)) } }
        if (owned.isNotEmpty()) item { SectionTitle("Purchased") }
        items(owned, key = { it.slug }) { app -> AppRow(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
    }
}

@Composable private fun ProfileTile(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = StorePanel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = StoreAccent); Spacer(Modifier.width(14.dp)); Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium); Icon(Icons.Default.ChevronRight, null, tint = StoreMuted) }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = StoreMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PaymentDialog(app: StoreApp, onDismiss: () -> Unit, onSubmitted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<PaymentMethod>>(emptyList()) }
    var selected by remember { mutableStateOf<PaymentMethod?>(null) }
    var name by remember { mutableStateOf(StoreConfig.account(context)?.name.orEmpty()) }
    var phone by remember { mutableStateOf("") }
    var tx by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(app.slug) {
        runCatching { StoreApi.paymentMethods(context) }
            .onSuccess { methods = it; selected = it.firstOrNull() }
            .onFailure { message = it.message ?: "Could not load payment methods." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secure payment") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app, 58); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.name, fontWeight = FontWeight.Bold); Text("Premium app", color = StoreMuted, fontSize = 12.sp) }; Text("Rs ${app.pricePkr}", color = StoreGold, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp)); Text("Select payment method", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
                }
                items(methods) { method ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = method }, colors = CardDefaults.cardColors(containerColor = if (selected?.id == method.id) Color(0xFF1B2440) else StorePanel2), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF17202B)), contentAlignment = Alignment.Center) { Icon(Icons.Default.AccountBalanceWallet, null, tint = if (method.type == "easypaisa") StoreGood else StoreGold) }
                            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(method.label, fontWeight = FontWeight.SemiBold); Text("Secure provider checkout", color = StoreMuted, fontSize = 11.sp) }; RadioButton(selected = selected?.id == method.id, onClick = { selected = method })
                        }
                    }
                }
                item {
                    if (methods.isEmpty() && message.isBlank()) CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(28.dp))
                    Spacer(Modifier.height(12.dp)); Text("Payment confirmation", fontWeight = FontWeight.Bold); Text("Private merchant account details are not displayed here.", color = StoreMuted, fontSize = 11.sp); Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                    OutlinedTextField(tx, { tx = it }, label = { Text("Transaction reference") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (message.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(message, color = Color(0xFFFF9F9F), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && selected != null && name.isNotBlank() && phone.isNotBlank() && tx.isNotBlank(), onClick = {
                val method = selected ?: return@Button; busy = true; scope.launch { runCatching { StoreApi.submitPurchase(context, app, name, phone, method.id, tx) }.onSuccess { onSubmitted() }.onFailure { message = it.message ?: "Payment submission failed." }; busy = false }
            }) { Text(if (busy) "Submitting..." else "Confirm payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun AppIcon(app: StoreApp, size: Int) {
    if (app.iconUrl.isNotBlank()) AsyncImage(model = app.iconUrl, contentDescription = app.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(StorePanel2))
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Brush.linearGradient(listOf(StoreAccent, StoreBlue))), contentAlignment = Alignment.Center) { Text(app.name.take(1).uppercase(), color = Color.White, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Black) }
}

@Composable private fun Notice(text: String) { Card(Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = StorePanel), shape = RoundedCornerShape(18.dp)) { Text(text, color = StoreMuted, modifier = Modifier.padding(16.dp)) } }

private fun actionText(app: StoreApp, state: InstalledState): String = when {
    state.installed && !state.updateAvailable -> "Open"
    state.updateAvailable -> "Update"
    app.isPaid && !app.owned && app.purchaseStatus == "pending" -> "Pending"
    app.isPaid && !app.owned -> "Rs ${app.pricePkr}"
    else -> "Install"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
