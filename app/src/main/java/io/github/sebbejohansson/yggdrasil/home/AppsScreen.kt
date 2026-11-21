package io.github.sebbejohansson.yggdrasil.home

import android.graphics.Rect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.HudTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsViewModel(private val apps: InstalledApps, private val settings: HomeSettings) : ViewModel() {
    private val all = MutableStateFlow<List<AppEntry>?>(null)

    init {
        viewModelScope.launch {
            apps.changes().onStart { emit(Unit) }.collect { all.value = withContext(Dispatchers.IO) { apps.list() } }
        }
    }

    val query = MutableStateFlow("")

    data class State(val loading: Boolean, val pinned: List<AppEntry>, val found: List<AppEntry>, val pins: List<String>)

    val state = combine(all, query, settings.pins) { a, q, pins ->
        val list = a.orEmpty()
        State(a == null, if (q.isBlank()) AppCatalog.pinned(list, pins) else emptyList(), AppCatalog.search(list, q), pins)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State(true, emptyList(), emptyList(), emptyList()))

    fun open(app: AppEntry, bounds: Rect?) {
        if (apps.open(app, bounds)) query.value = ""
    }

    fun pin(app: AppEntry) = settings.setPins(AppCatalog.pin(settings.pins.value, app.key))
    fun unpin(app: AppEntry) = settings.setPins(AppCatalog.unpin(settings.pins.value, app.key))
    fun move(app: AppEntry, by: Int) = settings.setPins(AppCatalog.move(settings.pins.value, app.key, by))
    fun details(app: AppEntry) = apps.details(app)
    fun uninstall(app: AppEntry) = apps.uninstall(app)
    fun icon(app: AppEntry, px: Int) = apps.icon(app, px)

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { AppsViewModel(c.installedApps, c.homeSettings) } }
    }
}

/**
 * The home screen's app list: your pins on top, then every app A-Z under its
 * letter. Type to search (Enter opens the first match). Long-press an app to
 * pin, move, look at or uninstall it.
 *
 * [homePresses] counts Home-button presses: each one clears the search and
 * goes back to the top.
 */
@Composable
fun AppsScreen(vm: AppsViewModel, homePresses: Int, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(homePresses) {
        vm.query.value = ""
        focus.clearFocus()
        list.scrollToItem(0)
    }
    LaunchedEffect(query) { if (query.isNotEmpty()) list.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        HudTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = "Search apps",
            leading = "›",
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { s.found.firstOrNull()?.let { vm.open(it, null); focus.clearFocus() } }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp).focusRequester(searchFocus),
        )
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (s.pinned.isNotEmpty()) {
                item("pinned-head") { HudSection("Pinned", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(s.pinned, key = { "pin:" + it.key }) { app ->
                    AppRow(vm, app, pinned = true, first = app == s.pinned.first(), last = app == s.pinned.last())
                }
            }
            if (query.isBlank()) {
                AppCatalog.sections(s.found).forEach { (letter, apps) ->
                    item("letter:$letter") { HudLabel(letter, color = Hud.cyan, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)) }
                    items(apps, key = { it.key }) { app -> AppRow(vm, app, pinned = app.key in s.pins) }
                }
            } else {
                items(s.found, key = { it.key }) { app -> AppRow(vm, app, pinned = app.key in s.pins) }
                if (s.found.isEmpty()) {
                    item("none") { Text("No app called that.", style = HudText.meta, modifier = Modifier.padding(16.dp)) }
                }
            }
            if (s.loading) item("loading") { HudLabel("Reading apps…", modifier = Modifier.padding(16.dp)) }
            item("end") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(vm: AppsViewModel, app: AppEntry, pinned: Boolean, first: Boolean = false, last: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val px = with(LocalDensity.current) { 30.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.key) { value = withContext(Dispatchers.IO) { vm.icon(app, px) } }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { c -> c.boundsInWindow().let { b -> bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()) } }
                .combinedClickable(onClick = { vm.open(app, bounds) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp)) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    ?: Box(Modifier.fillMaxSize().background(Hud.surface0, RoundedCornerShape(8.dp)))
            }
            Spacer(Modifier.width(14.dp))
            Text(app.label, style = HudText.body, maxLines = 1, modifier = Modifier.weight(1f))
            if (app.work) HudLabel("work", color = Hud.overlay0)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Hud.base2) {
            @Composable fun item(text: String, action: () -> Unit) =
                DropdownMenuItem(text = { Text(text, style = HudText.body) }, onClick = { menu = false; action() })
            if (pinned) {
                item("Unpin") { vm.unpin(app) }
                if (!first) item("Move up") { vm.move(app, -1) }
                if (!last) item("Move down") { vm.move(app, +1) }
            } else {
                item("Pin to top") { vm.pin(app) }
            }
            item("App info") { vm.details(app) }
            item("Uninstall") { vm.uninstall(app) }
        }
    }
}

/**
 * Settings → Home screen: make Ygg the home screen, or go back to another. Android
 * asks you to confirm either way, and you can always change it in Settings →
 * Apps → Default apps → Home app.
 */
@Composable
fun HomeScreenPanel(held: Boolean, onUse: () -> Unit, onChange: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudSection("Home screen")
        Text(
            if (held) "Ygg is your home screen. Home opens it, with every app under Apps." else
                "Make Ygg the phone's home screen: Home opens Ygg, with your apps under Apps. It's optional, and you can switch back any time.",
            style = HudText.meta,
        )
        io.github.sebbejohansson.yggdrasil.ui.HudButton(
            if (held) "Choose another home app" else "Use Ygg as home screen",
            if (held) onChange else onUse,
            kind = io.github.sebbejohansson.yggdrasil.ui.HudButtonKind.GHOST,
        )
    }
}
