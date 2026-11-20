package io.github.sebbejohansson.yggdrasil

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsScreen
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsViewModel
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesScreen
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesViewModel
import io.github.sebbejohansson.yggdrasil.norn.ChatScreen
import io.github.sebbejohansson.yggdrasil.norn.ChatViewModel
import io.github.sebbejohansson.yggdrasil.ui.YggTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Which tab a notification asked for. */
    private val requestedTab = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab.value = intent.tabExtra()
        val container = (application as YggApp).container
        setContent { YggTheme { Root(container, requestedTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.tabExtra()
    }

    private fun Intent.tabExtra() = getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_CHAT = 0
        const val TAB_ALERTS = 1
        const val TAB_MESSAGES = 2
    }
}

@Composable
private fun Root(container: AppContainer, requestedTab: MutableStateFlow<Int?>) {
    var tab by rememberSaveable { mutableIntStateOf(MainActivity.TAB_CHAT) }
    val asked by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(asked) {
        asked?.let { tab = it; requestedTab.value = null }
    }

    val chat: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val alerts: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(container))
    val messages: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(container))

    // Ask once for notifications (Android 13+), and look again on every return:
    // the user may have changed it in Android's settings.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        messages.recheckPermission()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(Unit) {
        messages.recheckPermission()
        onPauseOrDispose { }
    }

    val alertState by alerts.state.collectAsStateWithLifecycle()
    val messageState by messages.state.collectAsStateWithLifecycle()
    val openAlerts = alertState.active.count { it.alert.state == AlertState.OPEN }
    val unseen = messageState.messages.count { it.seenAt == null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab(tab == MainActivity.TAB_CHAT, "Norn", 0) { tab = MainActivity.TAB_CHAT }
                Tab(tab == MainActivity.TAB_ALERTS, "Alerts", openAlerts) { tab = MainActivity.TAB_ALERTS }
                Tab(tab == MainActivity.TAB_MESSAGES, "Messages", unseen) { tab = MainActivity.TAB_MESSAGES }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad).consumeWindowInsets(pad)
        when (tab) {
            MainActivity.TAB_ALERTS -> AlertsScreen(alerts, m)
            MainActivity.TAB_MESSAGES -> MessagesScreen(messages, m)
            else -> Box(m) { ChatScreen(chat) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(selected: Boolean, label: String, count: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        icon = {
            BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                Icon(painterResource(R.drawable.ic_notification), contentDescription = null)
            }
        },
    )
}
