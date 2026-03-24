/*
 * Copyright (c) 2026 Sykerö Software / Tuomas Airaksinen and the AndBible contributors.
 */

package net.bible.android.view.activity.readingplan

import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

private const val RESUME_CONTEXT_PARAGRAPHS_ABOVE_VIEWPORT = 2

data class CustomPlanQueueItem(
    val chapter: ChapterIdentity,
    val planId: String,
    val planOrder: Int,
)

data class StartupCustomPlanDecision(
    val shouldEnterMode: Boolean,
    val queue: List<CustomPlanQueueItem>,
)

/**
 * Deterministic queue builder for today's unread custom-plan chapters.
 */
open class CustomReadingPlanQueueBuilder(
    private val progressService: CustomReadingPlanProgressReader = CustomReadingPlanProgressReaderImpl,
    private val dateProvider: () -> LocalDate = { LocalDate.now(Clock.systemDefaultZone()) },
) {
    open fun buildTodayUnreadQueue(
        plans: List<CustomReadingPlan>,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ): List<CustomPlanQueueItem> {
        val day = dateProvider().dayOfWeek
        val dedup = linkedMapOf<ChapterIdentity, CustomPlanQueueItem>()

        plans.forEachIndexed { planOrder, plan ->
            if (!plan.isActive || !plan.isScheduledFor(day)) return@forEachIndexed
            val chapters = resolveSelectedChapters(plan, treeNodes)
            chapters.asSequence()
                .filter { progressService.loadChapterResume(plan.id, it)?.completionPercent?.coerceIn(0f, 1f) ?: 0f < 1f }
                .sortedWith(compareBy<ChapterIdentity>({ it.bookOrdinal }, { it.chapter }, { it.moduleInitials }))
                .forEach { chapter ->
                    dedup.putIfAbsent(chapter, CustomPlanQueueItem(chapter = chapter, planId = plan.id, planOrder = planOrder))
                }
        }
        return dedup.values.toList()
    }

    fun decideStartup(plans: List<CustomReadingPlan>, treeNodes: List<CustomReadingPlanTreeNode>): StartupCustomPlanDecision {
        val queue = buildTodayUnreadQueue(plans, treeNodes)
        return StartupCustomPlanDecision(shouldEnterMode = queue.isNotEmpty(), queue = queue)
    }

    internal fun calculateResumeOrdinal(lastVisibleParagraphOrdinals: List<Int>): Int? {
        if (lastVisibleParagraphOrdinals.isEmpty()) return null
        val index = (lastVisibleParagraphOrdinals.size - 1 - RESUME_CONTEXT_PARAGRAPHS_ABOVE_VIEWPORT).coerceAtLeast(0)
        return lastVisibleParagraphOrdinals[index]
    }

    private fun resolveSelectedChapters(plan: CustomReadingPlan, treeNodes: List<CustomReadingPlanTreeNode>): List<ChapterIdentity> {
        val allNodes = CustomReadingPlanTreeSelection
            .flattenVisible(treeNodes, CustomReadingPlanTreeSelection.validNodeKeys(treeNodes))
            .map { it.node }

        val selectedBooks = allNodes
            .filter { it.type == CustomReadingPlanNodeType.BIBLE_BOOK }
            .filter { CustomReadingPlanTreeSelection.selectionState(it, plan.selection.selectedNodeKeys) == CustomReadingPlanSelectionState.CHECKED }

        return selectedBooks.flatMap { selectedBookNode ->
            val moduleInitials = selectedBookNode.key.split(':').getOrNull(1) ?: return@flatMap emptyList()
            val bookName = selectedBookNode.key.split(':').lastOrNull() ?: return@flatMap emptyList()
            val bibleBook = runCatching { BibleBook.valueOf(bookName) }.getOrNull() ?: return@flatMap emptyList()
            val document = net.bible.service.sword.SwordDocumentFacade.getDocumentByInitials(moduleInitials) as? AbstractPassageBook ?: return@flatMap emptyList()
            val chapters = document.versification.getLastChapter(bibleBook)
            (1..chapters).map { ChapterIdentity(moduleInitials = moduleInitials, bookOrdinal = bibleBook.ordinal, chapter = it) }
        }
    }
}

interface CustomReadingPlanProgressReader {
    fun loadChapterResume(planId: String, chapter: ChapterIdentity): net.bible.android.database.progress.ReadingPlanChapterProgress?
}

object CustomReadingPlanProgressReaderImpl : CustomReadingPlanProgressReader {
    override fun loadChapterResume(planId: String, chapter: ChapterIdentity) =
        CustomReadingPlanProgressService.loadChapterResume(planId, chapter)
}

private fun CustomReadingPlan.isScheduledFor(dayOfWeek: DayOfWeek): Boolean {
    val mapped = when (dayOfWeek) {
        DayOfWeek.MONDAY -> ReadingWeekDay.MONDAY
        DayOfWeek.TUESDAY -> ReadingWeekDay.TUESDAY
        DayOfWeek.WEDNESDAY -> ReadingWeekDay.WEDNESDAY
        DayOfWeek.THURSDAY -> ReadingWeekDay.THURSDAY
        DayOfWeek.FRIDAY -> ReadingWeekDay.FRIDAY
        DayOfWeek.SATURDAY -> ReadingWeekDay.SATURDAY
        DayOfWeek.SUNDAY -> ReadingWeekDay.SUNDAY
    }
    return mapped in selectedDays
}
