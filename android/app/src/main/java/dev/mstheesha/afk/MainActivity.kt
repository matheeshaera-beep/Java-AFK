package dev.mstheesha.afk

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppGraph.init(applicationContext)
        AppGraph.setContext(applicationContext)
        requestRuntimePermissions()
        super.onCreate(savedInstanceState)
        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by rememberSaveable { mutableStateOf(Prefs.isDark(this, systemDark)) }
            MaterialTheme(colorScheme = if (dark) GreenDark else GreenLight) {
                AfkApp(
                    dark = dark,
                    onToggleDark = { v ->
                        dark = v
                        Prefs.setDark(this, v)
                    },
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }
}

private val GreenLight = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EFB0),
    secondary = Color(0xFF43A047),
    tertiary = Color(0xFF66BB6A),
)
private val GreenDark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF003300),
    primaryContainer = Color(0xFF005300),
    secondary = Color(0xFFA5D6A7),
    tertiary = Color(0xFF66BB6A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AfkApp(dark: Boolean, onToggleDark: (Boolean) -> Unit) {
    // Real selection state so the session screen reacts to the chosen server
    // (rememberSaveable survives rotation/process recreation).
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun openServer(id: Long) {
        selectedId = id
        scope.launch { drawerState.close() }
    }

    // Back returns from session to server list instead of exiting
    BackHandler(enabled = selectedId != null) {
        selectedId = null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                dark = dark,
                selectedId = selectedId,
                onToggleDark = onToggleDark,
                onSelectServer = { id -> openServer(id) },
                drawerOpen = drawerState.isOpen,
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Java AFK") },
                    actions = {
                        if (selectedId == null) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        } else {
                            IconButton(onClick = { selectedId = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                val id = selectedId
                if (id == null) {
                    ServerListScreen(onSelect = { serverId -> openServer(serverId) })
                } else {
                    SessionScreen(
                        serverId = id,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    dark: Boolean,
    selectedId: Long?,
    onToggleDark: (Boolean) -> Unit,
    onSelectServer: (Long) -> Unit,
    drawerOpen: Boolean,
) {
    val dao = AppGraph.db.serverDao()
    val servers by dao.all().collectAsState(initial = emptyList())
    val runningId by AppGraph.runningEngineIdFlow().collectAsState(initial = null)

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Text(
            "Java AFK",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        )

        Text(
            "Servers",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (servers.isEmpty()) {
            Text(
                "No servers.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            servers.forEach { server ->
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    label = { Text(server.name) },
                    selected = server.id == runningId,
                    onClick = { onSelectServer(server.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        ResourcesPanel(active = drawerOpen)

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        NavigationDrawerItem(
            icon = {
                AnimatedContent(targetState = dark, label = "theme_icon") { isDark ->
                    Icon(
                        if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = null,
                    )
                }
            },
            label = { Text("Dark mode") },
            selected = dark,
            onClick = { onToggleDark(!dark) },
            badge = {
                Switch(
                    checked = dark,
                    onCheckedChange = null,
                    modifier = Modifier.graphicsLayer { scaleX = 0.7f; scaleY = 0.7f },
                )
            },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        Spacer(Modifier.weight(1f))

        Text(
            "Java AFK v1.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun ResourcesPanel(active: Boolean) {
    val app = LocalContext.current.applicationContext as Application
    var stats by remember { mutableStateOf<AppStats?>(null) }

    LaunchedEffect(active) {
        while (active) {
            stats = withContext(Dispatchers.IO) { ResourceMonitor.sample(app) }
            delay(2000)
        }
    }

    val cpu by remember { derivedStateOf { stats?.cpuPercent ?: 0f } }
    val ram by remember { derivedStateOf { stats?.rssMB ?: 0L } }
    val netRx by remember { derivedStateOf { stats?.netRxBytes ?: 0L } }
    val netTx by remember { derivedStateOf { stats?.netTxBytes ?: 0L } }

    val cpuAnim by animateFloatAsState(targetValue = cpu / 100f, label = "cpu")
    val ramAnim by animateFloatAsState(targetValue = (ram.toFloat() / 256f).coerceIn(0f, 1f), label = "ram")

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "App usage",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        ListItem(
            headlineContent = {
                AnimatedContent(targetState = "%.0f%%".format(cpu), label = "cpu_val") { v -> Text(v) }
            },
            supportingContent = { LinearProgressIndicator(progress = { cpuAnim }) },
            leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
            modifier = Modifier.padding(vertical = 2.dp),
        )

        ListItem(
            headlineContent = {
                AnimatedContent(targetState = "${ram} MB", label = "ram_val") { v -> Text(v) }
            },
            supportingContent = { LinearProgressIndicator(progress = { ramAnim }) },
            leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
            modifier = Modifier.padding(vertical = 2.dp),
        )

        ListItem(
            headlineContent = { Text("${formatBytes(netRx)} ↓  ${formatBytes(netTx)} ↑") },
            leadingContent = { Icon(Icons.Default.SwapVert, contentDescription = null) },
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var u = 0
    while (value >= 1024 && u < units.size - 1) {
        value /= 1024
        u++
    }
    return String.format(Locale.US, "%.1f %s", value, units[u])
}

@Composable
private fun ServerListScreen(onSelect: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = AppGraph.db.serverDao()
    val servers by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No servers yet. Tap + to add one.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        onSelect = onSelect,
                        onEdit = { editing = server },
                        onDelete = {
                            AppGraph.removeEngine(server.id)
                            scope.launch { dao.delete(server) }
                        },
                    )
                }
            }
        }
    }

    val draft = remember { mutableStateOf(ServerDraft()) }
    if (showAdd) {
        ServerDialog(
            title = "Add server",
            draft = draft.value,
            onChange = { draft.value = it },
            onDismiss = {
                showAdd = false
                draft.value = ServerDraft()
            },
            onSave = {
                scope.launch {
                    dao.insert(
                        ServerEntity(
                            name = draft.value.name.trim().ifEmpty { draft.value.host },
                            host = draft.value.host.trim(),
                            port = draft.value.portInt,
                            chatCommand = draft.value.chatCommand.trim(),
                            commandDelaySeconds = draft.value.delayInt,
                            onlineMode = draft.value.onlineMode,
                            username = draft.value.username.trim(),
                        ),
                    )
                    showAdd = false
                    draft.value = ServerDraft()
                }
            },
        )
    }

    editing?.let { server ->
        val ed = remember(server.id) { mutableStateOf(ServerDraft.from(server)) }
        ServerDialog(
            title = "Edit server",
            draft = ed.value,
            onChange = { ed.value = it },
            onDismiss = { editing = null },
            onSave = {
                scope.launch {
                    dao.update(
                        ServerEntity(
                            id = server.id,
                            name = ed.value.name.trim().ifEmpty { ed.value.host },
                            host = ed.value.host.trim(),
                            port = ed.value.portInt,
                            chatCommand = ed.value.chatCommand.trim(),
                            commandDelaySeconds = ed.value.delayInt,
                            onlineMode = ed.value.onlineMode,
                            username = ed.value.username.trim(),
                        ),
                    )
                    editing = null
                }
            },
        )
    }
}

private val faviconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

private fun cacheFavicon(key: String, bmp: android.graphics.Bitmap) {
    if (faviconCache.size >= 32) faviconCache.clear()
    faviconCache[key] = bmp
}

@Composable
private fun ServerRow(
    server: ServerEntity,
    onSelect: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val engine = remember(server.id) { AppGraph.engineFor(server.id) }
    val state by engine.state.collectAsState()
    val cacheKey = "${server.host}:${server.port}"
    var favicon by remember(cacheKey) {
        mutableStateOf(faviconCache[cacheKey])
    }

    LaunchedEffect(cacheKey) {
        if (faviconCache.containsKey(cacheKey)) {
            favicon = faviconCache[cacheKey]
        } else {
            val bmp = withContext(Dispatchers.IO) {
                ServerPinger.fetchFavicon(server.host, server.port)
            }
            if (bmp != null) cacheFavicon(cacheKey, bmp)
            favicon = bmp
        }
    }

    val isConnected = state == "connected"
    val isWorking = state == "connecting" || state == "authenticating"
    val statusLabel = when {
        isConnected -> "Connected"
        isWorking -> state.replaceFirstChar { it.uppercase() }
        else -> "Offline"
    }
    val statusColor by animateColorAsState(
        if (isConnected) MaterialTheme.colorScheme.primary
        else if (isWorking) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(),
        onClick = { onSelect(server.id) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (favicon != null) {
                Image(
                    bitmap = favicon!!.asImageBitmap(),
                    contentDescription = "Server icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        server.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = statusColor)

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

private data class ServerDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "25565",
    val chatCommand: String = "",
    val commandDelaySeconds: String = "5",
    val onlineMode: Boolean = false,
    val username: String = "",
) {
    val portInt: Int get() = port.toIntOrNull()?.coerceIn(1, 65535) ?: 25565
    val delayInt: Int get() = commandDelaySeconds.toIntOrNull()?.coerceIn(0, 3600) ?: 5
    val portValid: Boolean get() = port.toIntOrNull()?.let { it in 1..65535 } ?: false
    val delayValid: Boolean get() = commandDelaySeconds.toIntOrNull()?.let { it in 0..3600 } ?: false

    companion object {
        fun from(s: ServerEntity) = ServerDraft(
            name = s.name,
            host = s.host,
            port = s.port.toString(),
            chatCommand = s.chatCommand,
            commandDelaySeconds = s.commandDelaySeconds.toString(),
            onlineMode = s.onlineMode,
            username = s.username,
        )
    }
}

@Composable
private fun ServerDialog(
    title: String,
    draft: ServerDraft,
    onChange: (ServerDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(draft.host, { onChange(draft.copy(host = it)) }, label = { Text("Host") }, singleLine = true)
                OutlinedTextField(
                    draft.port,
                    { onChange(draft.copy(port = it)) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !draft.portValid,
                    supportingText = if (!draft.portValid) { { Text("Port must be 1–65535") } } else null,
                )
                OutlinedTextField(draft.chatCommand, { onChange(draft.copy(chatCommand = it)) }, label = { Text("Chat command (optional)") }, singleLine = true)
                OutlinedTextField(
                    draft.commandDelaySeconds,
                    { onChange(draft.copy(commandDelaySeconds = it)) },
                    label = { Text("Command delay (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !draft.delayValid,
                    supportingText = if (!draft.delayValid) { { Text("Delay must be 0–3600") } } else null,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Microsoft account (online mode)")
                    Switch(checked = draft.onlineMode, onCheckedChange = { onChange(draft.copy(onlineMode = it)) })
                }
                if (!draft.onlineMode) {
                    OutlinedTextField(
                        draft.username,
                        { onChange(draft.copy(username = it)) },
                        label = { Text("Offline username") },
                        placeholder = { Text("AFKBot") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.host.isNotBlank() && draft.portValid && draft.delayValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SessionScreen(
    serverId: Long,
    snackbarHostState: SnackbarHostState,
) {
    val ctx = LocalContext.current
    val dao = AppGraph.db.serverDao()
    val engine = remember(serverId) { AppGraph.engineFor(serverId) }

    val server by dao.byId(serverId).collectAsState(initial = null)

    val state by engine.state.collectAsState()
    val detail by engine.detail.collectAsState()
    val logs by engine.logs.collectAsState()
    val chat by engine.chat.collectAsState()
    val authRequired by engine.authRequired.collectAsState()
    var kickedCount by remember { mutableIntStateOf(0) }
    var chatInput by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(engine.kickedCount) {
        if (engine.kickedCount > kickedCount) {
            snackbarHostState.showSnackbar("Kicked or disconnected: ${engine.detail.value}")
        }
        kickedCount = engine.kickedCount
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (server == null) {
                Text("Select a server from the list first.")
                return@Column
            }

            val srv = server!!

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${srv.name} — ${srv.host}:${srv.port}", style = MaterialTheme.typography.titleMedium)
                    Text("State: ${stateLabel(state)}", style = MaterialTheme.typography.bodyLarge)
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        engine.start(buildConfig(ctx, srv))
                        ctx.startForegroundService(Intent(ctx, AfkService::class.java).setAction(AfkService.ACTION_START))
                    },
                    enabled = state == "disconnected" || state == "error",
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" Start")
                }
                Button(
                    onClick = { engine.stop() },
                    enabled = state != "disconnected",
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(" Stop")
                }
                OutlinedButton(
                    onClick = { engine.reconnect() },
                    enabled = state == "connected" || state == "error",
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" Reconnect")
                }
                OutlinedButton(onClick = {
                    engine.clearToken()
                    scope.launch { snackbarHostState.showSnackbar("Saved login token cleared") }
                }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text(" Clear token")
                }
            }

            Text("Chat command:", style = MaterialTheme.typography.titleSmall)
            var chatCommandText by remember(serverId) { mutableStateOf(srv.chatCommand) }
            var delayText by remember(serverId) { mutableStateOf(srv.commandDelaySeconds.toString()) }
            LaunchedEffect(chatCommandText) {
                if (chatCommandText != srv.chatCommand) {
                    delay(600)
                    dao.update(srv.copy(chatCommand = chatCommandText))
                }
            }
            LaunchedEffect(delayText) {
                val parsed = delayText.toIntOrNull()
                if (parsed != null && parsed != srv.commandDelaySeconds) {
                    delay(600)
                    dao.update(srv.copy(commandDelaySeconds = parsed))
                }
            }
            OutlinedTextField(
                value = chatCommandText,
                onValueChange = { chatCommandText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Command sent after spawning") },
                singleLine = true,
            )
            OutlinedTextField(
                value = delayText,
                onValueChange = { delayText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Delay before command (seconds)") },
                singleLine = true,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { showChat = false }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = "Log",
                        tint = if (!showChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showChat = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Chat",
                        tint = if (showChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (showChat) {
                        chat.takeLast(40).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Type a message or /command") },
                                singleLine = true,
                            )
                            IconButton(
                                onClick = {
                                    val msg = chatInput
                                    if (msg.isNotBlank()) {
                                        engine.sendChat(msg)
                                        chatInput = ""
                                    }
                                },
                                enabled = state == "connected",
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    } else {
                        logs.takeLast(40).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }

    authRequired?.let { (url, code) ->
        AlertDialog(
            onDismissRequest = { engine.authRequired.value = null },
            title = { Text("Finish sign-in") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Please sign in to your Microsoft account to verify this device, then return to the app.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Code: $code", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = {
                            val clipboard = ctx.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("device code", code))
                        }) { Text("Copy code") }
                    }
                    Text(url, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) { Text("Open browser") }
            },
            dismissButton = {
                TextButton(onClick = { engine.authRequired.value = null }) { Text("Done") }
            },
        )
    }
}

private fun stateLabel(state: String): String = when (state) {
    "connected" -> "Connected"
    "connecting" -> "Connecting"
    "authenticating" -> "Auth required"
    "error" -> "Error"
    else -> "Disconnected"
}

private fun buildConfig(context: Context, server: ServerEntity): String =
    JSONObject()
        .put("host", server.host)
        .put("port", server.port)
        .put("chatCommand", server.chatCommand.trim())
        .put("commandDelaySeconds", if (server.commandDelaySeconds > 0) server.commandDelaySeconds else 5)
        .put("tokenCachePath", File(context.filesDir, "ms_token.json").absolutePath)
        .put("auth", if (server.onlineMode) "microsoft" else "offline")
        .put("username", server.username.ifBlank { "AFKBot" })
        .put("profilesFolder", File(context.filesDir, "minecraft-auth").absolutePath)
        .toString()