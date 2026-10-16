@file:OptIn(ExperimentalMaterial3Api::class)

package com.jobwatch.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.createChannel(this)
        setContent { JobWatchTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        resumes.value = resumes.value + 1 // re-read permissions the user may have just changed
        // Both do nothing while background checks are turned off.
        Scheduler.scheduleNext(this)
        thread { Checker.run(applicationContext) } // catch up if the last check was a while ago
    }

    companion object {
        val resumes = MutableStateFlow(0)
    }
}

@Composable
fun JobWatchTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF7AA2FF))
        else -> lightColorScheme(primary = Color(0xFF2453D6))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) // the LinkedIn app if installed, else the browser
}

@Composable
fun App() {
    val context = LocalContext.current
    val version by Store.changes.collectAsState()
    val resumed by MainActivity.resumes.collectAsState()
    val data = remember(version) { Store.load(context) }
    var screen by rememberSaveable { mutableStateOf("main") }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        MainActivity.resumes.value = MainActivity.resumes.value + 1
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** A check the user asked for. */
    fun runCheck(labels: Set<String>?) {
        if (busy) return
        busy = true
        scope.launch {
            val jobs = withContext(Dispatchers.IO) { Checker.run(context.applicationContext, labels, force = true) }
            busy = false
            snackbar.showSnackbar(when {
                Store.load(context).limited -> "LinkedIn asked to slow down. Try again in a few minutes."
                jobs.isNullOrEmpty() -> "No new jobs"
                else -> Messages.headline(jobs)
            })
        }
    }

    val setChecks: (Boolean) -> Unit = { on ->
        Store.update(context) { it.copy(checksOn = on) }
        if (on) {
            Scheduler.scheduleNext(context)
            runCheck(null)
        } else {
            Scheduler.stop(context)
            scope.launch { snackbar.showSnackbar("Background checks stopped. Nothing runs until you turn them back on.") }
        }
    }

    if (screen == "alerts") {
        BackHandler { screen = "main" }
        AlertsScreen(onBack = { screen = "main" })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job Watch") },
                actions = {
                    IconButton(onClick = { runCheck(null) }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, contentDescription = "Check now")
                    }
                    IconButton(onClick = { screen = "alerts" }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Email and WhatsApp alerts")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About Job Watch")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MainList(data, resumed, padding, onSearchAdded = { label -> runCheck(setOf(label)) },
            onChecksChanged = setChecks)
    }
    if (showAbout) AboutDialog(data, onClose = { showAbout = false })
}

@Composable
fun AboutDialog(data: AppData, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text("About Job Watch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version ${app.versionName} (build ${PackageInfoCompat.getLongVersionCode(app)})",
                    style = MaterialTheme.typography.titleMedium)
                if (app.lastUpdateTime > 0) {
                    Text("Installed ${timeText(app.lastUpdateTime)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Text("Checks your LinkedIn job searches every ${Scheduler.INTERVAL_MINUTES} minutes and tells you " +
                    "about new and reposted jobs. It runs entirely on this phone.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("${data.searches.size} searches · ${data.seen.size} jobs found so far",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
    )
}

@Composable
fun MainList(
    data: AppData,
    resumed: Int,
    padding: PaddingValues,
    onSearchAdded: (String) -> Unit,
    onChecksChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var toRemove by remember { mutableStateOf<Search?>(null) }
    val jobs = data.jobs.filter { filter == null || it.search == filter }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "checks") { ChecksCard(data, onChecksChanged) }
        if (data.checksOn) item(key = "setup") { BackgroundSetup(resumed) }

        item { SectionTitle("Searches") }
        items(data.searches, key = { "search:" + it.label }) { search ->
            SearchRow(search, onRemove = { toRemove = search })
        }
        item(key = "add") { AddSearch(data, onSearchAdded) }

        item { SectionTitle("Jobs (${data.jobs.size})") }
        if (data.searches.isNotEmpty()) {
            item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    data.searches.forEach { search ->
                        FilterChip(selected = filter == search.label, onClick = { filter = search.label }, label = { Text(search.label) })
                    }
                }
            }
        }
        if (jobs.isEmpty()) {
            item {
                Text("No jobs yet. New ones show up here as they're found.", color = muted,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        items(jobs, key = { "job:" + it.id }) { job ->
            JobRow(job) {
                // The "new" dot stays until you open that job.
                if (job.isNew) Store.update(context) { d -> d.copy(jobs = d.jobs.map { if (it.id == job.id) it.copy(isNew = false) else it }) }
                openUrl(context, job.url)
            }
        }
    }

    toRemove?.let { search ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Stop watching this search?") },
            text = { Text(search.label) },
            confirmButton = {
                TextButton(onClick = {
                    Store.update(context) { d -> d.copy(searches = d.searches.filter { it.label != search.label }) }
                    if (filter == search.label) filter = null
                    toRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancel") } },
        )
    }
}

/** The on/off switch for background checks, and what they're doing right now. */
@Composable
fun ChecksCard(data: AppData, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val next = remember(data) { Scheduler.nextCheckAt(context) }
    val nextText = if (next > now) "next check about ${timeText(next)}" else "next check"
    val limited = data.checksOn && data.limited
    val detail = when {
        !data.checksOn -> "Off: nothing runs in the background. Tap the refresh button at the top to check once."
        limited -> "LinkedIn asked to slow down. Trying again at the $nextText; jobs posted meanwhile won't be missed."
        else -> "Last check ${timeText(data.lastRun)}" + if (next > now) " · next about ${timeText(next)}" else ""
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = data.checksOn, onValueChange = onChange, role = Role.Switch)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Check every ${Scheduler.INTERVAL_MINUTES} minutes", style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = if (limited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = data.checksOn, onCheckedChange = null)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

/** Shows only what still needs doing for checks to run on time in the background. */
@Composable
fun BackgroundSetup(resumed: Int) {
    val context = LocalContext.current
    val notifications = remember(resumed) { notificationsAllowed(context) }
    val battery = remember(resumed) { Scheduler.ignoresBatteryOptimizations(context) }
    val exact = remember(resumed) { Scheduler.canExact(context) }
    if (notifications && battery && exact) return
    val app = Uri.parse("package:${context.packageName}")

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleSmall)
            if (!notifications) SetupItem("Notifications are off, so you won't hear about new jobs.") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            if (!battery) SetupItem("Android may pause checks to save battery. Allow the app to run in the background.") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
            }
            if (!exact && Build.VERSION.SDK_INT >= 31) SetupItem("Checks may run later than every 10 minutes.") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app))
            }
        }
    }
}

@Composable
fun SetupItem(text: String, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
fun SearchRow(search: Search, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(search.keywords, style = MaterialTheme.typography.titleMedium)
                Text("${search.place} · ${search.status}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text("${search.found}")
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Stop watching ${search.label}") }
        }
    }
}

@Composable
fun AddSearch(data: AppData, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    var keywords by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(keywords, { keywords = it; error = null }, Modifier.fillMaxWidth(),
                label = { Text("Position") }, placeholder = { Text("e.g. Software engineer") }, singleLine = true)
            OutlinedTextField(location, { location = it; error = null }, Modifier.fillMaxWidth(),
                label = { Text("Location") }, placeholder = { Text("e.g. Amman, Jordan") }, singleLine = true,
                isError = error != null, supportingText = { Text(error ?: "\"City, Country\" works best") })
            Button(onClick = {
                val search = Search(keywords.trim(), location.trim())
                error = when {
                    search.keywords.isEmpty() -> "Enter a position to search for."
                    data.searches.any { it.label == search.label } -> "That search is already in the list."
                    else -> null
                }
                if (error == null) {
                    Store.update(context) { it.copy(searches = it.searches + search) }
                    keywords = ""
                    location = ""
                    onAdded(search.label)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Add search") }
        }
    }
}

@Composable
fun JobRow(job: Job, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.isNew) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(job.company, style = MaterialTheme.typography.bodyMedium)
            Text(job.location, style = MaterialTheme.typography.bodySmall, color = muted)
            Text("${job.search} · found ${timeText(job.foundAt)}", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var form by remember { mutableStateOf(AlertPrefs.load(context)) }
    var result by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lastSend = remember { AlertPrefs.lastSend(context) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(topBar = {
        TopAppBar(title = { Text("Alerts") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New jobs always arrive as a phone notification. You can also get each round by email or WhatsApp.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
            if (lastSend.isNotBlank()) Text("Last send: $lastSend", style = MaterialTheme.typography.bodySmall, color = muted)

            SwitchRow("Email me each round of new jobs", form.emailEnabled) { form = form.copy(emailEnabled = it) }
            if (form.emailEnabled) {
                Field("Send to", form.emailTo, KeyboardType.Email) { form = form.copy(emailTo = it) }
                Field("SMTP server", form.smtpHost, KeyboardType.Uri) { form = form.copy(smtpHost = it) }
                Field("Port", form.smtpPort, KeyboardType.Number) { form = form.copy(smtpPort = it) }
                Field("Sender email", form.smtpUser, KeyboardType.Email) { form = form.copy(smtpUser = it) }
                Field("Password", form.smtpPassword, KeyboardType.Password, secret = true) { form = form.copy(smtpPassword = it) }
                Text("Gmail: turn on 2-Step Verification and use an App Password (myaccount.google.com/apppasswords), not your normal password.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
            HorizontalDivider()
            SwitchRow("WhatsApp me each round (via CallMeBot)", form.whatsappEnabled) { form = form.copy(whatsappEnabled = it) }
            if (form.whatsappEnabled) {
                Field("Your number, e.g. +971501234567", form.whatsappPhone, KeyboardType.Phone) { form = form.copy(whatsappPhone = it) }
                Field("API key", form.whatsappKey, KeyboardType.Number, secret = true) { form = form.copy(whatsappKey = it) }
                Text("To get a free key, send \"I allow callmebot to send me messages\" on WhatsApp to +34 644 43 38 04. " +
                    "If it doesn't reply, check callmebot.com for the current number.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }

            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !sending, onClick = {
                    val config = form.trimmed()
                    val problem = config.problem()
                        ?: if (!config.emailEnabled && !config.whatsappEnabled) "Turn on email or WhatsApp first." else null
                    if (problem != null) {
                        result = problem
                        return@OutlinedButton
                    }
                    sending = true
                    result = "Sending a test…"
                    scope.launch {
                        val results = withContext(Dispatchers.IO) { Alerts.send(config, listOf(Messages.SAMPLE_JOB)) }
                        sending = false
                        result = results.entries.joinToString("\n") { (channel, error) ->
                            "$channel: " + (error?.let { "failed: $it" } ?: "sent")
                        }
                    }
                }) { Text("Send test") }
                Button(onClick = {
                    val config = form.trimmed()
                    val problem = config.problem()
                    if (problem != null) {
                        result = problem
                    } else {
                        AlertPrefs.save(context, config)
                        onBack()
                    }
                }) { Text("Save") }
            }
        }
    }
}

/** A label and a switch; tapping anywhere on the row flips it. */
@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange, role = Role.Switch).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun Field(label: String, value: String, keyboard: KeyboardType, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
