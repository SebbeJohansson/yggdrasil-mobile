package io.github.sebbejohansson.yggdrasil.norn

import io.github.sebbejohansson.yggdrasil.dellingr.Asker
import io.github.sebbejohansson.yggdrasil.llm.LlmMessage
import io.github.sebbejohansson.yggdrasil.llm.LlmRequest
import io.github.sebbejohansson.yggdrasil.llm.LlmRole
import io.github.sebbejohansson.yggdrasil.llm.LlmRouter
import io.github.sebbejohansson.yggdrasil.llm.LlmTier
import io.github.sebbejohansson.yggdrasil.llm.Privacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Norn answering a question on its own, for Dellingr's ask jobs. Like the
 * desktop Norn it knows the day: the date and time, and today's meetings
 * from the phone's calendars when it may read them. It stays on the phone.
 */
class NornAsker(
    private val llm: LlmRouter,
    private val calendar: CalendarReader,
    private val brain: Brain? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Asker {

    override suspend fun ask(prompt: String): String {
        val now = Instant.now()
        val z = zone()
        val context = buildString {
            append("It is ").append(DAY.format(now.atZone(z))).append(".\n")
            if (calendar.allowed()) {
                val start = now.atZone(z).truncatedTo(ChronoUnit.DAYS)
                val events = withContext(Dispatchers.IO) { calendar.between(start.toInstant(), start.plusDays(1).toInstant()) }
                    .filter { !it.declined }
                if (events.isEmpty()) {
                    append("The calendar has nothing today.\n")
                } else {
                    append("Today's calendar:\n")
                    for (e in events) {
                        val at = if (e.allDay) "all day" else "${TIME.format(e.begin.atZone(z))}–${TIME.format(e.end.atZone(z))}"
                        append("- ").append(at).append(" ").append(e.title).append('\n')
                    }
                }
            }
        }
        // What Norn remembers about you that bears on the question.
        val remembered = brain?.let { Brain.context(it.recallFor(prompt)) }.orEmpty()
        return llm.complete(
            LlmRequest(
                messages = listOf(LlmMessage(LlmRole.USER, prompt)),
                system = listOf(SYSTEM, context, remembered).filter { it.isNotBlank() }.joinToString("\n\n"),
                tier = LlmTier.LARGE,
                privacy = Privacy.ON_DEVICE_ONLY,
                maxOutputTokens = 600,
            ),
        )
    }

    private companion object {
        const val SYSTEM = """You are Yggdrasil, a personal assistant on the user's phone. You are answering on a schedule, so
the user isn't there to ask back: answer in a few short lines, plainly, and say so when you don't know."""
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
