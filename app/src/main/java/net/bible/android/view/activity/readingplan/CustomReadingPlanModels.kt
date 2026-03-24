/*
 * Copyright (c) 2026 Sykerö Software / Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.android.view.activity.readingplan

import android.content.Context
import net.bible.android.activity.R
import net.bible.android.database.progress.CustomReadingPlanRecord
import net.bible.service.db.DatabaseContainer
import java.io.Serializable
import java.text.DateFormat
import java.util.Calendar
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

const val APPROX_WORDS_PER_PAGE = 200
const val FUTURE_READING_SPEED_SETTING_KEY = "reading_speed_words_per_minute"
private const val DEFAULT_READING_SPEED_WORDS_PER_MINUTE = 180

enum class ReadingWeekDay(
    val stringResId: Int,
) : Serializable {
    MONDAY(R.string.custom_reading_plan_day_monday),
    TUESDAY(R.string.custom_reading_plan_day_tuesday),
    WEDNESDAY(R.string.custom_reading_plan_day_wednesday),
    THURSDAY(R.string.custom_reading_plan_day_thursday),
    FRIDAY(R.string.custom_reading_plan_day_friday),
    SATURDAY(R.string.custom_reading_plan_day_saturday),
    SUNDAY(R.string.custom_reading_plan_day_sunday),
}

data class CustomReadingPlan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val selectionSummary: String,
    val selection: CustomReadingPlanSelection = CustomReadingPlanSelection(),
    val minutesPerSession: Int = 10,
    val periodInDays: Int = 1,
    val selectedDays: LinkedHashSet<ReadingWeekDay> = linkedSetOf(
        ReadingWeekDay.MONDAY,
        ReadingWeekDay.TUESDAY,
        ReadingWeekDay.WEDNESDAY,
        ReadingWeekDay.THURSDAY,
        ReadingWeekDay.FRIDAY,
        ReadingWeekDay.SATURDAY,
        ReadingWeekDay.SUNDAY,
    ),
    val isActive: Boolean = true,
) : Serializable {
    fun listSummary(context: Context): String {
        val cadence = CustomReadingPlanUiFormatter.cadenceText(context, periodInDays, lowercase = true)
        val selectedDaySummary = CustomReadingPlanUiFormatter.selectedDaysSummary(context, selectedDays)
        return context.getString(
            R.string.custom_reading_plan_list_summary,
            minutesPerSession,
            cadence,
            selectedDaySummary,
        )
    }
}

object CustomReadingPlanInMemoryRepository {
    private val plans = mutableListOf<CustomReadingPlan>()
    private var isInitialized = false
    private val dao get() = DatabaseContainer.instance.progressDb.progressDao()

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val persisted = dao.loadCustomReadingPlans().map { it.toModel() }
        if (persisted.isNotEmpty()) {
            plans += persisted
            return
        }

        val newTestamentSeed = seededSelection(context, "new_testament")
        val oldTestamentSeed = seededSelection(context, "old_testament")

        plans += CustomReadingPlan(
            title = context.getString(R.string.custom_reading_plan_new_testament),
            selection = newTestamentSeed?.first ?: CustomReadingPlanSelection(),
            selectionSummary = newTestamentSeed?.second ?: defaultSelectionSummary(context),
            selectedDays = linkedSetOf(
                ReadingWeekDay.MONDAY,
                ReadingWeekDay.TUESDAY,
                ReadingWeekDay.WEDNESDAY,
                ReadingWeekDay.THURSDAY,
                ReadingWeekDay.FRIDAY,
                ReadingWeekDay.SATURDAY,
            ),
        )
        plans += CustomReadingPlan(
            title = context.getString(R.string.custom_reading_plan_old_testament),
            selection = oldTestamentSeed?.first ?: CustomReadingPlanSelection(),
            selectionSummary = oldTestamentSeed?.second ?: defaultSelectionSummary(context),
            minutesPerSession = 15,
            periodInDays = 2,
            selectedDays = linkedSetOf(
                ReadingWeekDay.MONDAY,
                ReadingWeekDay.WEDNESDAY,
                ReadingWeekDay.FRIDAY,
                ReadingWeekDay.SUNDAY,
            ),
        )
        persistAll()
    }

    fun getPlans(): List<CustomReadingPlan> = plans.toList()

    fun getPlan(planId: String): CustomReadingPlan? = plans.firstOrNull { it.id == planId }

    fun upsert(plan: CustomReadingPlan) {
        val index = plans.indexOfFirst { it.id == plan.id }
        if (index >= 0) {
            plans[index] = plan
            dao.upsertCustomReadingPlan(plan.toRecord(index))
        } else {
            plans += plan
            dao.upsertCustomReadingPlan(plan.toRecord(plans.lastIndex))
        }
    }

    fun updateActive(planId: String, isActive: Boolean) {
        val index = plans.indexOfFirst { it.id == planId }
        if (index >= 0) {
            plans[index] = plans[index].copy(isActive = isActive)
            dao.upsertCustomReadingPlan(plans[index].toRecord(index))
        }
    }

    fun delete(planId: String) {
        val removed = plans.removeAll { it.id == planId }
        if (removed) {
            dao.deleteCustomReadingPlan(planId)
            persistAll()
        }
    }

    internal fun resetForTesting() {
        plans.clear()
        isInitialized = false
        runCatching {
            dao.loadCustomReadingPlans().forEach { dao.deleteCustomReadingPlan(it.id) }
        }
    }

    internal fun clearCacheForTesting() {
        plans.clear()
        isInitialized = false
    }

    private fun seededSelection(context: Context, testamentKey: String): Pair<CustomReadingPlanSelection, String>? {
        val treeNodes = CustomReadingPlanTreeFactory.build(context)
        val firstBibleNode = treeNodes.firstOrNull { it.type == CustomReadingPlanNodeType.BIBLE_MODULE } ?: return null
        val testamentNode = firstBibleNode.children.firstOrNull { it.key.endsWith(":$testamentKey") } ?: return null
        val selectedKeys = CustomReadingPlanTreeSelection.setSelected(testamentNode, emptySet(), true)
        val selection = CustomReadingPlanSelection(selectedKeys)
        val summary = CustomReadingPlanSelectionSummaryFormatter.format(context, selection, treeNodes)
        return selection to summary
    }

    private fun persistAll() {
        plans.forEachIndexed { index, plan ->
            dao.upsertCustomReadingPlan(plan.toRecord(index))
        }
    }

    private fun CustomReadingPlan.toRecord(positionInList: Int): CustomReadingPlanRecord = CustomReadingPlanRecord(
        id = id,
        title = title,
        selectionSummary = selectionSummary,
        selectionNodeKeys = selection.selectedNodeKeys.joinToString(","),
        minutesPerSession = minutesPerSession,
        periodInDays = periodInDays,
        selectedDays = ReadingWeekDay.entries.filter { it in selectedDays }.joinToString(",") { it.name },
        isActive = isActive,
        positionInList = positionInList,
    )

    private fun CustomReadingPlanRecord.toModel(): CustomReadingPlan {
        val selectedNodeKeys = selectionNodeKeys.split(',').mapNotNull { it.takeIf(String::isNotBlank) }.toSet()
        val selectedDaysSet = selectedDays.split(',').mapNotNull {
            runCatching { ReadingWeekDay.valueOf(it) }.getOrNull()
        }.toCollection(linkedSetOf())
        val normalizedDays = if (selectedDaysSet.isEmpty()) linkedSetOf<ReadingWeekDay>() else selectedDaysSet
        return CustomReadingPlan(
            id = id,
            title = title,
            selectionSummary = selectionSummary,
            selection = CustomReadingPlanSelection(selectedNodeKeys),
            minutesPerSession = minutesPerSession,
            periodInDays = periodInDays,
            selectedDays = normalizedDays,
            isActive = isActive,
        )
    }
}

fun defaultSelectionSummary(context: Context): String =
    context.getString(R.string.custom_reading_plan_default_selection_summary)

object CustomReadingPlanUiFormatter {
    fun estimatePages(minutesPerSession: Int): Int {
        val wordsRead = minutesPerSession * DEFAULT_READING_SPEED_WORDS_PER_MINUTE
        return ceil(wordsRead / APPROX_WORDS_PER_PAGE.toDouble()).toInt()
    }

    fun periodLabel(context: Context, periodInDays: Int): String = context.resources.getQuantityString(
        R.plurals.custom_reading_plan_period_every_x_days,
        periodInDays,
        periodInDays,
    )

    fun cadenceText(context: Context, periodInDays: Int, lowercase: Boolean = false): String {
        val resId = when (periodInDays) {
            1 -> R.string.custom_reading_plan_cadence_daily
            2 -> R.string.custom_reading_plan_cadence_every_two_days
            3 -> R.string.custom_reading_plan_cadence_every_three_days
            7 -> R.string.custom_reading_plan_cadence_weekly
            else -> R.string.custom_reading_plan_cadence_every_x_days
        }
        val text = if (resId == R.string.custom_reading_plan_cadence_every_x_days) {
            context.getString(resId, periodInDays)
        } else {
            context.getString(resId)
        }
        return if (lowercase) text.lowercase() else text
    }

    fun selectedDaysSummary(context: Context, selectedDays: Set<ReadingWeekDay>): String {
        if (selectedDays.isEmpty()) {
            return context.getString(R.string.custom_reading_plan_days_none)
        }
        if (selectedDays.size == ReadingWeekDay.entries.size) {
            return context.getString(R.string.custom_reading_plan_days_all)
        }

        return ReadingWeekDay.entries
            .filter { it in selectedDays }
            .joinToString(separator = ", ") { context.getString(it.stringResId) }
    }

    fun completionDurationText(context: Context, sessionCount: Int): String {
        if (sessionCount > 32) {
            val months = sessionCount / 30
            val days = sessionCount % 30
            return when {
                days == 0 -> context.resources.getQuantityString(
                    R.plurals.custom_reading_plan_duration_months,
                    months,
                    months,
                )
                else -> context.getString(R.string.custom_reading_plan_duration_months_days, months, days)
            }
        }

        return context.resources.getQuantityString(
            R.plurals.custom_reading_plan_duration_days,
            sessionCount,
            sessionCount,
        )
    }

    fun buildAdditionalInfo(context: Context, plan: CustomReadingPlan): Pair<String, String> {
        if (plan.selectedDays.isEmpty()) {
            return context.getString(R.string.custom_reading_plan_info_select_weekday) to
                context.getString(R.string.custom_reading_plan_info_select_weekday_secondary, plan.title)
        }

        val readingDaysCount = plan.selectedDays.size
        val treeNodes = CustomReadingPlanTreeFactory.build(context)
        val wordEstimate = CustomReadingPlanProgressService.estimatePlanWords(plan, treeNodes)
        val totalWords = wordEstimate.totalWords.coerceAtLeast(plan.minutesPerSession * DEFAULT_READING_SPEED_WORDS_PER_MINUTE)
        val sessionsNeeded = ceil(totalWords.toDouble() / (plan.minutesPerSession * DEFAULT_READING_SPEED_WORDS_PER_MINUTE)).toInt()
        val approximateCalendarDays = ((sessionsNeeded * plan.periodInDays) / (readingDaysCount / 7.0)).roundToInt().coerceAtLeast(1)
        val completionDuration = completionDurationText(context, approximateCalendarDays)
        val cadence = cadenceText(context, plan.periodInDays, lowercase = true)
        val progress = (CustomReadingPlanProgressService.getPlanCompletion(plan.id, plan, treeNodes) * 100).roundToInt()
            .coerceIn(0, 100)
        val finishDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, approximateCalendarDays)
        }.time
        val formattedDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(finishDate)

        val first = context.getString(
            R.string.custom_reading_plan_info_completion,
            completionDuration,
            plan.minutesPerSession,
            cadence,
        )
        val second = context.getString(
            R.string.custom_reading_plan_info_progress,
            plan.title,
            progress,
            formattedDate,
        )
        return first to second
    }
}
