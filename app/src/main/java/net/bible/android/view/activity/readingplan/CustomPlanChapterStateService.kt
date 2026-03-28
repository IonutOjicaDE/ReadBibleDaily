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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bible.android.database.progress.CustomPlanChapterState
import net.bible.android.database.progress.PlanChapterReadSummary
import net.bible.service.db.DatabaseContainer
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook

/** Canonical chapter identity for custom reading plans. */
data class CanonicalChapterKey(
    val bookOrdinal: Int,
    val chapter: Int,
)

/** Persisted/read-state projection for one canonical chapter key. */
data class PlanChapterState(
    val key: CanonicalChapterKey,
    val isRead: Boolean,
)

data class PlanProgressSummary(
    val totalChapters: Int,
    val readChapters: Int,
) {
    val unreadChapters: Int = (totalChapters - readChapters).coerceAtLeast(0)
}

/**
 * Handles canonical chapter checklist persistence for Custom Reading Plans.
 */
object CustomPlanChapterStateService {
    private val dao get() = DatabaseContainer.instance.progressDb.progressDao()

    fun resolveCanonicalChapterKeys(
        plan: CustomReadingPlan,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ): List<CanonicalChapterKey> {
        val allNodes = CustomReadingPlanTreeSelection
            .flattenVisible(treeNodes, CustomReadingPlanTreeSelection.validNodeKeys(treeNodes))
            .map { it.node }

        val resolved = allNodes
            .asSequence()
            .filter { it.type == CustomReadingPlanNodeType.BIBLE_BOOK }
            .filter { CustomReadingPlanTreeSelection.selectionState(it, plan.selection.selectedNodeKeys) == CustomReadingPlanSelectionState.CHECKED }
            .flatMap { selectedBookNode ->
                val moduleInitials = selectedBookNode.key.split(':').getOrNull(1) ?: return@flatMap emptySequence()
                val bookName = selectedBookNode.key.split(':').lastOrNull() ?: return@flatMap emptySequence()
                val bibleBook = runCatching { BibleBook.valueOf(bookName) }.getOrNull() ?: return@flatMap emptySequence()
                val document = SwordDocumentFacade.getDocumentByInitials(moduleInitials) as? AbstractPassageBook ?: return@flatMap emptySequence()
                val chapterCount = document.versification.getLastChapter(bibleBook)
                (1..chapterCount).asSequence().map { chapter ->
                    CanonicalChapterKey(bookOrdinal = bibleBook.ordinal, chapter = chapter)
                }
            }
            .toList()

        return canonicalizeKeys(resolved)
    }

    /**
     * Reconciles persisted states only at explicit plan confirmation time.
     *
     * Result guarantees:
     * - persisted rows match the currently confirmed canonical chapter set exactly
     * - existing chapter read flags are preserved whenever keys survive an edit
     * - new keys default to unread
     * - removed keys are dropped only by this confirmation-time reconciliation
     */
    suspend fun reconcilePlanChapterStates(
        planId: String,
        confirmedCanonicalKeys: List<CanonicalChapterKey>,
    ): List<PlanChapterState> = withContext(Dispatchers.IO) {
        val canonicalKeys = canonicalizeKeys(confirmedCanonicalKeys)
        val existing = dao.loadCustomPlanChapterStates(planId).associateBy {
            CanonicalChapterKey(bookOrdinal = it.bookOrdinal, chapter = it.chapter)
        }
        val now = System.currentTimeMillis()
        val reconciled = canonicalKeys.map { key ->
            CustomPlanChapterState(
                planId = planId,
                bookOrdinal = key.bookOrdinal,
                chapter = key.chapter,
                isRead = existing[key]?.isRead ?: false,
                updatedAt = now,
            )
        }

        dao.replaceCustomPlanChapterStates(planId, reconciled)

        reconciled.map { PlanChapterState(CanonicalChapterKey(it.bookOrdinal, it.chapter), it.isRead) }
    }

    suspend fun reconcileFromConfirmedSelection(
        plan: CustomReadingPlan,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ): List<PlanChapterState> = reconcilePlanChapterStates(
        planId = plan.id,
        confirmedCanonicalKeys = resolveCanonicalChapterKeys(plan, treeNodes),
    )

    suspend fun markChapterRead(
        planId: String,
        key: CanonicalChapterKey,
        isRead: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        dao.updateCustomPlanChapterReadAndResetIfNeeded(
            planId = planId,
            bookOrdinal = key.bookOrdinal,
            chapter = key.chapter,
            isRead = isRead,
        )
    }

    suspend fun markBookRead(
        planId: String,
        bookOrdinal: Int,
        isRead: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        dao.updateCustomPlanBookReadAndResetIfNeeded(
            planId = planId,
            bookOrdinal = bookOrdinal,
            isRead = isRead,
        )
    }

    suspend fun toggleChapterRead(
        planId: String,
        key: CanonicalChapterKey,
    ): Boolean = withContext(Dispatchers.IO) {
        dao.toggleCustomPlanChapterReadAndResetIfNeeded(
            planId = planId,
            bookOrdinal = key.bookOrdinal,
            chapter = key.chapter,
        )
    }

    suspend fun getPlanProgressSummary(planId: String): PlanProgressSummary = withContext(Dispatchers.IO) {
        dao.getCustomPlanChapterReadSummary(planId).toPlanProgressSummary()
    }

    suspend fun loadPlanChapterStates(planId: String): List<PlanChapterState> = withContext(Dispatchers.IO) {
        dao.loadCustomPlanChapterStates(planId).map {
            PlanChapterState(
                key = CanonicalChapterKey(it.bookOrdinal, it.chapter),
                isRead = it.isRead,
            )
        }
    }

    suspend fun getFirstUnreadChapter(planId: String): CanonicalChapterKey? = withContext(Dispatchers.IO) {
        dao.getFirstUnreadCustomPlanChapterState(planId)?.let {
            CanonicalChapterKey(bookOrdinal = it.bookOrdinal, chapter = it.chapter)
        }
    }

    internal fun canonicalizeKeys(keys: List<CanonicalChapterKey>): List<CanonicalChapterKey> = keys
        .distinct()
        .sortedWith(compareBy({ it.bookOrdinal }, { it.chapter }))

    private fun PlanChapterReadSummary.toPlanProgressSummary(): PlanProgressSummary =
        PlanProgressSummary(totalChapters = totalChapters, readChapters = readChapters)
}
