package com.mazhar.apkappstore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

private val S3Bg = Color(0xFF07090D)
private val S3Panel = Color(0xFF10141B)
private val S3Panel2 = Color(0xFF151A23)
private val S3Accent = Color(0xFF7B61FF)
private val S3Blue = Color(0xFF3398FF)
private val S3Muted = Color(0xFFA8AFBC)
private val S3Line = Color(0xFF232A35)
private val S3Good = Color(0xFF78E29A)
private val S3Gold = Color(0xFFFFC83D)

class StoreV3Activity : ComponentActivity() {
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
        setContent { StoreThemeV3 { StoreRootV3() } }
    }
}

@Composable
private fun StoreThemeV3(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = S3Accent,
            secondary = S3Blue,
            background = S3Bg,
            surface = S3Panel,
            surfaceVariant = S3Panel2,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class StoreTabV3 { Home, Categories, Search, Downloads, Profile }

@Composable
private fun StoreRootV3() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(StoreTabV3.Home) }
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
        AppDetailsV3(details!!, progress[details!!.slug], { details = null }, { action(details!!) })
    } else {
        Scaffold(
            containerColor = S3Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B0F15), tonalElevation = 0.dp) {
                    StoreBottomItem(tab == StoreTabV3.Home, "Home", Icons.Default.Home) { tab = StoreTabV3.Home }
                    StoreBottomItem(tab == StoreTabV3.Categories, "Categories", Icons.Default.GridView) { tab = StoreTabV3.Categories }
                    StoreBottomItem(tab == StoreTabV3.Search, "Search", Icons.Default.Search) { tab = StoreTabV3.Search }
                    StoreBottomItem(tab == StoreTabV3.Downloads, "Downloads", Icons.Default.Download) { tab = StoreTabV3.Downloads }
                    StoreBottomItem(tab == StoreTabV3.Profile, "Profile", Icons.Default.Person) { tab = StoreTabV3.Profile }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    StoreTabV3.Home -> HomeV3(apps, loading, error, progress, { details = it }, ::action, ::reload)
                    StoreTabV3.Categories -> CategoriesV3(apps, { details = it }, ::action)
                    StoreTabV3.Search -> SearchV3(apps, query, { query = it }, { details = it }, ::action)
                    StoreTabV3.Downloads -> DownloadsV3(apps, progress, downloaded, { details = it }, ::action)
                    StoreTabV3.Profile -> ProfileV3(apps, { details = it }, ::action)
                }
            }
        }
    }

    purchase?.let { app ->
        PaymentDialogV3(app, { purchase = null }, { purchase = null; reload() })
    }
}

@Composable
private fun StoreBottomItem(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label, fontSize = 10.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = S3Blue,
            selectedTextColor = S3Blue,
            indicatorColor = Color.Transparent,
            unselectedIconColor = S3Muted,
            unselectedTextColor = S3Muted
        )
    )
}

@Composable
private fun HomeV3(
    apps: List<StoreApp>, loading: Boolean, error: String,
    progress: SnapshotStateMap<String, Int>, onOpen: (StoreApp) -> Unit,
    onAction: (StoreApp) -> Unit, onRefresh: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val featured = apps.firstOrNull { it.featured } ?: apps.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("APK App Store", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Apps for a smarter you", color = S3Muted, fontSize = 12.sp)
                }
                FilledTonalIconButton(onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) }) {
                    Icon(Icons.Default.Person, "Account")
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171D26)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = S3Muted)
                    Spacer(Modifier.width(12.dp))
                    Text("Search apps, games, tools...", color = S3Muted)
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
        if (apps.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { CategoryChipV3("All", Icons.Default.Apps) }
                    items(apps.map { it.category }.filter { it.isNotBlank() }.distinct()) { CategoryChipV3(it, Icons.Default.Category) }
                }
            }
        }
        featured?.let { app -> item { HeroV3(app, { onOpen(app) }, { onAction(app) }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recommended for you", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (error.isNotBlank()) item { NoticeV3(error) }
        items(apps, key = { it.slug }) { app -> AppRowV3(app, StoreApi.installedState(context, app), progress[app.slug], { onOpen(app) }, { onAction(app) }) }
        if (!loading && error.isBlank() && apps.isEmpty()) item { NoticeV3("No apps published yet.") }
    }
}

@Composable
private fun CategoryChipV3(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = S3Panel2, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = S3Accent)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HeroV3(app: StoreApp, onOpen: () -> Unit, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF4024A7), Color(0xFF7E27C8)))).padding(20.dp)) {
            Column {
                Text("FEATURED APP", color = Color(0xFFE3DBFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconV3(app, 78)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.shortDescription, color = Color(0xFFE5E2EE), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text("★ 5.0   •   ${if (app.isPaid) "PKR ${app.pricePkr}" else "Free"}", color = S3Gold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF17121E)), shape = RoundedCornerShape(18.dp)) {
                    Text(actionTextV3(app, StoreApi.installedState(androidx.compose.ui.platform.LocalContext.current, app)))
                }
            }
        }
    }
}

@Composable
private fun AppRowV3(app: StoreApp, state: InstalledState, pct: Int?, onOpen: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconV3(app, 72)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(app.category, color = S3Muted, fontSize = 11.sp)
            Text("★ 5.0  •  ${app.versionName}  •  ${formatSizeV3(app.fileSize)}", color = S3Gold, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (pct != null && pct in 1..99) {
            CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
        } else {
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)) {
                Text(actionTextV3(app, state), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 106.dp, end = 20.dp), color = S3Line)
}

@Composable
private fun AppDetailsV3(app: StoreApp, pct: Int?, onBack: () -> Unit, onAction: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = StoreApi.installedState(context, app)
    LazyColumn(Modifier.fillMaxSize().background(S3Bg), contentPadding = PaddingValues(bottom = 34.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { }) { Icon(Icons.Default.Share, "Share") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconV3(app, 98)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(app.category, color = S3Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (app.isPaid) "In-app purchase • PKR ${app.pricePkr}" else "Free download", color = if (app.isPaid) S3Gold else S3Good, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatV3("5.0", "★★★★★")
                StatV3(formatSizeV3(app.fileSize), "Size")
                StatV3("${app.minSdk}+", "Android API")
            }
        }
        item {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = S3Blue)
            ) {
                if (pct != null && pct in 1..99) Text("Downloading $pct%") else Text(actionTextV3(app, state), fontWeight = FontWeight.Bold)
            }
        }
        if (app.screenshots.isNotEmpty()) {
            item { SectionTitleV3("Screenshots") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots) { shot ->
                        AsyncImage(model = shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(158.dp).height(280.dp).clip(RoundedCornerShape(18.dp)).background(S3Panel))
                    }
                }
            }
        }
        item { SectionTitleV3("About this app") }
        item { Text(app.description.ifBlank { app.shortDescription }, color = Color(0xFFD4D8E1), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        item { SectionTitleV3("Ratings & reviews") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("5.0", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("★★★★★", color = S3Gold, fontSize = 18.sp)
                    Text("Rating display", color = S3Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    RatingBarV3(5, 1f); RatingBarV3(4, .12f); RatingBarV3(3, .04f); RatingBarV3(2, .02f); RatingBarV3(1, .01f)
                }
            }
        }
        item { SectionTitleV3("What's new") }
        item { Text(app.changelog.ifBlank { "Version ${app.versionName}" }, color = S3Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
    }
}

@Composable private fun StatV3(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, color = S3Muted, fontSize = 10.sp)
    }
}

@Composable private fun RatingBarV3(stars: Int, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stars.toString(), color = S3Muted, fontSize = 10.sp, modifier = Modifier.width(16.dp))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = S3Blue, trackColor = S3Line)
    }
    Spacer(Modifier.height(5.dp))
}

@Composable private fun SectionTitleV3(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp))
}

@Composable
private fun CategoriesV3(apps: List<StoreApp>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories = apps.map { it.category.ifBlank { "Apps" } }.distinct()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeaderV3("Categories", "Browse by type") }
        items(categories) { category ->
            val group = apps.filter { it.category.ifBlank { "Apps" } == category }
            SectionTitleV3(category)
            group.forEach { app -> AppRowV3(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
        }
        if (categories.isEmpty()) item { NoticeV3("Categories will appear after apps are published.") }
    }
}

@Composable
private fun SearchV3(apps: List<StoreApp>, query: String, onQuery: (String) -> Unit, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val result = apps.filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) || it.shortDescription.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeaderV3("Search", "Find your favorite apps") }
        item {
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text("Search apps & games") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(22.dp))
        }
        item { Spacer(Modifier.height(16.dp)) }
        items(result, key = { it.slug }) { app -> AppRowV3(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
        if (result.isEmpty()) item { NoticeV3("No matching apps.") }
    }
}

@Composable
private fun DownloadsV3(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, downloaded: SnapshotStateMap<String, File>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val visible = apps.filter { progress.containsKey(it.slug) || downloaded.containsKey(it.slug) || StoreApi.installedState(context, it).installed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeaderV3("My Apps", "Downloads, installed apps and updates") }
        items(visible, key = { it.slug }) { app -> AppRowV3(app, StoreApi.installedState(context, app), progress[app.slug], { onOpen(app) }, { onAction(app) }) }
        if (visible.isEmpty()) item { NoticeV3("Your downloaded and installed apps will appear here.") }
    }
}

@Composable
private fun ProfileV3(apps: List<StoreApp>, onOpen: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val account = StoreConfig.account(context)
    val owned = apps.filter { it.owned && it.isPaid }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeaderV3("My Profile", "Manage account, purchases and settings") }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = S3Panel), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(S3Accent, S3Blue))), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account?.name?.ifBlank { "APK App Store User" } ?: "Sign in to your account", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(account?.email ?: "Sync purchases across your account", color = S3Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = S3Muted)
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
        item { ProfileTileV3("Account & security", Icons.Default.Security) { context.startActivity(Intent(context, AccountActivity::class.java)) } }
        item { ProfileTileV3("Purchased apps", Icons.Default.ShoppingBag) { } }
        item { ProfileTileV3("Updates", Icons.Default.SystemUpdate) { } }
        item { ProfileTileV3("Help & support", Icons.Default.Help) { } }
        if (owned.isNotEmpty()) item { SectionTitleV3("Purchased") }
        items(owned, key = { it.slug }) { app -> AppRowV3(app, StoreApi.installedState(context, app), null, { onOpen(app) }, { onAction(app) }) }
    }
}

@Composable private fun ProfileTileV3(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = S3Panel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = S3Accent)
            Spacer(Modifier.width(14.dp))
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ChevronRight, null, tint = S3Muted)
        }
    }
}

@Composable private fun PageHeaderV3(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = S3Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PaymentDialogV3(app: StoreApp, onDismiss: () -> Unit, onSubmitted: () -> Unit) {
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
                        AppIconV3(app, 58)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontWeight = FontWeight.Bold)
                            Text("Premium app", color = S3Muted, fontSize = 12.sp)
                        }
                        Text("Rs ${app.pricePkr}", color = S3Gold, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Select payment method", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }
                items(methods) { method ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = method },
                        colors = CardDefaults.cardColors(containerColor = if (selected?.id == method.id) Color(0xFF1B2440) else S3Panel2),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF17202B)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AccountBalanceWallet, null, tint = if (method.type == "easypaisa") S3Good else S3Gold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(method.label, fontWeight = FontWeight.SemiBold)
                                Text("Secure provider checkout", color = S3Muted, fontSize = 11.sp)
                            }
                            RadioButton(selected = selected?.id == method.id, onClick = { selected = method })
                        }
                    }
                }
                item {
                    if (methods.isEmpty() && message.isBlank()) CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Payment confirmation", fontWeight = FontWeight.Bold)
                    Text("Your private merchant account details are not shown on this screen.", color = S3Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(tx, { tx = it }, label = { Text("Transaction reference") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = Color(0xFFFF9F9F), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && selected != null && name.isNotBlank() && phone.isNotBlank() && tx.isNotBlank(),
                onClick = {
                    val method = selected ?: return@Button
                    busy = true
                    scope.launch {
                        runCatching { StoreApi.submitPurchase(context, app, name, phone, method.id, tx) }
                            .onSuccess { onSubmitted() }
                            .onFailure { message = it.message ?: "Payment submission failed." }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Submitting..." else "Confirm payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AppIconV3(app: StoreApp, size: Int) {
    if (app.iconUrl.isNotBlank()) {
        AsyncImage(model = app.iconUrl, contentDescription = app.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(S3Panel2))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Brush.linearGradient(listOf(S3Accent, S3Blue))), contentAlignment = Alignment.Center) {
            Text(app.name.take(1).uppercase(), color = Color.White, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable private fun NoticeV3(text: String) {
    Card(Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = S3Panel), shape = RoundedCornerShape(18.dp)) {
        Text(text, color = S3Muted, modifier = Modifier.padding(16.dp))
    }
}

private fun actionTextV3(app: StoreApp, state: InstalledState): String = when {
    state.installed && !state.updateAvailable -> "Open"
    state.updateAvailable -> "Update"
    app.isPaid && !app.owned && app.purchaseStatus == "pending" -> "Pending"
    app.isPaid && !app.owned -> "Rs ${app.pricePkr}"
    else -> "Install"
}

private fun formatSizeV3(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
