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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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

private val V2Bg = Color(0xFF080A0F)
private val V2Surface = Color(0xFF11141A)
private val V2Surface2 = Color(0xFF171A22)
private val V2Accent = Color(0xFF8B7CFF)
private val V2Muted = Color(0xFFAEB3C2)
private val V2Good = Color(0xFF8FE0A7)
private val V2Divider = Color(0xFF242832)

class StoreV2Activity : ComponentActivity() {
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
        setContent { StoreThemeV2 { StoreRootV2() } }
    }
}

@Composable
private fun StoreThemeV2(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = V2Accent,
            secondary = V2Accent,
            background = V2Bg,
            surface = V2Surface,
            surfaceVariant = V2Surface2,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class V2Tab { Home, Updates, Downloads, Settings }

@Composable
private fun StoreRootV2() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(V2Tab.Home) }
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
            .onFailure { error = it.message ?: "Could not load apps." }
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
        AppDetailsV2(app = details!!, progress = progress[details!!.slug], onBack = { details = null }, onAction = { action(details!!) })
    } else {
        Scaffold(
            containerColor = V2Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D1016)) {
                    NavigationBarItem(selected = tab == V2Tab.Home, onClick = { tab = V2Tab.Home }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == V2Tab.Updates, onClick = { tab = V2Tab.Updates }, icon = { Icon(Icons.Default.SystemUpdate, null) }, label = { Text("Updates") })
                    NavigationBarItem(selected = tab == V2Tab.Downloads, onClick = { tab = V2Tab.Downloads }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("Downloads") })
                    NavigationBarItem(selected = tab == V2Tab.Settings, onClick = { tab = V2Tab.Settings }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    V2Tab.Home -> HomeV2(apps, query, { query = it }, loading, error, progress, { details = it }, ::action, ::reload)
                    V2Tab.Updates -> UpdatesV2(apps, progress, { details = it }, ::action, ::reload)
                    V2Tab.Downloads -> DownloadsV2(apps, progress, downloaded, ::action)
                    V2Tab.Settings -> SettingsV2()
                }
            }
        }
    }

    purchase?.let { app ->
        PurchaseV2(app = app, onDismiss = { purchase = null }, onSubmitted = { purchase = null; reload() })
    }
}

@Composable
private fun HomeV2(
    apps: List<StoreApp>,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    error: String,
    progress: SnapshotStateMap<String, Int>,
    onSelect: (StoreApp) -> Unit,
    onAction: (StoreApp) -> Unit,
    onRefresh: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filtered = apps.filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) }
    val featured = apps.firstOrNull { it.featured }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("APK App Store", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Apps, games and updates", color = V2Muted, fontSize = 13.sp)
                }
                FilledTonalIconButton(onClick = { context.startActivity(Intent(context, AccountActivity::class.java)) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Person, "Account")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search apps & games") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
        }
        item { Spacer(Modifier.height(14.dp)) }
        if (apps.isNotEmpty()) {
            item {
                val categories = listOf("All") + apps.map { it.category }.filter { it.isNotBlank() }.distinct()
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { cat ->
                        SuggestionChip(onClick = { onQuery(if (cat == "All") "" else cat) }, label = { Text(cat) })
                    }
                }
            }
        }
        featured?.let { app ->
            item { FeaturedV2(app, StoreApi.installedState(context, app), { onSelect(app) }, { onAction(app) }) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Apps for you", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (error.isNotBlank()) item { NoticeV2(error) }
        items(filtered, key = { it.slug }) { app ->
            AppListItemV2(app, StoreApi.installedState(context, app), progress[app.slug], { onSelect(app) }, { onAction(app) })
            HorizontalDivider(modifier = Modifier.padding(start = 112.dp, end = 20.dp), color = V2Divider)
        }
        if (!loading && error.isBlank() && filtered.isEmpty()) item { NoticeV2("No apps found.") }
    }
}

@Composable
private fun FeaturedV2(app: StoreApp, state: InstalledState, onOpen: () -> Unit, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF1C275D), Color(0xFF5A2D77)))).padding(20.dp)) {
            Column {
                Text("FEATURED", color = Color(0xFFC9C5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconV2(app, 78)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.shortDescription, color = Color(0xFFE2E3EA), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF11131A))) {
                    Text(actionLabelV2(app, state))
                }
            }
        }
    }
}

@Composable
private fun AppListItemV2(app: StoreApp, state: InstalledState, pct: Int?, onOpen: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconV2(app, 76)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(app.shortDescription, color = V2Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text("${app.category}  •  ${v2FormatSize(app.fileSize)}", color = Color(0xFF8F96AA), fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (pct != null && pct in 1..99) {
            CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
        } else {
            FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(actionLabelV2(app, state), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AppDetailsV2(app: StoreApp, progress: Int?, onBack: () -> Unit, onAction: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = StoreApi.installedState(context, app)
    LazyColumn(Modifier.fillMaxSize().background(V2Bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Text("App details", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconV2(app, 96)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(app.category, color = V2Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(if (app.isPaid) "PKR ${app.pricePkr}" else "Free", color = if (app.isPaid) Color(0xFFD6D0FF) else V2Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatV2("Version", app.versionName, Modifier.weight(1f))
                StatV2("Size", v2FormatSize(app.fileSize), Modifier.weight(1f))
                StatV2("Android", "${v2SdkLabel(app.minSdk)}+", Modifier.weight(1f))
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (progress != null && progress in 1..99) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Downloading $progress%") }
                } else {
                    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(actionLabelV2(app, state), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (app.screenshots.isNotEmpty()) {
            item {
                Text("Preview", modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots) { shot ->
                        AsyncImage(model = shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(205.dp).height(365.dp).clip(RoundedCornerShape(18.dp)).background(V2Surface2))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text("About this app", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(app.description.ifBlank { app.shortDescription }, color = Color(0xFFD4D7E0), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(24.dp))
                Text("What's new", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(app.changelog.ifBlank { "Version ${app.versionName}" }, color = V2Muted, fontSize = 13.sp, lineHeight = 19.sp)
                if (app.purchaseStatus == "pending") {
                    Spacer(Modifier.height(18.dp))
                    Text("Payment verification is pending. Access unlocks automatically after approval.", color = Color(0xFFFFD27D), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatV2(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(label, color = V2Muted, fontSize = 10.sp)
    }
}

@Composable
private fun PurchaseV2(app: StoreApp, onDismiss: () -> Unit, onSubmitted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<PaymentMethod>>(emptyList()) }
    var selected by remember { mutableStateOf<PaymentMethod?>(null) }
    var name by remember { mutableStateOf(StoreConfig.account(context)?.name.orEmpty()) }
    var phone by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(app.slug) {
        if (app.purchaseStatus != "pending") {
            runCatching { StoreApi.paymentMethods(context) }
                .onSuccess { methods = it; selected = it.firstOrNull() }
                .onFailure { message = it.message ?: "Could not load payment methods." }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (app.purchaseStatus == "pending") "Payment pending" else "Secure checkout") },
        text = {
            if (app.purchaseStatus == "pending") {
                Column {
                    Text("Your payment was submitted and is waiting for approval.")
                    Spacer(Modifier.height(8.dp))
                    Text("The Install button will unlock automatically after approval and refresh.", color = V2Muted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIconV2(app, 58)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, fontWeight = FontWeight.SemiBold)
                                Text("PKR ${app.pricePkr}", color = Color(0xFFD6D0FF), fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Payment method", color = V2Muted, fontSize = 12.sp)
                        methods.forEach { method ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { selected = method },
                                colors = CardDefaults.cardColors(containerColor = if (selected?.id == method.id) Color(0xFF262139) else V2Surface2),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(method.label, fontWeight = FontWeight.SemiBold)
                                        Text("Manual verification", color = V2Muted, fontSize = 11.sp)
                                    }
                                    if (selected?.id == method.id) Text("Selected", color = V2Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Current checkout uses manual verification. Your merchant number will be removed once the official Easypaisa merchant gateway is connected.", color = Color(0xFFFFD27D), fontSize = 11.sp, lineHeight = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(phone, { phone = it }, label = { Text("Customer phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(reference, { reference = it }, label = { Text("Transaction reference") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                    enabled = !busy && selected != null && name.isNotBlank() && phone.isNotBlank() && reference.isNotBlank(),
                    onClick = {
                        val method = selected ?: return@Button
                        busy = true
                        scope.launch {
                            runCatching { StoreApi.submitPurchase(context, app, name, phone, method.id, reference) }
                                .onSuccess { onSubmitted() }
                                .onFailure { message = it.message ?: "Could not submit payment." }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Submitting…" else "Submit payment") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun UpdatesV2(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, onSelect: (StoreApp) -> Unit, onAction: (StoreApp) -> Unit, onRefresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = apps.filter { it.owned && StoreApi.installedState(context, it).updateAvailable }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SimpleHeaderV2("Updates", "Keep installed apps up to date") }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("${updates.size} available", color = V2Muted, modifier = Modifier.weight(1f)); TextButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(5.dp)); Text("Check") } } }
        if (updates.isEmpty()) item { NoticeV2("You're up to date.") }
        items(updates, key = { it.slug }) { app -> AppListItemV2(app, StoreApi.installedState(context, app), progress[app.slug], { onSelect(app) }, { onAction(app) }) }
    }
}

@Composable
private fun DownloadsV2(apps: List<StoreApp>, progress: SnapshotStateMap<String, Int>, downloaded: SnapshotStateMap<String, File>, onAction: (StoreApp) -> Unit) {
    val active = apps.filter { progress.containsKey(it.slug) || downloaded.containsKey(it.slug) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SimpleHeaderV2("Downloads", "Current and recent APK downloads") }
        if (active.isEmpty()) item { NoticeV2("Downloads will appear here.") }
        items(active, key = { it.slug }) { app ->
            val pct = progress[app.slug]
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconV2(app, 64)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, fontWeight = FontWeight.SemiBold)
                    Text(if (pct != null && pct in 1..99) "Downloading $pct%" else "Ready to install", color = V2Muted, fontSize = 12.sp)
                }
                if (downloaded[app.slug] != null) FilledTonalButton(onClick = { onAction(app) }) { Text("Install") }
            }
        }
    }
}

@Composable
private fun SettingsV2() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var wifi by remember { mutableStateOf(StoreConfig.autoDownloadOnWifi(context)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SimpleHeaderV2("Settings", "Account, updates and security") }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { context.startActivity(Intent(context, AccountActivity::class.java)) }, colors = CardDefaults.cardColors(containerColor = V2Surface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, tint = V2Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("Account & purchases", fontWeight = FontWeight.SemiBold); Text("Sign in, profile and purchased apps", color = V2Muted, fontSize = 12.sp) }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = V2Surface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Auto-download on Wi-Fi", fontWeight = FontWeight.SemiBold); Text("Download available updates in the background", color = V2Muted, fontSize = 12.sp) }
                    androidx.compose.material3.Switch(checked = wifi, onCheckedChange = { wifi = it; StoreConfig.setAutoDownloadOnWifi(context, it) })
                }
            }
        }
    }
}

@Composable
private fun SimpleHeaderV2(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(subtitle, color = V2Muted, fontSize = 13.sp)
    }
}

@Composable
private fun NoticeV2(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), colors = CardDefaults.cardColors(containerColor = V2Surface), shape = RoundedCornerShape(18.dp)) {
        Text(text, modifier = Modifier.padding(18.dp), color = V2Muted)
    }
}

@Composable
private fun AppIconV2(app: StoreApp, size: Int) {
    if (app.iconUrl.isNotBlank()) {
        AsyncImage(model = app.iconUrl, contentDescription = app.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(V2Surface2))
    } else {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Brush.linearGradient(listOf(Color(0xFF7478FF), Color(0xFFA462FF)))), contentAlignment = Alignment.Center) {
            Text(app.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size / 2.3).sp)
        }
    }
}

private fun actionLabelV2(app: StoreApp, state: InstalledState): String = when {
    state.installed && !state.updateAvailable -> "Open"
    app.isPaid && !app.owned && app.purchaseStatus == "pending" -> "Pending"
    app.isPaid && !app.owned -> "PKR ${app.pricePkr}"
    state.updateAvailable -> "Update"
    else -> "Install"
}

private fun v2FormatSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 100) "${mb.toInt()} MB" else String.format("%.1f MB", mb)
}

private fun v2SdkLabel(sdk: Int): String = when (sdk) {
    35 -> "15"
    34 -> "14"
    33 -> "13"
    32, 31 -> "12"
    30 -> "11"
    29 -> "10"
    else -> sdk.toString()
}
