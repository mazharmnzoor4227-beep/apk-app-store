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

private val Bg = Color(0xFF080A0F)
private val Surface = Color(0xFF11141C)
private val Surface2 = Color(0xFF171B25)
private val Accent = Color(0xFF7180FF)
private val Accent2 = Color(0xFF9B6CFF)
private val Muted = Color(0xFFA5ACBA)
private val Good = Color(0xFF8DDDAA)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scheduleUpdateChecks()
        setContent { ApkStoreTheme { StoreAppScreen() } }
    }

    private fun scheduleUpdateChecks() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "apk-store-update-check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

@Composable
private fun ApkStoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent2,
            background = Bg,
            surface = Surface,
            surfaceVariant = Surface2,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class Tab { Home, Updates, Downloads, Settings }

@Composable
private fun StoreAppScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Home) }
    var apps by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<StoreApp?>(null) }
    var purchaseApp by remember { mutableStateOf<StoreApp?>(null) }
    val progress = remember { mutableStateMapOf<String, Int>() }
    val downloaded = remember { mutableStateMapOf<String, File>() }

    fun reload() { refresh++ }

    LaunchedEffect(refresh) {
        if (StoreConfig.backendUrl(context).isBlank()) {
            apps = emptyList()
            error = "Store service is being connected. Please try again later."
            return@LaunchedEffect
        }
        loading = true
        error = ""
        runCatching { StoreApi.apps(context) }
            .onSuccess { apps = it }
            .onFailure { error = it.message ?: "Could not load the store." }
        loading = false
    }

    fun startDownload(app: StoreApp) {
        val state = StoreApi.installedState(context, app)
        if (state.installed && !state.updateAvailable) {
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it) }
            return
        }
        if (app.isPaid && !app.owned) {
            purchaseApp = app
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        scope.launch {
            progress[app.slug] = 1
            runCatching {
                AppDownloader.download(context, app) { pct -> progress[app.slug] = pct.coerceAtLeast(1) }
            }.onSuccess { file ->
                progress[app.slug] = 100
                downloaded[app.slug] = file
                AppDownloader.requestInstall(context, file)
            }.onFailure {
                progress.remove(app.slug)
                if (it.message?.isNotBlank() == true) error = it.message.orEmpty()
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0E1118)) {
                NavigationBarItem(selected = tab == Tab.Home, onClick = { tab = Tab.Home }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == Tab.Updates, onClick = { tab = Tab.Updates }, icon = { Icon(Icons.Default.SystemUpdate, null) }, label = { Text("Updates") })
                NavigationBarItem(selected = tab == Tab.Downloads, onClick = { tab = Tab.Downloads }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloads") })
                NavigationBarItem(selected = tab == Tab.Settings, onClick = { tab = Tab.Settings }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(apps, query, { query = it }, loading, error, progress, { selected = it }, ::startDownload, ::reload)
                Tab.Updates -> UpdatesScreen(apps, progress, { selected = it }, ::startDownload, ::reload)
                Tab.Downloads -> DownloadsScreen(apps, progress, downloaded, ::startDownload)
                Tab.Settings -> SettingsScreen()
            }
        }
    }

    selected?.let { app ->
        AppDetailsDialog(app = app, onDismiss = { selected = null }, onAction = { startDownload(app) })
    }
    purchaseApp?.let { app ->
        PurchaseDialog(
            app = app,
            onDismiss = { purchaseApp = null },
            onSubmitted = {
                purchaseApp = null
                reload()
            }
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun HomeScreen(
    apps: List<StoreApp>, query: String, onQuery: (String) -> Unit, loading: Boolean, error: String,
    progress: SnapshotStateMap<String, Int>, onSelect: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onRefresh: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filtered = apps.filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) }
    val featured = apps.firstOrNull { it.featured }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("APK App Store", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Discover, install and update apps in one place.", color = Muted, fontSize = 13.sp)
                }
                FilledTonalIconButton(
                    onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.Person, "Account")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
        }
        item { Spacer(Modifier.height(18.dp)) }
        featured?.let { app -> item { FeaturedCard(app, StoreApi.installedState(context, app), { onSelect(app) }, { onAction(app) }) } }
        if (apps.isNotEmpty()) {
            item {
                val categories = apps.map { it.category }.filter { it.isNotBlank() }.distinct()
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { cat -> SuggestionChip(onClick = { onQuery(cat) }, label = { Text(cat) }) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (error.isNotBlank()) item { MessageCard(error) }
        items(filtered, key = { it.slug }) { app ->
            AppRow(app, StoreApi.installedState(context, app), progress[app.slug], { onSelect(app) }, { onAction(app) })
        }
        if (!loading && error.isBlank() && filtered.isEmpty()) item { MessageCard("No apps published yet.") }
    }
}

private fun actionLabel(app: StoreApp, state: InstalledState): String = when {
    state.installed && !state.updateAvailable -> "Open"
    app.isPaid && !app.owned && app.purchaseStatus == "pending" -> "Pending"
    app.isPaid && !app.owned -> "Rs ${app.pricePkr}"
    state.updateAvailable -> "Update"
    else -> "Install"
}

@Composable
private fun FeaturedCard(app: StoreApp, state: InstalledState, onClick: () -> Unit, onAction: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF27317D), Color(0xFF5E2E86))))
            .clickable(onClick = onClick)
            .padding(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FEATURED", color = Color(0xFFC7CEFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (app.isPaid) "PKR ${app.pricePkr}" else "FREE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 68)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(app.shortDescription, color = Color(0xFFE3E5EF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF171B25))) {
                Text(actionLabel(app, state))
            }
        }
    }
}

@Composable
private fun AppRow(app: StoreApp, state: InstalledState, pct: Int?, onClick: () -> Unit, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 58)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(if (app.isPaid) "PKR ${app.pricePkr}" else "FREE", color = if (app.isPaid) Color(0xFFC7CEFF) else Good, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(app.shortDescription, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${app.versionName} • ${formatSize(app.fileSize)}", color = Color(0xFF7F8BFF), fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (pct != null && pct in 1..99) {
                CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
            } else {
                FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(actionLabel(app, state))
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: StoreApp, size: Int) {
    if (app.iconUrl.isNotBlank()) {
        AsyncImage(model = app.iconUrl, contentDescription = app.name, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
            Text(app.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size / 2.4).sp)
        }
    }
}

@Composable
private fun UpdatesScreen(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, onSelect: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onRefresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = apps.filter { it.owned && StoreApi.installedState(context, it).updateAvailable }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Updates", "New versions for apps installed on this phone.") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("${updates.size} available", color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Check now") }
            }
        }
        if (updates.isEmpty()) item { MessageCard("You're up to date.") }
        items(updates, key = { it.slug }) { app -> AppRow(app, StoreApi.installedState(context, app), progress[app.slug], { onSelect(app) }, { onAction(app) }) }
    }
}

@Composable
private fun DownloadsScreen(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, downloaded: SnapshotStateMap<String, File>, onAction: (StoreApp) -> Unit) {
    val active = apps.filter { progress.containsKey(it.slug) || downloaded.containsKey(it.slug) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Downloads", "Verified APK downloads and ready-to-install updates.") }
        if (active.isEmpty()) item { MessageCard("Downloads will appear here while you install or update apps.") }
        items(active, key = { it.slug }) { app ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 52); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, fontWeight = FontWeight.SemiBold)
                        val pct = progress[app.slug] ?: 0
                        if (pct in 1..99) {
                            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text("$pct%", color = Muted, fontSize = 11.sp)
                        } else Text("Ready to install", color = Good, fontSize = 12.sp)
                    }
                    if (downloaded[app.slug] != null) TextButton(onClick = { onAction(app) }) { Text("Install") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var autoWifi by remember { mutableStateOf(StoreConfig.autoDownloadOnWifi(context)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { Header("Settings", "Control your account, updates and download security.") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable {
                    context.startActivity(Intent(context, AccountActivity::class.java))
                },
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Account & profile", fontWeight = FontWeight.SemiBold)
                        Text("Sign in, purchases, privacy and terms", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-download updates on Wi-Fi", fontWeight = FontWeight.SemiBold)
                        Text("Downloads an available update in the background. Android still asks you to confirm installation.", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = autoWifi, onCheckedChange = { autoWifi = it; StoreConfig.setAutoDownloadOnWifi(context, it) })
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = Good)
                        Spacer(Modifier.width(8.dp))
                        Text("Verified downloads", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Every published APK can be verified with SHA-256. Paid APK downloads stay locked until payment is approved.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Automatic update checks", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("APK App Store checks periodically for newer versions. Purchased apps keep update access after approval.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AppDetailsDialog(app: StoreApp, onDismiss: () -> Unit, onAction: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = StoreApi.installedState(context, app)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 56)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(app.name)
                    Text("${app.category} • ${if (app.isPaid) "PKR ${app.pricePkr}" else "Free"}", color = Muted, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column {
                Text(app.description.ifBlank { app.shortDescription }, color = Color(0xFFD3D7E1))
                Spacer(Modifier.height(14.dp))
                Text("What's new", fontWeight = FontWeight.SemiBold)
                Text(app.changelog.ifBlank { "Version ${app.versionName}" }, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("Android ${sdkLabel(app.minSdk)}+ • ${formatSize(app.fileSize)}", color = Color(0xFF8F9AFF), fontSize = 12.sp)
                if (app.purchaseStatus == "pending") {
                    Spacer(Modifier.height(10.dp))
                    Text("Payment submitted • Waiting for approval", color = Color(0xFFFFD27D), fontSize = 12.sp)
                }
            }
        },
        confirmButton = { Button(onClick = onAction) { Text(actionLabel(app, state)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PurchaseDialog(app: StoreApp, onDismiss: () -> Unit, onSubmitted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<PaymentMethod>>(emptyList()) }
    var selectedMethod by remember { mutableStateOf<PaymentMethod?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var tx by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(app.slug) {
        if (app.purchaseStatus != "pending") {
            runCatching { StoreApi.paymentMethods(context) }
                .onSuccess { methods = it; selectedMethod = it.firstOrNull() }
                .onFailure { message = it.message ?: "Could not load payment methods" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (app.purchaseStatus == "pending") "Payment pending" else "Buy ${app.name}") },
        text = {
            if (app.purchaseStatus == "pending") {
                Column {
                    Text("Your payment details were submitted for this app.")
                    Spacer(Modifier.height(10.dp))
                    Text("Once approved in the store admin panel, the Install button will unlock automatically after refresh.", color = Muted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 500.dp)) {
                    item {
                        Text("Price: PKR ${app.pricePkr}", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (methods.isEmpty() && message.isBlank()) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        methods.forEach { method ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedMethod = method },
                                colors = CardDefaults.cardColors(containerColor = if (selectedMethod?.id == method.id) Color(0xFF242A43) else Surface2),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedMethod?.id == method.id, onClick = { selectedMethod = method })
                                    Column {
                                        Text(method.label, fontWeight = FontWeight.SemiBold)
                                        if (method.accountTitle.isNotBlank()) Text(method.accountTitle, color = Muted, fontSize = 11.sp)
                                        Text(method.accountValue, color = Color(0xFFC7CEFF), fontSize = 12.sp)
                                        if (method.instructions.isNotBlank()) Text(method.instructions, color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(tx, { tx = it }, label = { Text("Transaction ID / reference") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(message, color = Color(0xFFFF9A9A), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (app.purchaseStatus == "pending") {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(
                    enabled = !loading && selectedMethod != null && name.isNotBlank() && phone.isNotBlank() && tx.isNotBlank(),
                    onClick = {
                        val method = selectedMethod ?: return@Button
                        loading = true
                        message = ""
                        scope.launch {
                            runCatching { StoreApi.submitPurchase(context, app, name, phone, method.id, tx) }
                                .onSuccess { onSubmitted() }
                                .onFailure { message = it.message ?: "Could not submit payment" }
                            loading = false
                        }
                    }
                ) { Text(if (loading) "Submitting..." else "Submit payment") }
            }
        },
        dismissButton = { if (app.purchaseStatus != "pending") TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MessageCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(20.dp)) {
        Text(text, color = Muted, modifier = Modifier.padding(18.dp))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun sdkLabel(minSdk: Int): String = when (minSdk) {
    29 -> "10"; 30 -> "11"; 31, 32 -> "12"; 33 -> "13"; 34 -> "14"; 35 -> "15"; else -> "API $minSdk"
}
