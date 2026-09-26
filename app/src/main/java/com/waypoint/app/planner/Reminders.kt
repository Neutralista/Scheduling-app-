package com.waypoint.app.planner

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A reminder — take medicine, water the plants: no length and not planned into the day, just a
 * time (or several) that posts a pinned notification, there until it's marked done or skipped. It
 * happens once ([rule] is [RecurrenceRule.OneOff]) or repeats ([rule], every day when null).
 */
@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val note: String = "",
    /** "HH:MM", one per time a day it's due. */
    val times: List<String> = listOf("09:00"),
    val rule: RecurrenceRule? = null,
    val enabled: Boolean = true,
    /** Rings like the alarm clock (full screen, until dismissed or snoozed) instead of a notification. */
    val alarm: Boolean = false,
    /** Unused: reminders ring once, at their time (kept so older saved reminders still load). */
    val nagMinutes: Int = 0
)

enum class ReminderStatus { DONE, SKIPPED }

/** One time a reminder is due: [reminder] on [date] at [time] ("HH:MM"). */
data class ReminderOccurrence(val reminder: Reminder, val date: LocalDate, val time: String) {
    val key: String get() = occurrenceKey(reminder.id, date, time)
    val atMs: Long get() {
        val t = parseReminderTime(time) ?: LocalTime.of(9, 0)
        return date.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

fun occurrenceKey(reminderId: String, date: LocalDate, time: String) = "$date|$reminderId|$time"

fun parseReminderTime(time: String): LocalTime? = runCatching {
    val (h, m) = time.split(":").map { it.trim().toInt() }
    LocalTime.of(h, m)
}.getOrNull()

val Reminder.isOneOff: Boolean get() = rule is RecurrenceRule.OneOff

fun Reminder.occursOn(date: LocalDate): Boolean = enabled && (rule?.occursOn(date) ?: true)

/** Its times on [date], in order; none when it isn't due that day. */
fun Reminder.occurrencesOn(date: LocalDate): List<ReminderOccurrence> =
    if (!occursOn(date)) emptyList()
    else times.distinct().sortedBy { parseReminderTime(it) ?: LocalTime.MAX }.map { ReminderOccurrence(this, date, it) }

/** The next time it's due after [afterMs] that hasn't been [settled], within a year and a bit. */
fun Reminder.nextOccurrenceAfter(afterMs: Long, today: LocalDate, settled: (String) -> Boolean): ReminderOccurrence? {
    if (!enabled) return null
    var date = today.minusDays(1)
    repeat(400) {
        occurrencesOn(date).firstOrNull { it.atMs > afterMs && !settled(it.key) }?.let { return it }
        date = date.plusDays(1)
    }
    return null
}

/**
 * What the to-do list shows for [today]: every time due today, plus a one-off's times from
 * earlier days that were never done or skipped (they stay until they are).
 */
fun remindersForDay(reminders: List<Reminder>, today: LocalDate, settled: (String) -> Boolean): List<ReminderOccurrence> =
    reminders.flatMap { r ->
        val todays = r.occurrencesOn(today)
        val overdueOneOff = (r.rule as? RecurrenceRule.OneOff)
            ?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            ?.takeIf { r.enabled && it.isBefore(today) }
            ?.let { date -> r.times.map { ReminderOccurrence(r, date, it) }.filterNot { settled(it.key) } }
            .orEmpty()
        overdueOneOff + todays
    }.sortedBy { it.atMs }

/** Reminders (`wp_reminders`) and what was done with each time they were due (`wp_reminder_log`). */
class ReminderStore(context: Context) {
    private val reminders = context.getSharedPreferences("wp_reminders", Context.MODE_PRIVATE)
    private val log = context.getSharedPreferences("wp_reminder_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(): List<Reminder> = reminders.all.values.mapNotNull { raw ->
        (raw as? String)?.let { runCatching { json.decodeFromString<Reminder>(it) }.getOrNull() }
    }.sortedBy { it.title.lowercase() }

    fun load(id: String): Reminder? =
        reminders.getString(id, null)?.let { runCatching { json.decodeFromString<Reminder>(it) }.getOrNull() }

    fun save(reminder: Reminder) = reminders.edit().putString(reminder.id, json.encodeToString(reminder)).apply()

    fun delete(id: String) {
        reminders.edit().remove(id).apply()
        log.edit().apply { log.all.keys.filter { it.split("|").getOrNull(1) == id }.forEach { remove(it) } }.apply()
    }

    fun status(key: String): ReminderStatus? =
        log.getString(key, null)?.substringBefore("|")?.let { runCatching { ReminderStatus.valueOf(it) }.getOrNull() }

    fun isSettled(key: String): Boolean = log.contains(key)

    fun setStatus(key: String, status: ReminderStatus, atMs: Long = System.currentTimeMillis()) =
        log.edit().putString(key, "${status.name}|$atMs").apply()

    fun clearStatus(key: String) = log.edit().remove(key).apply()

    /** Drops old log entries, and one-offs from past days whose every time was done or skipped. */
    fun prune(today: LocalDate = LocalDate.now()) {
        val cutoff = today.minusDays(60).toString()
        log.edit().apply { log.all.keys.filter { it.substringBefore("|") < cutoff }.forEach { remove(it) } }.apply()
        loadAll().filter { r ->
            val date = (r.rule as? RecurrenceRule.OneOff)?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            date != null && date.isBefore(today) && r.times.all { isSettled(occurrenceKey(r.id, date, it)) }
        }.forEach { delete(it.id) }
    }
}
