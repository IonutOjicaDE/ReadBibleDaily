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
import net.bible.service.db.DatabaseContainer
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook

object CustomReadingPlanChapterStateService {
    private val dao get() = DatabaseContainer.instance.progressDb.progressDao()

    suspend fun reconcileChecklistForConfirmedPlan(
        plan: CustomReadingPlan,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ) {
        val canonicalKeys = resolveCanonicalChapterKeys(plan, treeNodes)
        reconcilePlanChapterStates(plan.id, canonicalKeys)
    }

    /**
     * Rebuilds one plan's checklist from canonical keys while preserving existing read flags for matching chapters.
     *
     * This is intentionally done as a replace strategy (delete + upsert merged rows) so removed chapters are dropped
     * deterministically and resulting rows are always emitted in canonical `(bookId, chapter)` order.
     */
    suspend fun reconcilePlanChapterStates(
        planId: String,
        canonicalChapterKeys: List<Pair<Int, Int>>,
    ) = withContext(Dispatchers.IO) {
        val existingByKey = dao.loadCustomPlanChapterStates(planId)
            .associateBy { it.bookId to it.chapter }

        val now = System.currentTimeMillis()
        val mergedRows = canonicalChapterKeys
            .distinct()
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .map { (bookId, chapter) ->
                val existing = existingByKey[bookId to chapter]
                CustomPlanChapterState(
                    planId = planId,
                    bookId = bookId,
                    chapter = chapter,
                    isRead = existing?.isRead ?: false,
                    updatedAt = if (existing == null) now else existing.updatedAt,
                )
            }

        dao.deleteCustomPlanChapterStates(planId)
        if (mergedRows.isNotEmpty()) {
            dao.upsertCustomPlanChapterStates(mergedRows)
        }
    }

    suspend fun loadPlanChapterStates(planId: String): List<CustomPlanChapterState> = withContext(Dispatchers.IO) {
        dao.loadCustomPlanChapterStates(planId)
    }

    suspend fun setChapterRead(planId: String, bookId: Int, chapter: Int, isRead: Boolean) = withContext(Dispatchers.IO) {
        val existing = dao.loadCustomPlanChapterState(planId, bookId, chapter)
            ?: CustomPlanChapterState(planId = planId, bookId = bookId, chapter = chapter)

        dao.upsertCustomPlanChapterState(
            existing.copy(
                isRead = isRead,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Applies a single read flag to many chapters atomically for one custom plan.
     *
     * Existing rows are preserved and only the read flag/timestamp are updated. Missing rows are created so callers
     * can bulk-mark directly from the tree without requiring a prior full reconcile pass.
     */
    suspend fun setChaptersRead(planId: String, chapters: Collection<Pair<Int, Int>>, isRead: Boolean) = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext
        val existingByKey = dao.loadCustomPlanChapterStates(planId).associateBy { it.bookId to it.chapter }
        val now = System.currentTimeMillis()
        val updates = chapters
            .distinct()
            .map { (bookId, chapter) ->
                val existing = existingByKey[bookId to chapter]
                    ?: CustomPlanChapterState(planId = planId, bookId = bookId, chapter = chapter)
                existing.copy(
                    isRead = isRead,
                    updatedAt = now,
                )
            }
        dao.upsertCustomPlanChapterStates(updates)
    }

    suspend fun countTotalChapters(planId: String): Int = withContext(Dispatchers.IO) {
        dao.countCustomPlanChapters(planId)
    }

    suspend fun countReadChapters(planId: String): Int = withContext(Dispatchers.IO) {
        dao.countReadCustomPlanChapters(planId)
    }

    suspend fun firstUnreadChapter(planId: String): CustomPlanChapterState? = withContext(Dispatchers.IO) {
        dao.getFirstUnreadCustomPlanChapter(planId)
    }

    suspend fun deletePlanChecklist(planId: String) = withContext(Dispatchers.IO) {
        dao.deleteCustomPlanChapterStates(planId)
    }

    private fun resolveCanonicalChapterKeys(
        plan: CustomReadingPlan,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ): List<Pair<Int, Int>> {
        val allNodes = CustomReadingPlanTreeSelection
            .flattenVisible(treeNodes, CustomReadingPlanTreeSelection.validNodeKeys(treeNodes))
            .map { it.node }

        val selectedBooks = allNodes
            .filter { it.type == CustomReadingPlanNodeType.BIBLE_BOOK }
            .filter { CustomReadingPlanTreeSelection.selectionState(it, plan.selection.selectedNodeKeys) == CustomReadingPlanSelectionState.CHECKED }

        val chapterKeys = mutableSetOf<Pair<Int, Int>>()
        for (selectedBookNode in selectedBooks) {
            val moduleInitials = selectedBookNode.key.split(':').getOrNull(1) ?: continue
            val bookName = selectedBookNode.key.split(':').lastOrNull() ?: continue
            val bibleBook = runCatching { BibleBook.valueOf(bookName) }.getOrNull() ?: continue
            val document = SwordDocumentFacade.getDocumentByInitials(moduleInitials) as? AbstractPassageBook ?: continue
            val chapterCount = document.versification.getLastChapter(bibleBook)
            for (chapter in 1..chapterCount) {
                chapterKeys += bibleBook.ordinal to chapter
            }
        }

        return chapterKeys
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    }
}
