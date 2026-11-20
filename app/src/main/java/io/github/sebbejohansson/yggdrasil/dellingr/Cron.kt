package io.github.sebbejohansson.yggdrasil.dellingr

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Five-field cron (minute hour day-of-month month day-of-week), and turning
 * words like "every weekday at 8" into it. Ported from Yggdrasil's
 * `dellingr/cron.go`. Words are turned into cron once, when a job is saved,
 * and never read again at run time.
 */
data class Schedule(
    val minute: Set<Int>,
    val hour: Set<Int>,
    val day: Set<Int>,
    val month: Set<Int>,
    val weekday: Set<Int>,
    /** As in cron: when both day fields are restricted, either may match. */
    val anyDay: Boolean,
    val anyWeekday: Boolean,
) {
    private fun dayMatches(t: ZonedDateTime): Boolean {
        val d = t.dayOfMonth in day
        val w = (t.dayOfWeek.value % 7) in weekday // Sunday is 0
        return when {
            anyDay && anyWeekday -> true
            anyDay -> w
            anyWeekday -> d
            else -> d || w
        }
    }

    /** The first time after [after] the schedule fires, in [zone]; null when none comes within five years. */
    fun next(after: ZonedDateTime, zone: ZoneId): ZonedDateTime? {
        var t = after.withZoneSameInstant(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t.isBefore(limit)) {
            t = when {
                t.monthValue !in month -> t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1)
                !dayMatches(t) -> t.truncatedTo(ChronoUnit.DAYS).plusDays(1)
                t.hour !in hour -> t.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                t.minute !in minute -> t.plusMinutes(1)
                else -> return t
            }
        }
        return null
    }
}

class CronException(message: String) : IllegalArgumentException(message)

object Cron {
    private val ranges = arrayOf(0 to 59, 0 to 23, 1 to 31, 1 to 12, 0 to 6)

    /** Reads five fields: *, numbers, a-b ranges, lists and /steps. Sunday is 0 or 7. */
    fun parse(line: String): Schedule {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size != 5) throw CronException("cron needs five fields, not ${fields.size}")
        val sets = fields.mapIndexed { i, f ->
            val (low, high0) = ranges[i]
            val high = if (i == 4) 7 else high0
            try {
                field(f, low, high)
            } catch (e: CronException) {
                throw CronException("cron field ${i + 1} (\"$f\"): ${e.message}")
            }
        }.toMutableList()
        if (7 in sets[4]) sets[4] = sets[4] - 7 + 0
        return Schedule(sets[0], sets[1], sets[2], sets[3], sets[4], anyDay = fields[2] == "*", anyWeekday = fields[4] == "*")
    }

    private fun field(field: String, low: Int, high: Int): Set<Int> {
        val set = mutableSetOf<Int>()
        for (part0 in field.split(",")) {
            var part = part0
            var step = 1
            if ("/" in part) {
                val n = part.substringAfter("/").toIntOrNull()
                if (n == null || n < 1) throw CronException("a step must be a positive number")
                part = part.substringBefore("/")
                step = n
            }
            var from = low
            var to = high
            when {
                part == "*" -> Unit
                "-" in part -> {
                    from = part.substringBefore("-").toIntOrNull() ?: throw CronException("not a range")
                    to = part.substringAfter("-").toIntOrNull() ?: throw CronException("not a range")
                }
                else -> {
                    val n = part.toIntOrNull() ?: throw CronException("not a number")
                    from = n
                    to = if (step > 1) high else n
                }
            }
            if (from < low || to > high || from > to) throw CronException("must be within $low to $high")
            var n = from
            while (n <= to) {
                set += n
                n += step
            }
        }
        return set
    }

    private val weekdays = mapOf(
        "sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6,
        "sun" to 0, "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5, "sat" to 6,
    )
    private val weekdayNames = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

    private val atTime = Regex("""\bat (\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\b""")
    private val everyN = Regex("""^every (\d+) (minute|hour)s?$""")
    private val monthDay = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\b""")
    private val cronLike = Regex("""^[\d*/,\-]+(\s+[\d*/,\-]+){4}$""")
    private val wordsRe = Regex("[a-z]+")

    /**
     * A schedule in words, as cron. It understands: every N minutes or hours,
     * every hour, every day / weekday / weekend at a time, named days at a
     * time ("every monday and friday at 9"), a day of the month ("the 1st of
     * each month at 10"), and cron itself.
     */
    fun words(input: String): String {
        val text = input.trim().lowercase().replace(" o'clock", "").replace("  ", " ")
        if (cronLike.matches(text)) {
            parse(text)
            return text.split(Regex("\\s+")).joinToString(" ")
        }
        everyN.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return if (m.groupValues[2] == "minute") {
                if (n !in 1..59) throw CronException("every 1 to 59 minutes")
                "*/$n * * * *"
            } else {
                if (n !in 1..23) throw CronException("every 1 to 23 hours")
                "0 */$n * * *"
            }
        }
        when (text) {
            "every minute" -> return "* * * * *"
            "every hour", "hourly" -> return "0 * * * *"
        }
        val m = atTime.find(text)
            ?: throw CronException("say when, e.g. \"every weekday at 8\", \"every monday at 9:30\", \"the 1st of each month at 10\"")
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour > 23 || minute > 59) throw CronException("that time doesn't exist")
        val rest = atTime.replace(text, "").trim()
        val at = "$minute $hour"
        when {
            "weekday" in rest -> return "$at * * 1-5"
            "weekend" in rest -> return "$at * * 0,6"
            "month" in rest -> {
                val day = monthDay.find(rest)?.groupValues?.get(1)?.toInt() ?: 1
                if (day !in 1..28) throw CronException("a day of the month from the 1st to the 28th, so every month has it")
                return "$at $day * *"
            }
        }
        val days = wordsRe.findAll(rest).mapNotNull { weekdays[it.value.removeSuffix("s")] }.toList().sorted()
        if (days.isNotEmpty()) return "$at * * ${days.joinToString(",")}"
        if (rest.isEmpty() || listOf("day", "daily", "night", "morning").any { it in rest }) return "$at * * *"
        throw CronException("could not tell which days from \"$rest\"")
    }

    /** A cron line in plain words, for showing back. */
    fun said(line: String): String {
        val f = line.trim().split(Regex("\\s+"))
        if (f.size != 5) return "cron $line"
        val (minute, hour, day, month, weekday) = f
        if (month != "*") return "cron $line"
        if (minute.startsWith("*/") && hour == "*" && day == "*" && weekday == "*") return "every ${minute.removePrefix("*/")} minutes"
        val m = minute.toIntOrNull()
        if (minute == "*" && hour == "*" && day == "*" && weekday == "*") return "every minute"
        if (m != null && hour == "*" && day == "*" && weekday == "*") return "every hour at :%02d".format(m)
        if (hour.startsWith("*/") && m != null && day == "*" && weekday == "*") return "every ${hour.removePrefix("*/")} hours at :%02d".format(m)
        val h = hour.toIntOrNull()
        if (m == null || h == null) return "cron $line"
        val at = "at %02d:%02d".format(h, m)
        return when {
            day == "*" && weekday == "*" -> "every day $at"
            day == "*" && weekday == "1-5" -> "Monday to Friday $at"
            day == "*" && (weekday == "0,6" || weekday == "6,0") -> "Saturdays and Sundays $at"
            day == "*" -> {
                val names = weekday.split(",").map { it.toIntOrNull()?.takeIf { n -> n in 0..7 } ?: return "cron $line" }
                    .map { weekdayNames[it % 7] }
                if (names.size > 1) names.dropLast(1).joinToString(", ") + " and " + names.last() + " " + at else names[0] + " " + at
            }
            weekday == "*" -> day.toIntOrNull()?.let { "the ${ordinal(it)} of each month $at" } ?: "cron $line"
            else -> "cron $line"
        }
    }

    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }
}
