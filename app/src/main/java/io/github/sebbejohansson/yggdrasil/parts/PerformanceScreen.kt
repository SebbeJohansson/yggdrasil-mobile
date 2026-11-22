package io.github.sebbejohansson.yggdrasil.parts

import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.sebbejohansson.yggdrasil.core.AppUse
import io.github.sebbejohansson.yggdrasil.core.PhoneUse
import io.github.sebbejohansson.yggdrasil.core.PhoneNow
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.sebbejohansson.yggdrasil.core.DiskUse
import io.github.sebbejohansson.yggdrasil.core.DiskItem
import io.github.sebbejohansson.yggdrasil.core.BatteryNow
import io.github.sebbejohansson.yggdrasil.core.ProcGroup
import io.github.sebbejohansson.yggdrasil.core.Procs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebbejohansson.yggdrasil.AppContainer
import io.github.sebbejohansson.yggdrasil.core.PerfMath
import io.github.sebbejohansson.yggdrasil.core.PerfStep
import io.github.sebbejohansson.yggdrasil.core.ProcessNow
import io.github.sebbejohansson.yggdrasil.ui.Hud
import io.github.sebbejohansson.yggdrasil.ui.HudButton
import io.github.sebbejohansson.yggdrasil.ui.HudButtonKind
import io.github.sebbejohansson.yggdrasil.ui.HudLabel
import io.github.sebbejohansson.yggdrasil.ui.HudSection
import io.github.sebbejohansson.yggdrasil.ui.HudText
import io.github.sebbejohansson.yggdrasil.ui.hudPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** What the model is asked to do, in words. */
private val PURPOSES = mapOf(
    "chat" to "Chat",
    "memory" to "Learning facts",
    "promises" to "Finding promises",
    "names" to "Swapping names",
    "huginn" to "Huginn's briefs",
    "jobs" to "Dellingr's jobs",
    "embed" to "Memory by meaning",
    "ask" to "Asked",
)

private fun mb(kb: Long) = if (kb >= 1_000_000) "%.2f GB".format(kb / 1e6) else "%.0f MB".format(kb / 1e3)

/**
 * Performance, as the dashboard's (`perf.vue`), and what is pulling what:
 * how much the app takes right now, who asks the model for what and how
 * long it's busy, each part's rounds, and who moves the data. Over the last
 * day or week. Opened from Health.
 */
@Composable
fun PerformanceScreen(c: AppContainer, onBack: () -> Unit) {
    var week by rememberSaveable { mutableStateOf(false) }
    val version by c.perf.changes.flow.collectAsStateWithLifecycle()
    val steps by produceState(emptyList<PerfStep>(), week, version) {
        val since = Instant.now().minus(if (week) Duration.ofDays(7) else Duration.ofDays(1))
        value = withContext(Dispatchers.IO) { c.perf.since(since) }
    }
    val now by produceState<ProcessNow?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { ProcessNow.read() }
            delay(5_000)
        }
    }
    // Every process of the app's, Ygg's Linux included, read twice 3 s apart for CPU.
    val procs by produceState(emptyList<ProcGroup>()) {
        var before = withContext(Dispatchers.IO) { Procs.read() }
        var at = System.currentTimeMillis()
        while (true) {
            delay(3_000)
            val after = withContext(Dispatchers.IO) { Procs.read() }
            val t = System.currentTimeMillis()
            value = Procs.groups(before, after, t - at)
            before = after; at = t
        }
    }
    val context = LocalContext.current
    val battery by produceState<BatteryNow?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { BatteryNow.read(context) }.getOrNull() }
            delay(5_000)
        }
    }
    val phone by produceState<PhoneNow?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { PhoneNow.read(context) }.getOrNull() }
            delay(5_000)
        }
    }
    // Usage access is granted in Android's settings: looked at again whenever the screen comes back.
    var usageAccess by remember { mutableStateOf(PhoneUse.hasUsageAccess(context)) }
    LifecycleResumeEffect(Unit) {
        usageAccess = PhoneUse.hasUsageAccess(context)
        onPauseOrDispose { }
    }
    val apps by produceState(emptyList<AppUse>(), usageAccess, week) {
        if (!usageAccess) { value = emptyList(); return@produceState }
        val to = System.currentTimeMillis()
        value = withContext(Dispatchers.IO) { PhoneUse.apps(context, to - if (week) 7 * 86_400_000L else 86_400_000L, to) }
    }
    var appStorage by remember { mutableStateOf<List<AppUse>?>(null) }
    var disk by remember { mutableStateOf<List<DiskItem>?>(null) }
    var measuring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Performance", style = HudText.title)
                HudLabel("what is pulling what")
            }
            HudButton("Back", onBack, kind = HudButtonKind.SMALL)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudButton("Last day", { week = false }, kind = if (week) HudButtonKind.GHOST else HudButtonKind.PRIMARY)
            HudButton("Last week", { week = true }, kind = if (week) HudButtonKind.PRIMARY else HudButtonKind.GHOST)
        }

        HudSection("Right now")
        Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            now?.let { p ->
                Fact("Memory", "${mb(p.pssKb)} in all", "Java ${mb(p.javaHeapKb)} · native ${mb(p.nativeHeapKb)}")
                Fact("Gemma", if (c.localLlm.loaded) "in memory" else "not loaded", if (c.localLlm.loaded) "most of the memory above is the model" else "loads on the first question")
                Fact("Embeddings", if (c.embedder.loaded) "in memory" else "not loaded", null)
                Fact("CPU", "%.1f%% of a core".format(p.cpuPercent), "on average, ${PerfMath.time(p.cpuMs)} in ${PerfMath.time(p.upMs)} since the app started")
                Fact("Network", "${PerfMath.bytes(p.rxBytes)} in · ${PerfMath.bytes(p.txBytes)} out", "since the phone started")
            } ?: Text("Reading…", style = HudText.meta)
        }

        HudSection("What runs now", Modifier.padding(top = 6.dp))
        Text("Every process of Ygg's, Ygg's Linux included (Claude Code, herdr, the bridge): CPU over the last 3 s, as a share of one core.",
            style = HudText.meta)
        if (procs.isEmpty()) Text("Reading…", style = HudText.meta)
        val cpuAll = procs.sumOf { it.cpuPercent }
        val memAll = procs.sumOf { it.rssKb }.coerceAtLeast(1)
        if (procs.isNotEmpty()) Fact("In all", "%.0f%% CPU · %s".format(cpuAll, mb(memAll)), "${procs.sumOf { it.processes }} processes")
        procs.forEach { g ->
            RowView(
                title = g.group,
                share = (if (cpuAll >= 1) g.cpuPercent / cpuAll else g.rssKb.toDouble() / memAll).toFloat(),
                right = "%.0f%% · %s".format(g.cpuPercent, mb(g.rssKb)),
                line = "${g.processes} ${if (g.processes == 1) "process" else "processes"} · " +
                    g.commands.joinToString(" · ") { it.substringAfterLast('/').take(48) },
                warn = g.cpuPercent >= 80,
            )
        }

        HudSection("Battery", Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            battery?.let { b ->
                Fact("Level", "${b.percent}%" + if (b.charging) " · charging" else "", null)
                Fact("Drawing", b.currentMa?.let { "${kotlin.math.abs(it)} mA" + if (it > 0 && b.charging) " in" else "" } ?: "the phone doesn't say",
                    "the whole phone, screen included: Android doesn't tell an app its own share")
                b.temperatureC?.let { Fact("Temperature", "%.1f °C".format(it), if (it >= 40) "warm: heavy work slows down to cool" else null) }
            } ?: Text("Reading…", style = HudText.meta)
        }

        HudSection("Storage", Modifier.padding(top = 6.dp))
        if (disk == null) {
            Text("What takes the phone's storage: the models, Ygg's Linux by part, and the app's own. Measuring takes a few seconds.", style = HudText.meta)
            HudButton(if (measuring) "Measuring…" else "Measure", {
                measuring = true
                scope.launch {
                    disk = withContext(Dispatchers.IO) { DiskUse.measure(context, c.modelStore.dir, c.ownLinux.rootfs) }
                    measuring = false
                }
            }, kind = HudButtonKind.SMALL, enabled = !measuring)
        }
        disk?.let { items ->
            val all = items.sumOf { it.bytes }.coerceAtLeast(1)
            Fact("In all", PerfMath.bytes(all), null)
            items.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.forEach { d ->
                RowView(title = d.label, share = d.bytes.toFloat() / all, right = PerfMath.bytes(d.bytes), line = d.note ?: "", warn = false)
            }
            HudButton("Measure again", { disk = null }, kind = HudButtonKind.SMALL)
        }

        HudSection("The phone", Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxWidth().hudPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            phone?.let { p ->
                Fact("Memory", "${PerfMath.bytes(p.ramTotal - p.ramAvailable)} of ${PerfMath.bytes(p.ramTotal)} in use",
                    if (p.lowMemory) "low: Android is closing apps to make room" else "${PerfMath.bytes(p.ramAvailable)} free")
                Fact("Storage", "${PerfMath.bytes(p.storageTotal - p.storageFree)} of ${PerfMath.bytes(p.storageTotal)} used", "${PerfMath.bytes(p.storageFree)} free")
                Fact("Heat", p.thermalWords, p.thermalHeadroom.takeIf { !it.isNaN() }?.let { "%.0f%% of the way to slowing down".format(it * 100) })
            } ?: Text("Reading…", style = HudText.meta)
        }
        HudButton("Battery by app (Android)", { runCatching { context.startActivity(PhoneUse.batteryIntent()) } }, kind = HudButtonKind.SMALL)
        if (!usageAccess) {
            Text(
                "Other apps' screen time, data and storage need Usage access, which you grant in Android's settings. " +
                    "It lets Ygg see which apps you use and when; it stays on the phone. CPU, memory and battery per app Android keeps to itself.",
                style = HudText.meta,
            )
            HudButton("Allow usage access", { runCatching { context.startActivity(PhoneUse.usageAccessIntent(context)) } }, kind = HudButtonKind.SMALL)
        } else {
            val shown = apps.take(12)
            val topTime = shown.maxOfOrNull { it.foregroundMs }?.coerceAtLeast(1) ?: 1
            HudLabel(if (week) "apps this week" else "apps today")
            if (shown.isEmpty()) Text("Reading…", style = HudText.meta)
            shown.forEach { a ->
                RowView(
                    title = a.label,
                    share = a.foregroundMs.toFloat() / topTime,
                    right = if (a.foregroundMs > 0) PerfMath.time(a.foregroundMs) else PerfMath.bytes(a.dataBytes),
                    line = listOfNotNull(
                        a.foregroundMs.takeIf { it > 0 }?.let { "${PerfMath.time(it)} on screen" },
                        a.mobileBytes.takeIf { it > 0 }?.let { "${PerfMath.bytes(it)} mobile" },
                        a.wifiBytes.takeIf { it > 0 }?.let { "${PerfMath.bytes(it)} Wi-Fi" },
                    ).joinToString(" · "),
                    warn = false,
                )
            }
            val byData = apps.filter { it.dataBytes > 0 }.sortedByDescending { it.dataBytes }.take(5)
            if (byData.isNotEmpty()) {
                HudLabel("most data")
                byData.forEach { a -> Fact(a.label, PerfMath.bytes(a.dataBytes), "${PerfMath.bytes(a.mobileBytes)} mobile · ${PerfMath.bytes(a.wifiBytes)} Wi-Fi") }
            }
            if (appStorage == null) {
                HudButton("Measure apps' storage", {
                    scope.launch { appStorage = withContext(Dispatchers.IO) { PhoneUse.storage(context) } }
                }, kind = HudButtonKind.SMALL)
            }
            appStorage?.let { list ->
                HudLabel("largest apps")
                val top = list.firstOrNull()?.storageBytes?.coerceAtLeast(1) ?: 1
                list.take(10).forEach { a ->
                    RowView(title = a.label, share = (a.storageBytes ?: 0).toFloat() / top, right = PerfMath.bytes(a.storageBytes ?: 0),
                        line = "cache ${PerfMath.bytes(a.cacheBytes ?: 0)}", warn = false)
                }
            }
        }

        val rows = PerfMath.rows(steps)
        val model = rows.filter { it.part == "model" }
        val rounds = rows.filter { it.part != "model" && it.amount == 0L }
        val network = rows.filter { it.part != "model" && it.amount > 0L }

        HudSection("Who pulls on the model", Modifier.padding(top = 6.dp))
        if (model.isEmpty()) Text("No model calls in this window.", style = HudText.meta)
        val busy = model.sumOf { it.totalMs }.coerceAtLeast(1)
        model.forEach { r ->
            RowView(
                title = (PURPOSES[r.kind] ?: r.kind) + (r.who?.let { " · ${it.substringBefore(" (")}" } ?: ""),
                share = r.totalMs.toFloat() / busy,
                right = "${PerfMath.time(r.totalMs)} busy",
                line = "${r.count} ${if (r.count == 1) "call" else "calls"} · p50 ${PerfMath.time(r.p50)} · p95 ${PerfMath.time(r.p95)}" +
                    (if (r.amount > 0) " · ${r.amount / 4} tokens out" else "") +
                    failedWords(r),
                warn = r.failed > 0,
            )
        }

        HudSection("The parts' rounds", Modifier.padding(top = 6.dp))
        if (rounds.isEmpty()) Text("No rounds timed yet.", style = HudText.meta)
        val roundsBusy = rounds.sumOf { it.totalMs }.coerceAtLeast(1)
        rounds.forEach { r ->
            RowView(
                title = "${Parts.INFO.firstOrNull { it.first == r.part }?.second ?: r.part} · ${r.kind}",
                share = r.totalMs.toFloat() / roundsBusy,
                right = PerfMath.time(r.totalMs),
                line = "${r.count} ${if (r.count == 1) "round" else "rounds"} · p50 ${PerfMath.time(r.p50)} · p95 ${PerfMath.time(r.p95)}" + failedWords(r),
                warn = r.failed > 0,
            )
        }

        HudSection("Who moves the data", Modifier.padding(top = 6.dp))
        if (network.isEmpty()) Text("Nothing downloaded or synced in this window.", style = HudText.meta)
        val moved = network.sumOf { it.amount }.coerceAtLeast(1)
        network.sortedByDescending { it.amount }.forEach { r ->
            RowView(
                title = "${Parts.INFO.firstOrNull { it.first == r.part }?.second ?: r.part.replaceFirstChar { it.uppercase() }} · ${r.kind}",
                share = r.amount.toFloat() / moved,
                right = PerfMath.bytes(r.amount),
                line = "${r.count} ${if (r.count == 1) "time" else "times"} · ${PerfMath.time(r.totalMs)} in all",
                warn = false,
            )
        }
        Text(
            "Timed on this phone and kept a week. Android doesn't tell an app its own battery use; time the model is busy is the closest " +
                "measure, as the model is what drains it.",
            style = HudText.meta.copy(color = Hud.overlay0),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun failedWords(r: PerfMath.Row) =
    if (r.failed == 0) "" else " · ${r.failed} failed" + (r.topFailure?.let { " (${it.first})" } ?: "")

@Composable
private fun Fact(label: String, value: String, note: String?) {
    Row(verticalAlignment = Alignment.Top) {
        HudLabel(label, modifier = Modifier.weight(0.32f))
        Column(Modifier.weight(0.68f)) {
            Text(value, style = HudText.small)
            note?.let { Text(it, style = HudText.meta) }
        }
    }
}

/** One row with a bar for its share of the section. */
@Composable
private fun RowView(title: String, share: Float, right: String, line: String, warn: Boolean) {
    Column(Modifier.fillMaxWidth().hudPanel(warn = warn).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = HudText.small.copy(color = Hud.bright), modifier = Modifier.weight(1f), maxLines = 1)
            HudLabel(right, color = Hud.cyan)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Hud.surface0)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().background(if (warn) Hud.amber else Hud.cyan.copy(alpha = 0.8f)))
        }
        Text(line, style = HudText.meta.copy(color = if (warn) Hud.amber else Hud.subtext0))
    }
}
