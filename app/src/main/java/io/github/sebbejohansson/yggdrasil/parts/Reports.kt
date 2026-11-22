package io.github.sebbejohansson.yggdrasil.parts

import io.github.sebbejohansson.yggdrasil.core.Loops
import io.github.sebbejohansson.yggdrasil.dellingr.DellingrService
import io.github.sebbejohansson.yggdrasil.dellingr.JobsUiState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertState
import io.github.sebbejohansson.yggdrasil.gjallar.AlertsUiState
import io.github.sebbejohansson.yggdrasil.hermodr.Focus
import io.github.sebbejohansson.yggdrasil.hermodr.MessageState
import io.github.sebbejohansson.yggdrasil.hermodr.MessagesUiState
import io.github.sebbejohansson.yggdrasil.huginn.HuginnUiState
import io.github.sebbejohansson.yggdrasil.llm.ProviderStatus
import io.github.sebbejohansson.yggdrasil.norn.ChatUiState
import io.github.sebbejohansson.yggdrasil.ratatoskr.YouService
import io.github.sebbejohansson.yggdrasil.update.UpdateState
import io.github.sebbejohansson.yggdrasil.update.Updater
import io.github.sebbejohansson.yggdrasil.vedrfolnir.Readings
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private fun hhmm(t: Instant) = t.atZone(ZoneId.systemDefault()).format(clock)
private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/** What each part says about itself, from the states the screens already hold. */
fun partReports(
    chat: ChatUiState,
    messages: MessagesUiState,
    focus: Focus?,
    jobs: JobsUiState,
    huginn: HuginnUiState,
    readings: Readings?,
    phoneFailing: Set<String>,
    alerts: AlertsUiState,
    you: YouService,
    updater: Updater,
    loops: Loops,
    now: Instant,
    /** The watchers, when the container has them. */
    munin: io.github.sebbejohansson.yggdrasil.munin.Munin? = null,
    heimdall: io.github.sebbejohansson.yggdrasil.heimdall.Heimdall? = null,
    loki: io.github.sebbejohansson.yggdrasil.loki.Loki? = null,
): List<PartReport> {
    fun loop(l: Pair<String, Duration>) = loops.get(l.first, l.second)

    val local = chat.providers.firstOrNull { !it.leavesDevice }
    val claude = chat.providers.firstOrNull { it.leavesDevice }
    val failingJobs = jobs.jobs.count { it.failures >= DellingrService.FAILING_AFTER }
    val held = messages.messages.count { it.state == MessageState.HELD }
    val open = alerts.active.count { it.alert.state == AlertState.OPEN }
    val sync = alerts.sync

    return listOf(
        PartReport(
            "norn",
            down = local?.status !is ProviderStatus.Ready,
            problem = when (val s = local?.status) {
                is ProviderStatus.NeedsSetup -> s.reason
                is ProviderStatus.Unavailable -> s.reason
                null -> "No model on this phone yet."
                else -> null
            },
            facts = listOfNotNull(
                local?.name,
                chat.backend?.let { "on $it" },
                "${chat.memories.size} memories",
                if (chat.embedderReady) "recall by meaning" else "recall by recency",
                "${chat.conversations.size} chats",
            ),
            loops = listOf(loop(Parts.NORN_PROMISES)),
        ),
        PartReport(
            "huginn",
            troubled = !huginn.notificationsAllowed,
            problem = if (!huginn.notificationsAllowed) "Can't read notifications: allow it under System → Huginn." else null,
            facts = listOf(
                "${huginn.rules.count { it.enabled }} rules on",
                "${huginn.reactions.size} reactions this week",
                if (huginn.calendarAllowed) "calendar read" else "no calendar",
            ),
            loops = listOf(loop(Parts.HUGINN_CALENDAR)),
        ),
        PartReport(
            "dellingr",
            troubled = failingJobs > 0 || !jobs.exactAllowed,
            problem = when {
                failingJobs > 0 -> "$failingJobs ${if (failingJobs == 1) "job keeps" else "jobs keep"} failing."
                !jobs.exactAllowed -> "Exact alarms aren't allowed: jobs run up to 15 minutes late."
                else -> null
            },
            facts = listOfNotNull(
                "${jobs.jobs.count { it.enabled }} jobs on",
                jobs.jobs.mapNotNull { it.nextRunAt }.filter { it.isAfter(now) }.minOrNull()?.let { "next at ${hhmm(it)}" },
            ),
            loops = listOf(loop(Parts.DELLINGR_TICK)),
        ),
        PartReport(
            "hermodr",
            troubled = messages.blocked != null,
            problem = messages.blocked?.let { "Notifications are off: $it." },
            facts = listOfNotNull(
                focus?.let { "focusing until ${hhmm(it.until)}" },
                messages.busyUntil?.takeIf { it.isAfter(now) }?.let { "busy until ${hhmm(it)}" },
                "$held held",
                "${messages.messages.count { it.seenAt == null }} unread",
                if (messages.quietHours.enabled) "quiet ${messages.quietHours.start}–${messages.quietHours.end}" else "no quiet hours",
            ),
        ),
        PartReport(
            "vedrfolnir",
            troubled = phoneFailing.isNotEmpty(),
            problem = phoneFailing.takeIf { it.isNotEmpty() }?.let { "Out of bounds: ${it.joinToString(", ")}." },
            facts = readings?.let { r ->
                listOfNotNull(
                    r.batteryPercent?.let { "battery $it%" + if (r.charging) " charging" else "" },
                    "storage ${gb(r.storageFreeBytes)} free",
                    "memory ${gb(r.memoryAvailBytes)} free",
                    r.batteryTempC?.let { "%.0f °C".format(it) },
                )
            } ?: listOf("no reading yet"),
            loops = listOf(loop(Parts.VEDRFOLNIR_CHECK)),
        ),
        PartReport(
            "saga",
            off = !sync.configured,
            troubled = sync.lastError != null,
            problem = when {
                !sync.configured -> "Mímir isn't set up: Settings → Connection."
                sync.lastError != null -> "Last sync failed: ${sync.lastError}"
                else -> null
            },
            facts = listOfNotNull(
                sync.lastSyncAt?.let { "synced ${Parts.ago(it, now)}" },
                "${sync.waiting} waiting to send".takeIf { sync.waiting > 0 },
                "${alerts.mirrors.size} desktop alerts",
            ),
            loops = if (sync.configured) listOf(loop(Parts.SAGA_SYNC)) else emptyList(),
        ),
        PartReport(
            "claude",
            off = claude?.status !is ProviderStatus.Ready,
            problem = when (val s = claude?.status) {
                is ProviderStatus.NeedsSetup -> s.reason
                is ProviderStatus.Unavailable -> s.reason
                else -> null
            },
            facts = listOf(if (chat.allowClaude) "may answer" else "not allowed to answer"),
        ),
        PartReport(
            "gjallar",
            facts = listOf("$open open", "${alerts.active.size - open} seen or snoozed", "${alerts.resolved.size} healed lately"),
        ),
        PartReport(
            "ratatoskr",
            facts = listOfNotNull(
                if (you.settings.on) "watching your rhythm" else "not watching your rhythm",
                you.status.value.active.keys.joinToString(", ") { it.title.lowercase() }.ifEmpty { null },
                "Ygg ${updater.installed}",
                (updater.state.value as? UpdateState.Available)?.let { "${it.release.version} is out" }
                    ?: (updater.state.value as? UpdateState.Ready)?.let { "${it.release.version} ready to install" },
            ),
            troubled = updater.state.value is UpdateState.Failed,
            problem = (updater.state.value as? UpdateState.Failed)?.message,
            loops = listOfNotNull(loop(Parts.RATATOSKR_YOU), loop(Parts.RATATOSKR_CHECK), loop(Parts.UPDATES_CHECK).takeIf { updater.state.value !is UpdateState.DevBuild }),
        ),
    ) + listOfNotNull(
        munin?.status?.value?.let { m ->
            PartReport(
                "munin",
                facts = listOfNotNull(
                    if (m.online) null else "the phone is offline: the internet's checks wait",
                    "${m.targets.count { it.up }} of ${m.targets.size} answer",
                    m.targets.filter { !it.up && it.detail != "offline" }.joinToString(", ") { it.target.name }.ifEmpty { null }?.let { "not answering: $it" },
                ),
                loops = listOf(loop(Parts.MUNIN_CHECK)),
            )
        },
        heimdall?.status?.value?.let { h ->
            val serious = h.findings.count { it.severity == "high" || it.severity == "critical" }
            PartReport(
                "heimdall",
                troubled = serious > 0,
                problem = h.repoError?.let { "can't read the repo's findings: $it" },
                facts = listOf("${h.findings.size} findings", "$serious that matter"),
                loops = listOf(loop(Parts.HEIMDALL_WATCH)),
            )
        },
        loki?.status?.value?.let { l ->
            PartReport(
                "loki",
                facts = listOfNotNull(
                    "${l.blindSpots.size} blind spots",
                    l.running?.let { "tricking ${it.trick.part} now" },
                    l.strikes.firstOrNull()?.let { "last strike: ${it.trick.part}, ${it.state}" },
                ),
                loops = listOf(loop(Parts.LOKI_PLAN)),
            )
        },
    )
}
