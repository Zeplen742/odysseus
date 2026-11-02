package dev.odysseus.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onStart() { super.onStart(); TurnHost.appVisible = true }
    override fun onStop() { TurnHost.appVisible = false; super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_SESSION)?.let { vm.openFromNotification(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) {
            intent?.getStringExtra(Notifier.EXTRA_SESSION)?.let { vm.openFromNotification(it) }
        }
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
                    when (vm.screen) {
                        Screen.SETUP -> SetupScreen(vm)
                        Screen.SESSIONS -> SessionsScreen(vm)
                        Screen.CHAT -> ChatScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.baseUrl) }
    var tok by remember { mutableStateOf(vm.token) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Connect to Odysseus", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("On your server open Settings → pair a device (/api/companion/pair), then paste the token or the pairing JSON below.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Server URL (https://odysseus.example.com)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(tok, { tok = it }, label = { Text("Token or pairing JSON") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button({ vm.connect(url, tok) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) "Connecting…" else "Connect")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(vm: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Odysseus") }, actions = {
                TextButton({ vm.refreshSessions() }) { Text("Refresh") }
                TextButton({ vm.signOut() }) { Text("Sign out") }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { vm.newChat() }) { Text("New chat") } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn {
                items(vm.sessions, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name.ifBlank { "Untitled" }, maxLines = 1) },
                        supportingContent = { Text(s.model, maxLines = 1) },
                        modifier = Modifier.clickable { vm.openSession(s.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    BackHandler { vm.back() }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val rows = vm.messages + if (vm.live.isNotEmpty()) listOf(ChatMessage("assistant", vm.live)) else emptyList()
    LaunchedEffect(rows.size, vm.live.length) { if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Text(vm.status ?: if (vm.streaming) "Generating…" else "Chat") },
                navigationIcon = { TextButton({ vm.back() }) { Text("Back") } })
        },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f), maxLines = 5)
                Spacer(Modifier.width(8.dp))
                Button({ vm.send(input); input = "" }, enabled = !vm.streaming && input.isNotBlank()) { Text("Send") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list) {
                items(rows.size) { i ->
                    val m = rows[i]
                    val mine = m.role == "user"
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Surface(
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            SelectionContainer { Text(m.content, Modifier.padding(10.dp)) }
                        }
                    }
                }
            }
        }
    }
}
