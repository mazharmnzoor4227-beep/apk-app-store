package com.mazhar.apkappstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val AccountBg = Color(0xFF080A0F)
private val AccountSurface = Color(0xFF11141C)
private val AccountSurface2 = Color(0xFF171B25)
private val AccountAccent = Color(0xFF7180FF)
private val AccountAccent2 = Color(0xFF9B6CFF)
private val AccountMuted = Color(0xFFA5ACBA)
private val AccountGood = Color(0xFF8DDDAA)

class AccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccountAccent,
                    secondary = AccountAccent2,
                    background = AccountBg,
                    surface = AccountSurface,
                    surfaceVariant = AccountSurface2,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) { AccountRoot(onClose = { finish() }) }
        }
    }
}

private enum class AccountPage { Main, Email, Purchases, Privacy, Terms, Security, About }

@Composable
private fun AccountRoot(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(AccountPage.Main) }
    var account by remember { mutableStateOf(StoreConfig.account(context)) }
    var busy by remember { mutableStateOf(false) }

    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    LaunchedEffect(Unit) {
        if (StoreConfig.authToken(context).isNotBlank()) {
            runCatching { StoreApi.me(context) }
                .onSuccess { StoreConfig.updateAccount(context, it); account = it }
                .onFailure { StoreConfig.clearSession(context); account = null }
        }
    }

    BackHandler {
        if (page == AccountPage.Main) onClose() else page = AccountPage.Main
    }

    Scaffold(containerColor = AccountBg, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                AccountPage.Main -> AccountHome(
                    account = account,
                    busy = busy,
                    onClose = onClose,
                    onGoogle = { notice("Google sign-in needs the Google OAuth client ID to be added next. Email login is already connected.") },
                    onEmail = { page = AccountPage.Email },
                    onPurchases = {
                        if (account == null) notice("Sign in first to sync paid apps and purchases.") else page = AccountPage.Purchases
                    },
                    onLogout = {
                        busy = true
                        scope.launch {
                            StoreApi.logout(context)
                            account = null
                            busy = false
                            notice("Signed out")
                        }
                    },
                    onPrivacy = { page = AccountPage.Privacy },
                    onTerms = { page = AccountPage.Terms },
                    onSecurity = { page = AccountPage.Security },
                    onAbout = { page = AccountPage.About }
                )
                AccountPage.Email -> EmailSignInScreen(
                    busy = busy,
                    onBack = { page = AccountPage.Main },
                    onSubmit = { create, name, email, password ->
                        busy = true
                        scope.launch {
                            runCatching {
                                if (create) StoreApi.register(context, name, email, password)
                                else StoreApi.login(context, email, password)
                            }.onSuccess { session ->
                                StoreConfig.saveSession(context, session)
                                account = session.user
                                page = AccountPage.Main
                                notice(if (create) "Account created and signed in" else "Signed in")
                            }.onFailure { notice(it.message ?: "Sign-in failed") }
                            busy = false
                        }
                    }
                )
                AccountPage.Purchases -> PurchasesScreen(onBack = { page = AccountPage.Main })
                AccountPage.Privacy -> LegalTextScreen("Privacy Policy", loadAssetText(context, "privacy_policy.txt")) { page = AccountPage.Main }
                AccountPage.Terms -> LegalTextScreen("Terms of Use", loadAssetText(context, "terms_of_use.txt")) { page = AccountPage.Main }
                AccountPage.Security -> SecurityScreen { page = AccountPage.Main }
                AccountPage.About -> AboutScreen { page = AccountPage.Main }
            }
        }
    }
}

@Composable
private fun AccountHome(
    account: AccountUser?,
    busy: Boolean,
    onClose: () -> Unit,
    onGoogle: () -> Unit,
    onEmail: () -> Unit,
    onPurchases: () -> Unit,
    onLogout: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    onSecurity: () -> Unit,
    onAbout: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { PageHeader("Account", onClose) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(28.dp)
            ) {
                Box(
                    Modifier.background(Brush.linearGradient(listOf(Color(0xFF232C6D), Color(0xFF512B78)))).padding(22.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(38.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.name?.ifBlank { account.email } ?: "Guest", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(account?.email ?: "Sign in to sync purchases and restore paid apps across devices.", color = Color(0xFFE1E4F0), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (account == null) {
            item {
                Button(
                    onClick = onGoogle,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF161820)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFF1F3F4)), contentAlignment = Alignment.Center) {
                        Text("G", fontWeight = FontWeight.Black, color = Color(0xFF4285F4))
                    }
                    Spacer(Modifier.width(12.dp)); Text("Continue with Google", fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                OutlinedButton(
                    onClick = onEmail,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.Email, null); Spacer(Modifier.width(10.dp)); Text("Continue with email")
                }
            }
        } else {
            item {
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(52.dp),
                    shape = RoundedCornerShape(18.dp)
                ) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
            }
        }

        item { SectionLabel("ACCOUNT") }
        item { AccountRow(Icons.Default.ShoppingBag, "My purchases", "Paid apps and purchase status", onPurchases) }
        item { SectionLabel("PRIVACY & LEGAL") }
        item { AccountRow(Icons.Default.Policy, "Privacy Policy", "How data and permissions are handled", onPrivacy) }
        item { AccountRow(Icons.Default.Gavel, "Terms of Use", "Rules for downloads, purchases and updates", onTerms) }
        item { AccountRow(Icons.Default.Security, "Security", "Accounts, APK checks and private data", onSecurity) }
        item { AccountRow(Icons.Default.Info, "About APK App Store", "Version and service information", onAbout) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp), color = AccountMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun AccountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AccountSurface), shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1E2330)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color(0xFFC6CCFF))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = AccountMuted, fontSize = 11.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = AccountMuted)
        }
    }
}

@Composable
private fun EmailSignInScreen(
    busy: Boolean,
    onBack: () -> Unit,
    onSubmit: (Boolean, String, String, String) -> Unit
) {
    var createAccount by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader(if (createAccount) "Create account" else "Email sign in", onBack) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = AccountSurface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    if (createAccount) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), leadingIcon = { Icon(Icons.Default.Email, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmit(createAccount, name, email, password) },
                        enabled = !busy && email.isNotBlank() && password.length >= 8 && (!createAccount || name.length >= 2),
                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)
                    ) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(if (createAccount) "Create account" else "Sign in")
                    }
                    TextButton(onClick = { createAccount = !createAccount }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (createAccount) "Already have an account? Sign in" else "New here? Create an account")
                    }
                }
            }
        }
        item { Text("Your password is sent over HTTPS and stored only as a salted password hash on the backend.", color = AccountMuted, fontSize = 11.sp, modifier = Modifier.padding(22.dp)) }
    }
}

@Composable
private fun PurchasesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var purchases by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { StoreApi.apps(context).filter { it.isPaid && (it.owned || it.purchaseStatus != "none") } }
            .onSuccess { purchases = it }
            .onFailure { error = it.message ?: "Could not load purchases" }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("My purchases", onBack) }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
        if (!loading && error.isBlank() && purchases.isEmpty()) item { Text("No paid app purchases yet.", color = AccountMuted, modifier = Modifier.padding(22.dp)) }
        items(purchases, key = { it.slug }) { app ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AccountSurface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Apps, null, tint = Color(0xFFC6CCFF)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, fontWeight = FontWeight.SemiBold)
                        Text(if (app.owned) "Approved • PKR ${app.pricePkr}" else "${app.purchaseStatus.replaceFirstChar { it.uppercase() }} • PKR ${app.pricePkr}", color = if (app.owned) AccountGood else AccountMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalTextScreen(title: String, text: String, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
        item { PageHeader(title, onBack) }
        item { Text(text, color = Color(0xFFD5D9E4), fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
    }
}

@Composable
private fun SecurityScreen(onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("Security", onBack) }
        item { SecurityCard(Icons.Default.Lock, "Account sessions", "Email accounts use expiring server sessions. Signing out revokes the current session.") }
        item { SecurityCard(Icons.Default.Verified, "Verified APK downloads", "Published APK releases carry SHA-256 metadata and are delivered through the store backend.") }
        item { SecurityCard(Icons.Default.AdminPanelSettings, "Private admin access", "Publishing and payment approvals stay behind the separate admin key.") }
    }
}

@Composable
private fun SecurityCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AccountSurface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = AccountGood); Spacer(Modifier.width(12.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text(text, color = AccountMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { PageHeader("About", onBack) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(containerColor = AccountSurface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ShoppingBag, null, modifier = Modifier.size(58.dp), tint = Color(0xFFC7CEFF)); Spacer(Modifier.height(10.dp))
                    Text("APK App Store", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Version ${BuildConfig.VERSION_NAME}", color = AccountMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("A self-hosted Android marketplace for published APKs, paid-app access and updates.", color = AccountMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

private fun loadAssetText(context: android.content.Context, name: String): String = runCatching {
    context.assets.open(name).bufferedReader().use { it.readText() }
}.getOrElse { "Unable to load this document." }
