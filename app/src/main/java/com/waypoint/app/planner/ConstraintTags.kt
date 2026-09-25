package com.waypoint.app.planner

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The tags ("constraints") a task, block task or auto-placed block can carry, other than its time
 * of day, which has its own section in each sheet. One model for all three so they offer, store
 * and label the same things: [fromSpecs] reads them out of saved conditions and [toSpecs] writes
 * them back.
 */
data class ConstraintTags(
    val days: Set<Int> = emptySet(),
    /** Anything but days of week, which is [days]. */
    val recurrence: RecurrenceRule? = null,
    val afterTaskIds: Set<String> = emptySet(),
    val beforeTaskIds: Set<String> = emptySet(),
    val afterEventIds: Set<Long> = emptySet(),
    val beforeEventIds: Set<Long> = emptySet(),
    val duringEventId: Long? = null,
    val afterBlockIds: Set<String> = emptySet(),
    val beforeBlockIds: Set<String> = emptySet(),
    val sameDayIds: Set<String> = emptySet(),
    val notWithIds: Set<String> = emptySet(),
    /** Titles of the events tagged, so a tag keeps its name on days the event isn't on. */
    val eventTitles: Map<Long, String> = emptyMap()
) {
    val isEmpty: Boolean get() = this == ConstraintTags(eventTitles = eventTitles)

    fun toSpecs(): List<TaskConditionSpec> = buildList {
        if (days.isNotEmpty()) add(TaskConditionSpec("daysOfWeek", days = days.sorted()))
        recurrence?.toSpec()?.let(::add)
        if (afterTaskIds.isNotEmpty()) add(TaskConditionSpec("afterTask", referenceTaskIds = afterTaskIds.sorted()))
        if (beforeTaskIds.isNotEmpty()) add(TaskConditionSpec("beforeTask", referenceTaskIds = beforeTaskIds.sorted()))
        afterEventIds.sorted().forEach { add(TaskConditionSpec("afterCalEvent", calendarEventId = it, label = eventTitles[it])) }
        beforeEventIds.sorted().forEach { add(TaskConditionSpec("beforeCalEvent", calendarEventId = it, label = eventTitles[it])) }
        duringEventId?.let { add(TaskConditionSpec("duringCalEvent", calendarEventId = it, label = eventTitles[it])) }
        afterBlockIds.sorted().forEach { add(TaskConditionSpec("afterBlock", blockId = it)) }
        beforeBlockIds.sorted().forEach { add(TaskConditionSpec("beforeBlock", blockId = it)) }
        if (sameDayIds.isNotEmpty()) add(TaskConditionSpec("sameDayAs", referenceTaskIds = sameDayIds.sorted()))
        if (notWithIds.isNotEmpty()) add(TaskConditionSpec("notSameDayAs", referenceTaskIds = notWithIds.sorted()))
    }

    companion object {
        /** Condition types these tags own; everything else is left to the sheet. */
        val TYPES = setOf(
            "daysOfWeek", "oneOff", "everyNDays", "everyNWeeks", "everyNMonths", "nTimesPerPeriod",
            "afterTask", "beforeTask", "afterCalEvent", "beforeCalEvent", "duringCalEvent",
            "afterBlock", "beforeBlock", "sameDayAs", "notSameDayAs"
        )

        fun fromSpecs(specs: List<TaskConditionSpec>): ConstraintTags {
            fun ids(type: String) = specs.filter { it.type == type }.flatMap { it.referenceTaskIds.orEmpty() }.toSet()
            fun events(type: String) = specs.filter { it.type == type }.mapNotNull { it.calendarEventId }.toSet()
            fun blocks(type: String) = specs.filter { it.type == type }.mapNotNull { it.blockId }.toSet()
            return ConstraintTags(
                days = specs.filter { it.type == "daysOfWeek" }.flatMap { it.days.orEmpty() }.toSet(),
                recurrence = specs.firstNotNullOfOrNull { it.toRecurrence() },
                afterTaskIds = ids("afterTask"),
                beforeTaskIds = ids("beforeTask"),
                afterEventIds = events("afterCalEvent"),
                beforeEventIds = events("beforeCalEvent"),
                duringEventId = specs.firstOrNull { it.type == "duringCalEvent" }?.calendarEventId,
                afterBlockIds = blocks("afterBlock"),
                beforeBlockIds = blocks("beforeBlock"),
                sameDayIds = ids("sameDayAs"),
                notWithIds = ids("notSameDayAs"),
                eventTitles = specs.mapNotNull { s -> s.calendarEventId?.let { id -> s.label?.let { id to it } } }.toMap()
            )
        }
    }
}

fun RecurrenceRule.toSpec(): TaskConditionSpec = when (this) {
    is RecurrenceRule.OneOff          -> TaskConditionSpec("oneOff", oneOffDate = date)
    is RecurrenceRule.DaysOfWeek      -> TaskConditionSpec("daysOfWeek", days = days.sorted())
    is RecurrenceRule.EveryNDays      -> TaskConditionSpec("everyNDays", intervalN = n, anchorDate = anchorDate)
    is RecurrenceRule.EveryNWeeks     -> TaskConditionSpec("everyNWeeks", intervalN = n, anchorDate = anchorDate)
    is RecurrenceRule.EveryNMonths    -> TaskConditionSpec("everyNMonths", intervalN = n, anchorDate = anchorDate)
    is RecurrenceRule.NTimesPerPeriod -> TaskConditionSpec("nTimesPerPeriod", occurrenceCount = count, intervalN = periodDays, anchorDate = anchorDate)
}

/** The repeat rule a spec holds (not days of week, which are their own tag). */
fun TaskConditionSpec.toRecurrence(): RecurrenceRule? = when (type) {
    "oneOff"          -> oneOffDate?.let { RecurrenceRule.OneOff(it) }
    "everyNDays"      -> if (intervalN != null && anchorDate != null) RecurrenceRule.EveryNDays(intervalN, anchorDate) else null
    "everyNWeeks"     -> if (intervalN != null && anchorDate != null) RecurrenceRule.EveryNWeeks(intervalN, anchorDate) else null
    "everyNMonths"    -> if (intervalN != null && anchorDate != null) RecurrenceRule.EveryNMonths(intervalN, anchorDate) else null
    "nTimesPerPeriod" -> if (occurrenceCount != null && intervalN != null && anchorDate != null)
        RecurrenceRule.NTimesPerPeriod(occurrenceCount, intervalN, anchorDate) else null
    else -> null
}

// ── Labels ──────────────────────────────────────────────────────────────────

/** What a tag points at, for its icon. */
enum class TagKind { DAYS, REPEAT, TIME, SLEEP, TASK, BLOCK, EVENT, SAME_DAY, NOT_WITH }

/** One tag as shown: its kind, its label, and (in an editor) how removing it changes the tags. */
data class TagView(val kind: TagKind, val label: String, val remove: (ConstraintTags) -> ConstraintTags = { it })

/** Names for what tags point at; null when it no longer exists. */
class TagNames(
    val task: (String) -> String? = { null },
    val block: (String) -> String? = { null },
    val event: (Long) -> String? = { null }
)

private val SHORT_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Weekdays", "Weekends", "Every day", or "Mon Wed Fri". */
fun daysLabel(days: Collection<Int>): String {
    val set = days.toSet()
    return when (set) {
        (1..7).toSet() -> "Every day"
        (1..5).toSet() -> "Weekdays"
        setOf(6, 7)    -> "Weekends"
        else           -> set.sorted().mapNotNull { SHORT_DAYS.getOrNull(it - 1) }.joinToString(" ")
    }
}

/** "Once · today", "Once · Fri 26 Sep", "Every 2 days", "3× every 7 days", … */
fun recurrenceLabel(rule: RecurrenceRule, today: LocalDate = LocalDate.now()): String = when (rule) {
    is RecurrenceRule.OneOff -> {
        val date = runCatching { LocalDate.parse(rule.date) }.getOrNull()
        when (date) {
            null              -> "Once"
            today             -> "Once · today"
            today.plusDays(1) -> "Once · tomorrow"
            else -> "Once · " + date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        }
    }
    is RecurrenceRule.DaysOfWeek      -> daysLabel(rule.days)
    is RecurrenceRule.EveryNDays      -> if (rule.n == 1) "Every day" else "Every ${rule.n} days"
    is RecurrenceRule.EveryNWeeks     -> if (rule.n == 1) "Every week" else "Every ${rule.n} weeks"
    is RecurrenceRule.EveryNMonths    -> if (rule.n == 1) "Every month" else "Every ${rule.n} months"
    is RecurrenceRule.NTimesPerPeriod -> when {
        rule.periodDays == 7 && rule.count == 1 -> "Once a week"
        rule.periodDays == 7                    -> "${rule.count}× a week"
        rule.count == 1                         -> "Once every ${rule.periodDays} days"
        else                                    -> "${rule.count}× every ${rule.periodDays} days"
    }
}

/** An event's name, with its weekday when it isn't today's ("Dentist · Tue"). */
fun eventLabel(title: String, startMillis: Long?, today: LocalDate = LocalDate.now()): String {
    val date = startMillis?.let {
        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    } ?: return title
    return when (date) {
        today             -> title
        today.plusDays(1) -> "$title · tomorrow"
        else              -> "$title · ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }
}

/** Every tag in [tags], in a stable order, each with how to remove it. */
fun ConstraintTags.views(names: TagNames): List<TagView> = buildList {
    fun task(id: String) = if (id == TASK_REF_SLEEP) "sleep" else names.task(id) ?: "deleted task"
    fun kindOf(id: String) = if (id == TASK_REF_SLEEP) TagKind.SLEEP else TagKind.TASK
    fun event(id: Long) = names.event(id) ?: eventTitles[id] ?: "calendar event"
    fun block(id: String) = names.block(id) ?: "deleted block"

    if (days.isNotEmpty()) add(TagView(TagKind.DAYS, daysLabel(days)) { it.copy(days = emptySet()) })
    recurrence?.let { r -> add(TagView(TagKind.REPEAT, recurrenceLabel(r)) { it.copy(recurrence = null) }) }
    afterTaskIds.sortedBy { it != TASK_REF_SLEEP }.forEach { id ->
        add(TagView(kindOf(id), "After ${task(id)}") { it.copy(afterTaskIds = it.afterTaskIds - id) })
    }
    afterBlockIds.forEach { id -> add(TagView(TagKind.BLOCK, "After ${block(id)}") { it.copy(afterBlockIds = it.afterBlockIds - id) }) }
    afterEventIds.forEach { id -> add(TagView(TagKind.EVENT, "After ${event(id)}") { it.copy(afterEventIds = it.afterEventIds - id) }) }
    beforeTaskIds.sortedBy { it != TASK_REF_SLEEP }.forEach { id ->
        add(TagView(kindOf(id), "Before ${task(id)}") { it.copy(beforeTaskIds = it.beforeTaskIds - id) })
    }
    beforeBlockIds.forEach { id -> add(TagView(TagKind.BLOCK, "Before ${block(id)}") { it.copy(beforeBlockIds = it.beforeBlockIds - id) }) }
    beforeEventIds.forEach { id -> add(TagView(TagKind.EVENT, "Before ${event(id)}") { it.copy(beforeEventIds = it.beforeEventIds - id) }) }
    duringEventId?.let { id -> add(TagView(TagKind.EVENT, "During ${event(id)}") { it.copy(duringEventId = null) }) }
    sameDayIds.forEach { id -> add(TagView(TagKind.SAME_DAY, "Same day as ${task(id)}") { it.copy(sameDayIds = it.sameDayIds - id) }) }
    notWithIds.forEach { id -> add(TagView(TagKind.NOT_WITH, "Not with ${task(id)}") { it.copy(notWithIds = it.notWithIds - id) }) }
}

/**
 * Everything a saved item's conditions say, as tags, for showing under it: its time of day
 * first ("Around 12:00 ±1h", "09:00–17:00", "After 09:00"), then its other tags.
 */
fun conditionTagViews(specs: List<TaskConditionSpec>, names: TagNames): List<TagView> = buildList {
    specs.firstOrNull { it.type == "aroundTime" }?.let { a ->
        val flex = a.flexMinutes ?: 60
        val flexLabel = if (flex % 60 == 0) "${flex / 60}h" else "${flex}m"
        add(TagView(TagKind.TIME, "Around ${a.start ?: "12:00"} ±$flexLabel"))
    }
    specs.firstOrNull { it.type == "timeWindow" }?.let { w ->
        val start = w.start?.takeIf { it != "00:00" }
        val end = w.end?.takeIf { it != "23:59" }
        when {
            start != null && end != null -> add(TagView(TagKind.TIME, "$start–$end"))
            start != null                -> add(TagView(TagKind.TIME, "After $start"))
            end != null                  -> add(TagView(TagKind.TIME, "Before $end"))
        }
    }
    addAll(ConstraintTags.fromSpecs(specs).views(names))
}
