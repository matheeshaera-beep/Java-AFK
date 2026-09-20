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
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    override fun onStart() {
        super.onStart()
        AppGraph.uiVisible = true
    }

    override fun onStop() {
        AppGraph.uiVisible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppGraph.init(applicationContext)
        AppGraph.setContext(applicationContext)
        requestRuntimePermissions()
        // Target SDK 35+ enforces edge-to-edge: adjustResize no longer moves
        // content, so the keyboard would cover the chat input. Opt in here
        // and handle the IME inset in Compose (single imePadding, SessionScreen).
        enableEdgeToEdge()
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
                            IconButton(
                                onClick = { selectedId = null },
                                // Center-align with the server settings gear
                                // below: TopAppBar actions end 4 dp from the
                                // edge, the gear ends 16+16 dp in.
                                modifier = Modifier.padding(end = 28.dp),
                            ) {
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
    val activeCount by AppGraph.activeCount.collectAsState(initial = 0)

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Text(
            "Java AFK",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
        )
        Spacer(Modifier.height(16.dp))

        // Running count in front of the "Servers" header (fraction only).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "$activeCount/${NodeRuntime.MAX_BOTS}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Servers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (servers.isEmpty()) {
            Text(
                "No servers.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            servers.forEach { server ->
                NavigationDrawerItem(
                    icon = {
                        ServerAvatar(
                            host = server.host,
                            port = server.port,
                            name = server.name,
                            size = 32.dp,
                        )
                    },
                    label = { Text(server.name) },
                    selected = false,
                    onClick = { onSelectServer(server.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        // Push everything below (usage, theme, version) to the bottom so the
        // server list owns the top of the drawer.
        Spacer(Modifier.weight(1f))

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

        Text(
            "Java AFK v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
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
            delay(5000)
        }
    }

    // Raw per-window CPU is bursty (GC, render, Node); smooth it so the
    // number doesn't jump between extremes every refresh.
    var smoothCpu by remember { mutableStateOf(0f) }
    LaunchedEffect(stats) {
        stats?.let {
            smoothCpu =
                if (smoothCpu == 0f) it.cpuPercent
                else smoothCpu * 0.65f + it.cpuPercent * 0.35f
        }
    }
    val ram = stats?.rssMB ?: 0L
    val ramFrac = stats?.let { it.rssMB.toFloat() / it.totalMemMB } ?: 0f

    val cpuAnim by animateFloatAsState(
        targetValue = (smoothCpu / 100f).coerceIn(0f, 1f),
        label = "cpu",
    )
    val ramAnim by animateFloatAsState(targetValue = ramFrac.coerceIn(0f, 1f), label = "ram")

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "App usage",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "CPU: %.0f%%".format(smoothCpu),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LinearProgressIndicator(
                progress = { cpuAnim },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "RAM: $ram MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LinearProgressIndicator(
                progress = { ramAnim },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
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
                            AppGraph.removeSession(server.id)
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
                            viewDistance = draft.value.viewDistanceInt,
                            chatMode = draft.value.chatMode,
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
                            viewDistance = ed.value.viewDistanceInt,
                            chatMode = ed.value.chatMode,
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
    val session = remember(server.id) { AppGraph.sessionFor(server.id) }
    // Feed the foreground notification's server-name list.
    LaunchedEffect(server.id, server.name) {
        AppGraph.noteServerName(server.id, server.name)
    }
    val state by session.state.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    val isConnected = state == "connected"
    val isWorking = state == "connecting" || state == "authenticating" || state == "reconnecting"
    val statusLabel = when {
        isConnected -> "Connected"
        isWorking -> state.replaceFirstChar { it.uppercase() }
        else -> "Offline"
    }
    val statusColor =
        if (isConnected) MaterialTheme.colorScheme.primary
        else if (isWorking) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        onClick = { onSelect(server.id) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerAvatar(
                host = server.host,
                port = server.port,
                name = server.name,
                size = 48.dp,
            )
            Spacer(Modifier.width(10.dp))

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                // Host with the status pill right next to it.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        server.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = statusColor)
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Server options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Server icon shared by the main list and the drawer: live favicon when the
 *  server has one, initial-letter tile otherwise (memory-cached). */
@Composable
private fun ServerAvatar(host: String, port: Int, name: String, size: Dp) {
    val cacheKey = "$host:$port"
    var favicon by remember(cacheKey) {
        mutableStateOf(faviconCache[cacheKey])
    }

    LaunchedEffect(cacheKey) {
        if (faviconCache.containsKey(cacheKey)) {
            favicon = faviconCache[cacheKey]
        } else {
            val bmp = withContext(Dispatchers.IO) {
                ServerPinger.fetchFavicon(host, port)
            }
            if (bmp != null) cacheFavicon(cacheKey, bmp)
            favicon = bmp
        }
    }

    if (favicon != null) {
        Image(
            bitmap = favicon!!.asImageBitmap(),
            contentDescription = "Server icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                style = if (size >= 40.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            )
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
    val viewDistance: String = "2",
    val chatMode: String = "enabled", // enabled | commandsOnly | hidden
) {
    val portInt: Int get() = port.toIntOrNull()?.coerceIn(1, 65535) ?: 25565
    val delayInt: Int get() = commandDelaySeconds.toIntOrNull()?.coerceIn(0, 3600) ?: 5
    val viewDistanceInt: Int get() = viewDistance.toIntOrNull()?.coerceIn(2, 12) ?: 2
    val portValid: Boolean get() = port.toIntOrNull()?.let { it in 1..65535 } ?: false

    companion object {
        fun from(s: ServerEntity) = ServerDraft(
            name = s.name,
            host = s.host,
            port = s.port.toString(),
            chatCommand = s.chatCommand,
            commandDelaySeconds = s.commandDelaySeconds.toString(),
            onlineMode = s.onlineMode,
            username = s.username,
            viewDistance = s.viewDistance.toString(),
            chatMode = s.chatMode,
        )
    }
}

@Composable
private fun ChatModeChip(label: String, value: String, current: String, onClick: () -> Unit) {
    val selected = current == value
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = onSave, enabled = draft.host.isNotBlank() && draft.portValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Per-server settings, moved off the main session screen. Edits auto-save
// to the database with the same debounce as the old inline fields.
@Composable
private fun ServerSettingsDialog(
    server: ServerEntity,
    session: ServerSession,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val dao = AppGraph.db.serverDao()
    val scope = rememberCoroutineScope()
    var chatCommandText by remember(server.id) { mutableStateOf(server.chatCommand) }
    var delayText by remember(server.id) { mutableStateOf(server.commandDelaySeconds.toString()) }
    var viewDistanceText by remember(server.id) { mutableStateOf(server.viewDistance.toString()) }
    var chatModeValue by remember(server.id) { mutableStateOf(server.chatMode) }
    LaunchedEffect(chatCommandText) {
        if (chatCommandText != server.chatCommand) {
            delay(600)
            dao.update(server.copy(chatCommand = chatCommandText))
            session.pushLiveConfig(chatCommandText, delayText.toIntOrNull() ?: server.commandDelaySeconds, viewDistanceText.toIntOrNull() ?: server.viewDistance, chatModeValue)
        }
    }
    LaunchedEffect(delayText) {
        val parsed = delayText.toIntOrNull()
        if (parsed != null && parsed != server.commandDelaySeconds) {
            delay(600)
            dao.update(server.copy(commandDelaySeconds = parsed))
            session.pushLiveConfig(chatCommandText, parsed, viewDistanceText.toIntOrNull() ?: server.viewDistance, chatModeValue)
        }
    }
    LaunchedEffect(viewDistanceText) {
        val parsed = viewDistanceText.toIntOrNull()?.coerceIn(2, 12)
        if (parsed != null && parsed != server.viewDistance) {
            delay(600)
            dao.update(server.copy(viewDistance = parsed))
            session.pushLiveConfig(chatCommandText, delayText.toIntOrNull() ?: server.commandDelaySeconds, parsed, chatModeValue)
        }
    }
    LaunchedEffect(chatModeValue) {
        if (chatModeValue != server.chatMode) {
            delay(600)
            dao.update(server.copy(chatMode = chatModeValue))
            session.pushLiveConfig(chatCommandText, delayText.toIntOrNull() ?: server.commandDelaySeconds, viewDistanceText.toIntOrNull() ?: server.viewDistance, chatModeValue)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${server.name} settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("View distance (chunks)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Higher = more chunks loaded from server (2–12). Farm production depends on the server's simulation-distance, not this. Lower = less battery and data. 2 is enough for AFK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = viewDistanceText,
                        onValueChange = { viewDistanceText = it },
                        label = { Text("Chunks") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Public chat", style = MaterialTheme.typography.bodyLarge)
                    Row {
                        ChatModeChip("Enabled", "enabled", chatModeValue) { chatModeValue = "enabled" }
                        ChatModeChip("Commands only", "commandsOnly", chatModeValue) { chatModeValue = "commandsOnly" }
                        ChatModeChip("Hidden", "hidden", chatModeValue) { chatModeValue = "hidden" }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedButton(onClick = {
                    session.clearToken()
                    scope.launch { snackbarHostState.showSnackbar("Saved login token cleared") }
                }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text(" Clear token")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun SessionScreen(
    serverId: Long,
    snackbarHostState: SnackbarHostState,
) {
    val ctx = LocalContext.current
    val dao = AppGraph.db.serverDao()
    val session = remember(serverId) { AppGraph.sessionFor(serverId) }

    val server by dao.byId(serverId).collectAsState(initial = null)

    val state by session.state.collectAsState()
    val detail by session.detail.collectAsState()
    val logs by session.logs.collectAsState()
    val chat by session.chat.collectAsState()
    val authRequired by session.authRequired.collectAsState()
    val openWindow by session.window.collectAsState()
    val activeCount by AppGraph.activeCount.collectAsState(initial = 0)
    var kickedCount by remember { mutableIntStateOf(0) }
    var chatInput by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var showSettings by remember(serverId) { mutableStateOf(false) }
    // Session-owned counters: keep ticking while connected even when this
    // screen is closed and reopened.
    val afkSeconds by session.afkSeconds.collectAsState()
    val sessionData by session.sessionDataBytes.collectAsState()
    val scope = rememberCoroutineScope()
    val canStart = state == "disconnected" || state == "error"

    // Follow-newest log/chat: sticks to the tail on new lines, releases when
    // the user scrolls up, re-engages at the bottom or via the jump button.
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(showChat) { followNewest = true }
    val itemCount = if (showChat) chat.size else logs.size
    LaunchedEffect(itemCount, followNewest) {
        if (followNewest && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && last.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !atBottom) followNewest = false
    }
    LaunchedEffect(atBottom) {
        if (atBottom) followNewest = true
    }
    // Keyboard opened: jump to the newest message so it stays visible above
    // the keyboard. Visibility only — no keyboard height is tracked anywhere.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && showChat && itemCount > 0) {
            followNewest = true
            listState.scrollToItem(itemCount - 1)
        }
    }

    // Chat is pulled in the session poll loop even with the tab closed; this
    // just tops it up the moment the tab opens.
    LaunchedEffect(showChat) {
        session.setChatPolling(showChat)
        if (showChat) {
            // Ensure server chat already ingested is shown, even if status poll is idle.
            session.refreshChatNow()
        }
    }

    // 1 s counter ticker, alive only while this screen is composed (the
    // session itself runs no background ticker anymore).
    LaunchedEffect(serverId) {
        while (true) {
            delay(1000)
            session.refreshCounters()
        }
    }

    LaunchedEffect(session.kickedCount) {
        if (session.kickedCount > kickedCount) {
            snackbarHostState.showSnackbar("Kicked or disconnected: ${session.detail.value}")
        }
        kickedCount = session.kickedCount
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (server == null) {
            Text("Select a server from the list first.")
        } else {
            val srv = server!!

            LaunchedEffect(srv.id, srv.name) {
                AppGraph.noteServerName(srv.id, srv.name)
            }

            val statusDotColor =
                if (state == "connected") MaterialTheme.colorScheme.primary
                else if (state == "connecting" || state == "authenticating" || state == "reconnecting") MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant
            // Header + buttons wrap their content and scroll if the window
            // gets short. The log/chat panel below owns weight(1f) plus the
            // single imePadding, so the input can never be pushed off-screen.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Header group: title, status and detail stacked tightly
                    // and centered. The gear overlays the top-right corner so
                    // it never pushes the title off-center.
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                srv.name,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor),
                                )
                                Text(
                                    "Status: ${stateLabel(state)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Server settings")
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text(
                                    "AFK TIME",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    formatAfkTime(afkSeconds),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        VerticalDivider(modifier = Modifier.height(36.dp))
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            Icon(
                                Icons.Default.DataUsage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text(
                                    "DATA USED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    formatBytes(sessionData),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (activeCount >= NodeRuntime.MAX_BOTS) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Maximum ${NodeRuntime.MAX_BOTS} servers can run at the same time.")
                            }
                        } else {
                            session.start(buildConfig(ctx, srv))
                            ctx.startForegroundService(Intent(ctx, AfkService::class.java).setAction(AfkService.ACTION_START))
                        }
                    },
                    enabled = canStart,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" Start")
                }
                Button(
                    onClick = { session.reconnect() },
                    enabled = state != "disconnected",
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reconnect now")
                }
                Button(
                    onClick = { session.stop() },
                    enabled = state != "disconnected",
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(" Stop")
                }
            }
            }
        }

        server?.let { srvd ->
            if (showSettings) {
                ServerSettingsDialog(
                    server = srvd,
                    session = session,
                    snackbarHostState = snackbarHostState,
                    onDismiss = { showSettings = false },
                )
            }
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

            // Chat tab = Column(fillMaxSize) { messages list (weight 1f);
            // input row }. The ONE imePadding in this screen sits on the
            // panel: the input always rests directly above the keyboard
            // (gesture and 3-button nav alike), the list takes the rest, and
            // with the keyboard closed the padding is 0 so the layout is
            // exactly as before. No keyboard height is tracked manually.
            Card(
                Modifier.fillMaxWidth().weight(1f).imePadding(),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                        ) {
                            if (showChat) {
                                items(chat, key = { "${it.seq}:${it.ts}:${it.text.hashCode()}" }) { line ->
                                    val sender = line.sender?.let { "<$it> " } ?: ""
                                    val color = when (line.type) {
                                        "chat" -> MaterialTheme.colorScheme.onSurface
                                        "system" -> MaterialTheme.colorScheme.tertiary
                                        "whisper" -> MaterialTheme.colorScheme.secondary
                                        "error" -> MaterialTheme.colorScheme.error
                                        "out" -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        "$sender${line.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = color,
                                    )
                                }
                            } else {
                                items(logs, key = { it.id }) { line ->
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        if (!followNewest && itemCount > 0) {
                            TextButton(
                                onClick = {
                                    followNewest = true
                                    scope.launch { listState.scrollToItem(itemCount - 1) }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) { Text("Jump to latest") }
                        }
                    }
                    if (showChat) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Type a message or /command") },
                                minLines = 1,
                                maxLines = 5,
                            )
                            IconButton(
                                onClick = {
                                    val msg = chatInput
                                    if (msg.isNotBlank()) {
                                        session.sendChat(msg)
                                        chatInput = ""
                                    }
                                },
                                enabled = state == "connected",
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }

    // Inventory GUI: sheet lives exactly while a window is open — rows stay
    // tappable across multiple clicks, and it dismisses itself when the
    // window state goes null. No icons/textures, text only. Swiping down
    // closes the server-side window AND drops the sheet at once: leaving a
    // hidden-but-composed sheet would keep its scrim eating all touches.
    var windowDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(openWindow) {
        if (openWindow == null) windowDismissed = false
    }
    if (openWindow != null && !windowDismissed) {
        val w = openWindow!!
        // Filler-dedup: slots repeating the same name+count more than twice
        // collapse into one row ("+N more"); distinct items come first in
        // slot order. Tapping a collapsed row clicks its first slot.
        // Keyed on title so the toggle survives the 1 Hz re-polls.
        var showAllSlots by remember(w.title) { mutableStateOf(false) }
        val rows = remember(w.title, w.slots, showAllSlots) {
            if (showAllSlots) {
                w.slots.map { SlotRow(it.slot, it.name, it.count, 0) }
            } else {
                val groups = w.slots.groupBy { it.name to it.count }
                val out = mutableListOf<SlotRow>()
                w.slots
                    .filter { s -> groups[s.name to s.count]!!.size <= 2 }
                    .mapTo(out) { SlotRow(it.slot, it.name, it.count, 0) }
                groups.values
                    .filter { it.size > 2 }
                    .sortedBy { g -> g.minOf { it.slot } }
                    .mapTo(out) { g ->
                        val first = g.minBy { it.slot }
                        SlotRow(first.slot, first.name, first.count, g.size - 1)
                    }
                out
            }
        }
        val collapsedCount = rows.sumOf { it.more }
        ModalBottomSheet(
            onDismissRequest = {
                windowDismissed = true
                session.closeWindow()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    w.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (collapsedCount > 0 && !showAllSlots) {
                    TextButton(onClick = { showAllSlots = true }) { Text("Show all") }
                } else if (showAllSlots) {
                    TextButton(onClick = { showAllSlots = false }) { Text("Show less") }
                }
            }
            LazyColumn {
                items(rows, key = { it.slot }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { session.clickSlot(s.slot) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            buildString {
                                append("×${s.count}")
                                if (s.more > 0) append("  +${s.more} more")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    authRequired?.let { (url, code) ->
        AlertDialog(
            onDismissRequest = { session.authRequired.value = null },
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
                TextButton(onClick = { session.authRequired.value = null }) { Text("Done") }
            },
        )
    }
}

/** One bottom-sheet row: a window slot plus collapsed duplicates (more). */
private data class SlotRow(val slot: Int, val name: String, val count: Int, val more: Int)

private fun formatAfkTime(totalSeconds: Long): String {    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun stateLabel(state: String): String = when (state) {
    "connected" -> "Connected"
    "connecting" -> "Connecting"
    "reconnecting" -> "Reconnecting"
    "authenticating" -> "Authenticating"
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
        .put("viewDistance", server.viewDistance.coerceIn(2, 12))
        .put("chatMode", if (server.chatMode in CALLABLE_CHAT_MODES) server.chatMode else "enabled")
        .put("profilesFolder", File(context.filesDir, "minecraft-auth").absolutePath)
        .toString()

private val CALLABLE_CHAT_MODES = setOf("enabled", "commandsOnly", "hidden")