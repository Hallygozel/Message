@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.mysms

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent?.data?.schemeSpecificPart
            ?.substringBefore('?')?.substringBefore(',')?.trim()
        setContent { AppTheme { App(initial) } }
    }
}

// ------------------------------------------------------------------ Tema (gije/gündiz awtomatik)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

// ------------------------------------------------------------------ Ekranlar
sealed interface Screen {
    object Home : Screen
    object Search : Screen
    object Blocked : Screen
    data class Thread(val threadId: Long, val address: String, val name: String) : Screen
    data class New(val address: String) : Screen
}

private val ScreenSaver = listSaver<Screen, Any>(
    save = { s ->
        when (s) {
            is Screen.Home -> listOf("home")
            is Screen.Search -> listOf("search")
            is Screen.Blocked -> listOf("blocked")
            is Screen.Thread -> listOf("thread", s.threadId, s.address, s.name)
            is Screen.New -> listOf("new", s.address)
        }
    },
    restore = { l ->
        when (l[0] as String) {
            "search" -> Screen.Search
            "blocked" -> Screen.Blocked
            "thread" -> Screen.Thread(l[1] as Long, l[2] as String, l[3] as String)
            "new" -> Screen.New(l[1] as String)
            else -> Screen.Home
        }
    }
)

private val PERMISSIONS = arrayOf(
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.POST_NOTIFICATIONS
)

private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "🙏")
private val STICKERS = listOf("👍", "❤️", "😂", "🎉", "🔥", "😍", "🙏", "👏", "😭", "🥳", "😎", "💯")
private val EMOJIS = (
    "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 😉 😍 🥰 😘 😎 🤩 🥳 😇 🤔 😐 😴 😢 😭 😡 😱 🙄 😬 🤗 🤝 " +
        "👍 👎 👏 🙌 🙏 💪 👋 ✌️ 👌 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 🔥 ✨ 🎉 🎁 🎂 ☀️ 🌙 ⭐ 🌹 🍀 ☕ 🍕 🍎 🚗 ✈️ 🏠 📞 💯 ✅ ❌"
    ).split(" ")

private fun fmt(ms: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))

private fun statusText(m: Msg): String? {
    if (!m.isMine) return null
    return when {
        m.type == Telephony.Sms.MESSAGE_TYPE_FAILED -> "Ugradylmady"
        m.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || m.type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Ugradylýar…"
        m.status == Telephony.Sms.STATUS_COMPLETE -> "Eltildi ✓✓"
        m.status == Telephony.Sms.STATUS_FAILED -> "Eltilmedi"
        else -> "Ugradyldy ✓"
    }
}

@Composable
fun App(initialAddress: String?) {
    val ctx = LocalContext.current
    val repo = remember { SmsRepository(ctx.applicationContext) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(
            if (initialAddress.isNullOrBlank()) Screen.Home else Screen.New(initialAddress)
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result[Manifest.permission.READ_SMS] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permLauncher.launch(PERMISSIONS) }

    LaunchedEffect(Unit) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            permLauncher.launch(PERMISSIONS)
        }
    }

    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                tick++
            }
        }
        ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Habarlary görkezmek üçin SMS rugsadyny beriň.")
            Button(onClick = { permLauncher.launch(PERMISSIONS) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Rugsat sora")
            }
        }
        return
    }

    BackHandler(enabled = screen !is Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        is Screen.Home -> ConversationList(
            repo, tick,
            onOpen = { screen = Screen.Thread(it.threadId, it.address, it.name) },
            onNew = { screen = Screen.New("") },
            onSearch = { screen = Screen.Search },
            onBlocked = { screen = Screen.Blocked }
        )
        is Screen.Search -> SearchScreen(
            repo, tick,
            onBack = { screen = Screen.Home },
            onOpen = { screen = Screen.Thread(it.threadId, it.address, it.name) }
        )
        is Screen.Blocked -> BlockedScreen(
            repo,
            onBack = { screen = Screen.Home },
            onOpen = { a -> screen = Screen.Thread(repo.threadIdFor(a), a, repo.displayName(a)) }
        )
        is Screen.Thread -> ThreadScreen(
            repo, tick, s.threadId, s.address, s.name,
            onBack = { screen = Screen.Home }
        )
        is Screen.New -> NewMessageScreen(
            repo, s.address,
            onBack = { screen = Screen.Home },
            onSent = { addr -> screen = Screen.Thread(repo.threadIdFor(addr), addr, repo.displayName(addr)) }
        )
    }
}

// ------------------------------------------------------------------ Sanaw
@Composable
fun ConversationList(
    repo: SmsRepository,
    tick: Int,
    onOpen: (Conversation) -> Unit,
    onNew: () -> Unit,
    onSearch: () -> Unit,
    onBlocked: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var convs by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var toDelete by remember { mutableStateOf<Conversation?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        convs = withContext(Dispatchers.IO) { repo.conversations() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Habarlar") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Gözleg") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menýu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Bloklananlar") },
                                onClick = { menuOpen = false; onBlocked() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "Täze habar") }
        }
    ) { pad ->
        if (convs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Habar ýok") }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(convs, key = { it.threadId }) { c ->
                    ListItem(
                        headlineContent = {
                            Text(
                                c.name, maxLines = 1,
                                fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        supportingContent = {
                            Text(
                                c.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        trailingContent = { Text(fmt(c.date), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(c) },
                            onLongClick = { toDelete = c }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val d = toDelete
    if (d != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Söhbeti pozmalymy?") },
            text = { Text(d.name) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { repo.deleteThread(d.threadId) }
                        if (!ok) {
                            Toast.makeText(
                                ctx, "Pozup bolmady (default SMS programma bolmaly)", Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) { Text("Poz") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Ýok") } }
        )
    }
}

// ------------------------------------------------------------------ Gözleg
@Composable
fun SearchScreen(
    repo: SmsRepository,
    tick: Int,
    onBack: () -> Unit,
    onOpen: (SearchHit) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query, tick) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = emptyList()
        } else {
            delay(300)
            hits = withContext(Dispatchers.IO) { repo.search(q) }
        }
    }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text("Habarlardan gözle") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza")
                    }
                }
            )
        }
    ) { pad ->
        if (query.isNotBlank() && hits.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Hiç zat tapylmady")
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(hits, key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text(h.name, maxLines = 1) },
                        supportingContent = { Text(h.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(fmt(h.date), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { onOpen(h) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Bloklananlar
@Composable
fun BlockedScreen(repo: SmsRepository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var list by remember { mutableStateOf(repo.blockedList()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bloklananlar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza")
                    }
                }
            )
        }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Bloklanan nomer ýok")
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(list, key = { it }) { a ->
                    ListItem(
                        headlineContent = { Text(repo.displayName(a)) },
                        supportingContent = { Text(a) },
                        trailingContent = {
                            TextButton(onClick = {
                                repo.unblock(a)
                                list = repo.blockedList()
                            }) { Text("Bloklaňy aç") }
                        },
                        modifier = Modifier.clickable { onOpen(a) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Söhbet
@Composable
fun ThreadScreen(
    repo: SmsRepository,
    tick: Int,
    threadId: Long,
    address: String,
    name: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var localTick by remember { mutableIntStateOf(0) }
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Msg?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(repo.isBlocked(address)) }
    val listState = rememberLazyListState()

    LaunchedEffect(tick, localTick) {
        msgs = withContext(Dispatchers.IO) { repo.messages(threadId) }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) {
            listState.scrollToItem(msgs.size - 1)
            withContext(Dispatchers.IO) { repo.markRead(threadId) }
        }
    }

    fun sendNow(t: String, fromField: Boolean) {
        if (t.isBlank()) return
        if (fromField) text = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { repo.send(address, t) }
            if (r.isFailure) {
                if (fromField) text = t
                Toast.makeText(ctx, "Ugradyp bolmady: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            } else {
                localTick++
            }
        }
    }

    val lastMineId = msgs.lastOrNull { it.isMine }?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menýu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (blocked) "Bloklaňy aç" else "Nomeri blokla") },
                                onClick = {
                                    menuOpen = false
                                    if (blocked) {
                                        repo.unblock(address)
                                        blocked = false
                                        Toast.makeText(ctx, "Bloklaňy açyldy", Toast.LENGTH_SHORT).show()
                                    } else {
                                        repo.block(address)
                                        Toast.makeText(ctx, "Nomer bloklandy", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            ComposeBar(
                repo = repo,
                value = text,
                onChange = { text = it },
                onSend = { sendNow(text.trim(), true) },
                onSticker = { s -> sendNow(s, false) }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                Bubble(
                    m,
                    showStatus = m.id == lastMineId || m.type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                    onLongClick = { selected = m }
                )
            }
        }
    }

    val sel = selected
    if (sel != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Ýap") } },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        REACTIONS.forEach { e ->
                            Text(
                                e, fontSize = 22.sp,
                                modifier = Modifier.clickable {
                                    repo.setReaction(sel.id, e)
                                    selected = null
                                    localTick++
                                }.padding(4.dp)
                            )
                        }
                    }
                    if (sel.reaction != null) {
                        TextButton(onClick = {
                            repo.setReaction(sel.id, null)
                            selected = null
                            localTick++
                        }) { Text("Reaksiýany aýyr") }
                    }
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("sms", sel.body))
                        Toast.makeText(ctx, "Kopirlendi", Toast.LENGTH_SHORT).show()
                        selected = null
                    }) { Text("Kopirle") }
                    if (sel.isMine && sel.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                        TextButton(onClick = {
                            selected = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    repo.deleteMessage(sel.id)
                                    repo.send(address, sel.body)
                                }
                                if (r.isFailure) {
                                    Toast.makeText(ctx, "Ugradyp bolmady", Toast.LENGTH_LONG).show()
                                }
                                localTick++
                            }
                        }) { Text("Täzeden ugrat") }
                    }
                    TextButton(onClick = {
                        selected = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { repo.deleteMessage(sel.id) }
                            if (!ok) {
                                Toast.makeText(
                                    ctx, "Pozup bolmady (default SMS programma bolmaly)", Toast.LENGTH_LONG
                                ).show()
                            }
                            localTick++
                        }
                    }) { Text("Habary poz") }
                }
            }
        )
    }
}

@Composable
fun Bubble(m: Msg, showStatus: Boolean, onLongClick: () -> Unit) {
    val sticker = m.body.trim() in STICKERS
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (m.isMine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(18.dp)
                )
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(m.body, fontSize = if (sticker) 44.sp else 16.sp)
            Text(fmt(m.date), style = MaterialTheme.typography.labelSmall)
        }
        if (m.reaction != null) {
            Text(m.reaction, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (showStatus) {
            statusText(m)?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Ýazmak paneli (SIM + emoji + stiker)
@Composable
fun SimSelector(repo: SmsRepository) {
    val sims = remember { repo.sims() }
    if (sims.size < 2) return
    var selected by remember { mutableIntStateOf(repo.selectedSubId()) }
    var open by remember { mutableStateOf(false) }
    val cur = sims.firstOrNull { it.subId == selected } ?: sims.first()
    Box(Modifier.padding(start = 8.dp)) {
        TextButton(onClick = { open = true }) { Text("SIM ${cur.slot + 1} · ${cur.name} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sims.forEach { s ->
                DropdownMenuItem(
                    text = { Text("SIM ${s.slot + 1} · ${s.name}") },
                    onClick = {
                        repo.setSelectedSubId(s.subId)
                        selected = s.subId
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
fun EmojiPanel(onEmoji: (String) -> Unit, onSticker: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().height(240.dp)) {
        Row {
            TextButton(onClick = { tab = 0 }) { Text("Emoji") }
            TextButton(onClick = { tab = 1 }) { Text("Stikerler") }
        }
        if (tab == 0) {
            LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxSize()) {
                gridItems(EMOJIS) { e ->
                    Box(Modifier.clickable { onEmoji(e) }.padding(6.dp), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 26.sp)
                    }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.fillMaxSize()) {
                gridItems(STICKERS) { e ->
                    Box(Modifier.clickable { onSticker(e) }.padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 48.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ComposeBar(
    repo: SmsRepository,
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onSticker: (String) -> Unit
) {
    var emojiOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        SimSelector(repo)
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                emojiOpen = !emojiOpen
                if (emojiOpen) focus.clearFocus()
            }) { Text(if (emojiOpen) "ABC" else "😊", fontSize = 20.sp) }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Habar ýazyň") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ugrat")
            }
        }
        if (emojiOpen) {
            EmojiPanel(onEmoji = { onChange(value + it) }, onSticker = onSticker)
        }
    }
}

// ------------------------------------------------------------------ Täze habar
@Composable
fun NewMessageScreen(
    repo: SmsRepository,
    presetAddress: String,
    onBack: () -> Unit,
    onSent: (String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(presetAddress) }
    var text by remember { mutableStateOf("") }

    fun sendNow(t: String) {
        val addr = to.trim()
        if (addr.isEmpty()) {
            Toast.makeText(ctx, "Nomer ýazyň", Toast.LENGTH_SHORT).show()
            return
        }
        if (t.isBlank()) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { repo.send(addr, t) }
            if (r.isSuccess) onSent(addr)
            else Toast.makeText(ctx, "Ugradyp bolmady: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Täze habar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza")
                    }
                }
            )
        },
        bottomBar = {
            ComposeBar(
                repo = repo,
                value = text,
                onChange = { text = it },
                onSend = { sendNow(text.trim()) },
                onSticker = { s -> sendNow(s) }
            )
        }
    ) { pad ->
        OutlinedTextField(
            value = to,
            onValueChange = { to = it },
            modifier = Modifier.padding(pad).padding(12.dp).fillMaxWidth(),
            label = { Text("Nomer") },
            singleLine = true
        )
    }
}
