@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
        RemoteBoards.cancelOldSchedule(this)
        setContent { JobWatchTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        resumes.value = resumes.value + 1 // re-read permissions the user may have just changed
        // Opening the app is no reason to check LinkedIn: the alarm does that. Only when
        // the chain broke (the phone forgot the alarm, or its time came and it never fired) start
        // it again and catch up now. Nothing happens while background checks are turned off.
        if (Store.load(this).checksOn && !Scheduler.isScheduled(this)) {
            Scheduler.scheduleNext(this)
            thread { Checker.tick(applicationContext, Checker.TRIGGER_OPENED) }
        }
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

    /**
     * A check you asked for: LinkedIn's next search (or [prefer], a search just added) if two minutes
     * have passed since its last one, and the job boards that are due. The snackbar says what it found.
     */
    fun runCheck(prefer: String?, trigger: String = Checker.TRIGGER_REFRESH) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Checker.tick(context.applicationContext, trigger, force = true, prefer = prefer)
            }
            busy = false
            snackbar.showSnackbar(result.message)
        }
    }

    /** Delete jobs, with a few seconds to undo. */
    fun deleteJobs(ids: Set<String>) {
        val removed = Store.deleteJobs(context, ids)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss() // a newer delete replaces the last one's undo
            val result = snackbar.showSnackbar("Deleted ${removed.size} job${if (removed.size == 1) "" else "s"}",
                actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) Store.restoreJobs(context, removed)
        }
    }

    val setChecks: (Boolean) -> Unit = { on ->
        Store.update(context) { it.copy(checksOn = on) } // the remote boards follow the same switch
        if (on) {
            Scheduler.scheduleNext(context)
            runCheck(null, Checker.TRIGGER_SWITCH)
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
        MainList(data, resumed, padding, onSearchAdded = { label -> runCheck(label, Checker.TRIGGER_ADDED) },
            onChecksChanged = setChecks, onDelete = ::deleteJobs)
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
                Text("Checks each of your LinkedIn searches every ${Scheduler.INTERVAL_MINUTES} minutes, one search every " +
                    "${Scheduler.SPACING_MINUTES} minutes so LinkedIn isn't asked too often, and remote job boards as often " +
                    "as each one allows, and tells you about new and reposted jobs. It runs entirely on this phone.",
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
    onDelete: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var toRemove by remember { mutableStateOf<Search?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) } // ids of jobs picked with a long press
    val selecting = selected.isNotEmpty()
    val jobs = data.jobs.filter { filter == null || it.search == filter }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BackHandler(enabled = selecting) { selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + if (selecting) 140.dp else 24.dp, // room for the selection bar
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
            item(key = "remote") {
                RemoteBoardsCard(data, onChange = { on ->
                    Store.update(context) { it.copy(remoteOn = on) }
                    if (on) thread { // don't wait for the next check
                        val app = context.applicationContext
                        Checker.announce(app, RemoteBoards.check(app).jobs, immediate = true)
                    }
                })
            }
            item(key = "sites") {
                JobSitesCard(data,
                    onChange = { on ->
                        Store.update(context) { it.copy(sitesOn = on) }
                        if (on) readSitesNow(context)
                    },
                    onKeySaved = { readSitesNow(context) },
                )
            }

            val newCount = data.jobs.count { it.isNew }
            item { SectionTitle("Jobs (${data.jobs.size}${if (newCount > 0) ", $newCount new" else ""})") }
            val filterLabels = data.searches.map { it.label } +
                if (data.jobs.any { it.search == RemoteBoards.LABEL }) listOf(RemoteBoards.LABEL) else emptyList()
            if (filterLabels.isNotEmpty()) {
                item(key = "filters") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                        filterLabels.forEach { label ->
                            FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
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
                JobRow(
                    job = job,
                    selecting = selecting,
                    selected = job.id in selected,
                    onOpen = {
                        // The "new" dot stays until you open that job (or mark it read).
                        if (job.isNew) Store.setNew(context, setOf(job.id), false)
                        openUrl(context, job.url)
                    },
                    onToggleSelect = { selected = if (job.id in selected) selected - job.id else selected + job.id },
                    onToggleNew = { Store.setNew(context, setOf(job.id), !job.isNew) },
                )
            }
        }

        if (selecting) {
            SelectionBar(
                count = selected.size,
                bottomInset = padding.calculateBottomPadding(),
                onSelectAll = { selected = jobs.map { it.id }.toSet() }, // everything the current filter shows
                onMark = { isNew ->
                    Store.setNew(context, selected, isNew)
                    selected = emptySet()
                },
                onDelete = {
                    onDelete(selected)
                    selected = emptySet()
                },
                onCancel = { selected = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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

/** The on/off switch for background checks, what they're doing right now, and the history of checks. */
@Composable
fun ChecksCard(data: AppData, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val now = Clock.now()
    val next = remember(data) { Scheduler.nextCheckAt(context) }
    val retry = Checker.linkedInReadyAt(data, now).let { if (it > now) it else next }
    val limited = data.checksOn && data.limited
    val detail = when {
        !data.checksOn -> "Off: nothing runs in the background. Tap the refresh button at the top to check once."
        limited -> "LinkedIn asked to slow down. Trying it again at about ${timeText(retry)}; jobs posted meanwhile won't be missed."
        else -> "Last check ${timeText(data.lastRun)}" + if (next > now) " · next about ${timeText(next)}" else ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
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
            TextButton(onClick = { showHistory = true }, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                Text("Recent checks")
            }
        }
    }
    if (showHistory) HistoryDialog(data.rounds, onClose = { showHistory = false })
}

/** The last checks, newest first: when each started, what started it, how long it took and what it found. */
@Composable
fun HistoryDialog(rounds: List<Round>, onClose: () -> Unit) {
    val now = Clock.now()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Recent checks") },
        text = {
            if (rounds.isEmpty()) {
                Text("No checks yet.", style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rounds, key = { it.startedAt }) { round ->
                        val (line, warn) = when {
                            round.finishedAt > 0 -> "${round.result} · took ${durationText(round.finishedAt - round.startedAt)}" to false
                            now - round.startedAt < 3 * 60_000 -> "Running…" to false
                            else -> "Did not finish: the phone stopped the app during this check" to true
                        }
                        Column {
                            Text("${timeText(round.startedAt)} · ${round.trigger}", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                color = if (warn) MaterialTheme.colorScheme.error else muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
    )
}

fun durationText(ms: Long): String = when {
    ms < 1_000 -> "under a second"
    ms < 60_000 -> "${ms / 1_000} s"
    else -> "${ms / 60_000} min ${ms % 60_000 / 1_000} s"
}

/** On/off switch for the remote job boards, with what they look for and when they were last checked. */
@Composable
fun RemoteBoardsCard(data: AppData, onChange: (Boolean) -> Unit) {
    val positions = data.searches.map { it.keywords.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val status = when {
        !data.remoteOn -> "Off"
        !data.checksOn -> "Paused while background checks are off"
        positions.isEmpty() -> "Add a search above to choose which jobs to look for"
        data.remoteLastCheck == 0L -> "First check is on its way"
        else -> data.remoteStatus
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = data.remoteOn, onValueChange = onChange, role = Role.Switch)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(RemoteBoards.LABEL, style = MaterialTheme.typography.titleMedium)
                Text("Remote ${positions.joinToString(" and ").ifBlank { "jobs" }} jobs open worldwide or to your region, " +
                    "from ${RemoteBoards.boardNames}. Checked every ${Scheduler.INTERVAL_MINUTES} minutes on Wi-Fi and " +
                    "hourly on mobile data; Jobicy allows one check an hour and Remotive four a day.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = data.remoteOn, onCheckedChange = null)
        }
    }
}

/** Read the job sites that are due right away (just turned on, or a new Jooble key), and announce what's new. */
private fun readSitesNow(context: Context) = thread {
    val app = context.applicationContext
    Checker.announce(app, JobSites.check(app, force = true).jobs, immediate = true)
}

/** On/off for the job sites, what they look for, how their last reads went, and Jooble's key. */
@Composable
fun JobSitesCard(data: AppData, onChange: (Boolean) -> Unit, onKeySaved: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var editing by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf("") }
    fun status(site: String) = when {
        !data.sitesOn -> "Off"
        !data.checksOn -> "Paused while background checks are off"
        site == JobSites.RELIEFWEB && JobSites.reliefWebUrl(data.searches) == null -> "Add a search with a place to use it"
        site == JobSites.JOOBLE && data.joobleKey.isBlank() -> "Add your free API key below to turn it on"
        else -> data.siteStatus[site] ?: "First check is on its way"
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = data.sitesOn, onValueChange = onChange, role = Role.Switch)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(JobSites.LABEL, style = MaterialTheme.typography.titleMedium)
                    Text("ReliefWeb (run by the UN's OCHA: UN and NGO jobs) and Jooble (a job search engine) look for your " +
                        "positions in your searches' places, each at most hourly. Their jobs show under the search they match.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    Text("${JobSites.RELIEFWEB}: ${status(JobSites.RELIEFWEB)}", style = MaterialTheme.typography.bodySmall)
                    Text("${JobSites.JOOBLE}: ${status(JobSites.JOOBLE)}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = data.sitesOn, onCheckedChange = null)
            }
            if (data.sitesOn && (data.joobleKey.isBlank() || editing)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Jooble's API key is free. Its form asks for a website; the key is only used for your searches.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Jooble API key") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { openUrl(context, JobSites.JOOBLE_KEY_PAGE) }) { Text("Get a key") }
                        Spacer(Modifier.weight(1f))
                        if (editing) TextButton(onClick = { editing = false; key = "" }) { Text("Cancel") }
                        Button(onClick = {
                            val saved = key.trim()
                            // A new key starts afresh: Jooble is asked for each search straight away.
                            Store.update(context) { d ->
                                d.copy(joobleKey = saved, siteChecks = d.siteChecks.filterKeys { !it.startsWith("${JobSites.JOOBLE}|") },
                                    siteStatus = d.siteStatus - JobSites.JOOBLE)
                            }
                            editing = false
                            key = ""
                            onKeySaved()
                        }, enabled = key.isNotBlank()) { Text("Save") }
                    }
                }
            } else if (data.sitesOn) {
                TextButton(onClick = { editing = true }, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                    Text("Change Jooble key")
                }
            }
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

/** The form for a new search, or, with [Store.MAX_SEARCHES] searches already, a note on why there's no room. */
@Composable
fun AddSearch(data: AppData, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    var keywords by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.searches.size >= Store.MAX_SEARCHES) {
                Text("${Store.MAX_SEARCHES} searches is the most: LinkedIn gets one search every ${Scheduler.SPACING_MINUTES} " +
                    "minutes, so each is checked every ${Scheduler.INTERVAL_MINUTES}. Remove one to add another.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
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
                    if (error == null && Store.addSearch(context, search)) {
                        keywords = ""
                        location = ""
                        onAdded(search.label)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Add search") }
            }
        }
    }
}

/**
 * One job. Tap to open it (and mark it read); tap the dot to switch between new (filled) and
 * read (empty); long-press to start selecting several jobs. While selecting, a tap selects.
 */
@Composable
fun JobRow(
    job: Job,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleNew: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Secondary lines follow the card's text colour, so they stay readable on a selected card.
    val muted = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant
    val colors = if (selected) CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer)
    else CardDefaults.cardColors()
    Card(
        modifier = Modifier.fillMaxWidth()
            .clip(CardDefaults.shape)
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
                onLongClickLabel = "Select",
            ),
        colors = colors,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                IconButton(
                    onClick = onToggleNew,
                    modifier = Modifier.semantics { contentDescription = if (job.isNew) "Mark as read" else "Mark as new" },
                ) {
                    val dot = Modifier.size(10.dp)
                    Box(if (job.isNew) dot.background(MaterialTheme.colorScheme.primary, CircleShape)
                        else dot.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (job.isNew) FontWeight.SemiBold else FontWeight.Normal)
                Text(job.company, style = MaterialTheme.typography.bodyMedium)
                Text(job.location, style = MaterialTheme.typography.bodySmall, color = muted)
                val via = if (job.source == "LinkedIn") "" else " · via ${job.source}" // boards ask to be credited
                Text("${job.search}$via · found ${timeText(job.foundAt)}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/** The bar shown while jobs are selected: mark them read or new, delete them, select all, or cancel. */
@Composable
fun SelectionBar(
    count: Int,
    bottomInset: Dp,
    onSelectAll: () -> Unit,
    onMark: (isNew: Boolean) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 12.dp + bottomInset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
                Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onSelectAll) { Text("Select all") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected jobs") }
            }
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMark(false) }, modifier = Modifier.weight(1f)) { Text("Mark as read") }
                Button(onClick = { onMark(true) }, modifier = Modifier.weight(1f)) { Text("Mark as new") }
            }
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
