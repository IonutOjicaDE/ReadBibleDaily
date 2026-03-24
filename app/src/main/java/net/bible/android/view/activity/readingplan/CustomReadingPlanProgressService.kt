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

import net.bible.android.control.event.ABEventBus
import net.bible.android.database.progress.ChapterReadCounter
import net.bible.android.database.progress.ReadingPlanChapterProgress
import net.bible.android.database.progress.ReadingSource
import net.bible.android.database.progress.WordCountIndexRecord
import net.bible.service.db.DatabaseContainer
import net.bible.service.sword.SwordContentFacade
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

private const val FALLBACK_WORDS_PER_VERSE = 20

/** Posted when a word index for a module has completed and consumers can refresh ETA labels. */
class CustomReadingPlanWordIndexUpdatedEvent(
    val moduleInitials: String,
)

data class PlanWordEstimate(
    val totalWords: Int,
    val indexReady: Boolean,
)

data class ChapterIdentity(
    val bookOrdinal: Int,
    val chapter: Int,
)

data class ChapterResumePosition(
    val lastReadOrdinal: Int?,
    val chapterAnchor: String?,
)

data class PlanScopeProgress(
    val completedChapters: Int,
    val totalChapters: Int,
    val completionPercent: Float,
)

/**
 * Central service for custom reading plan metrics:
 * - chapter-based word index + fast ETA aggregation
 * - canonical chapter read counters (module-independent)
 * - per-plan chapter resume/progress state
 */
object CustomReadingPlanProgressService {
    private val dao get() = DatabaseContainer.instance.progressDb.progressDao()
    private val executor = Executors.newSingleThreadExecutor()
    private val indexingModules = ConcurrentHashMap.newKeySet<String>()
    private val cachedWordIndex = ConcurrentHashMap<String, Map<ChapterIdentity, Int>>()

    fun estimatePlanWords(plan: CustomReadingPlan, treeNodes: List<CustomReadingPlanTreeNode>): PlanWordEstimate {
        val planScope = resolveSelectedChapters(plan, treeNodes)
        if (planScope.isEmpty()) return PlanWordEstimate(0, true)

        val groupedByModule = planScope.groupBy { it.first.initials }
        var totalWords = 0
        var fullyIndexed = true

        for ((moduleInitials, chapters) in groupedByModule) {
            val bible = SwordDocumentFacade.getDocumentByInitials(moduleInitials) ?: continue
            val versification = (bible as? AbstractPassageBook)?.versification ?: continue
            val cacheKey = cacheKey(moduleInitials, versification.name)
            val index = cachedWordIndex[cacheKey] ?: loadWordIndex(moduleInitials, versification.name).also {
                cachedWordIndex[cacheKey] = it
            }
            if (index.isEmpty()) {
                fullyIndexed = false
                ensureWordIndexInBackground(bible)
            }

            for ((_, chapterIdentity) in chapters) {
                val indexedWords = index[chapterIdentity]
                if (indexedWords != null) {
                    totalWords += indexedWords
                } else {
                    fullyIndexed = false
                    val bibleBook = versification.bookIterator.asSequence().firstOrNull { it.ordinal == chapterIdentity.bookOrdinal }
                    if (bibleBook != null) {
                        totalWords += fallbackChapterWords(versification, bibleBook, chapterIdentity.chapter)
                    }
                }
            }
        }

        return PlanWordEstimate(totalWords = totalWords, indexReady = fullyIndexed)
    }

    fun incrementChapterReadCount(bookOrdinal: Int, chapter: Int, source: ReadingSource = ReadingSource.MANUAL) {
        val existing = dao.getChapterReadCounter(bookOrdinal, chapter)
        val now = System.currentTimeMillis()
        val updated = if (existing == null) {
            ChapterReadCounter(
                bookOrdinal = bookOrdinal,
                chapter = chapter,
                readCount = 1,
                firstReadAt = now,
                lastReadAt = now,
                source = source,
            )
        } else {
            existing.copy(
                readCount = existing.readCount + 1,
                lastReadAt = now,
                source = source,
            )
        }
        dao.insertChapterReadCounter(updated)
    }

    fun getChapterReadCount(bookOrdinal: Int, chapter: Int): Int =
        dao.getChapterReadCounter(bookOrdinal, chapter)?.readCount ?: 0

    fun getBookReadCount(bookOrdinal: Int): Int = dao.getBookReadCount(bookOrdinal)

    fun getSectionReadCount(bookOrdinals: List<Int>): Int =
        if (bookOrdinals.isEmpty()) 0 else dao.getSectionReadCount(bookOrdinals)

    fun saveChapterResume(
        planId: String,
        chapter: ChapterIdentity,
        completion: Float,
        position: ChapterResumePosition,
    ) {
        val normalizedCompletion = completion.coerceIn(0f, 1f)
        val current = dao.loadReadingPlanChapterProgress(planId, chapter.bookOrdinal, chapter.chapter)
        dao.upsertReadingPlanChapterProgress(
            (current ?: ReadingPlanChapterProgress(
                planId = planId,
                bookOrdinal = chapter.bookOrdinal,
                chapter = chapter.chapter,
                completionPercent = normalizedCompletion,
                lastReadOrdinal = position.lastReadOrdinal,
                chapterAnchor = position.chapterAnchor,
            )).copy(
                completionPercent = normalizedCompletion,
                lastReadOrdinal = position.lastReadOrdinal,
                chapterAnchor = position.chapterAnchor,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun loadChapterResume(planId: String, chapter: ChapterIdentity): ReadingPlanChapterProgress? =
        dao.loadReadingPlanChapterProgress(planId, chapter.bookOrdinal, chapter.chapter)

    fun getPlanScopeProgress(planId: String, plan: CustomReadingPlan, treeNodes: List<CustomReadingPlanTreeNode>): PlanScopeProgress {
        val scope = resolveSelectedChapters(plan, treeNodes).map { it.second }.toSet()
        if (scope.isEmpty()) return PlanScopeProgress(0, 0, 0f)

        val progressRows = dao.loadReadingPlanChapterProgressForPlan(planId)
            .associateBy { ChapterIdentity(it.bookOrdinal, it.chapter) }

        val completed = scope.count { (progressRows[it]?.completionPercent ?: 0f) >= 1f }

        val weighted = buildWeightedProgress(scope, progressRows)

        return PlanScopeProgress(
            completedChapters = completed,
            totalChapters = scope.size,
            completionPercent = weighted,
        )
    }

    fun getPlanCompletion(planId: String, plan: CustomReadingPlan, treeNodes: List<CustomReadingPlanTreeNode>): Float =
        getPlanScopeProgress(planId, plan, treeNodes).completionPercent

    private fun resolveSelectedChapters(
        plan: CustomReadingPlan,
        treeNodes: List<CustomReadingPlanTreeNode>,
    ): List<Pair<Book, ChapterIdentity>> {
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
            val document = SwordDocumentFacade.getDocumentByInitials(moduleInitials) as? AbstractPassageBook ?: return@flatMap emptyList()
            val chapters = document.versification.getLastChapter(bibleBook)
            (1..chapters).map {
                document to ChapterIdentity(bookOrdinal = bibleBook.ordinal, chapter = it)
            }
        }
    }

    private fun buildWeightedProgress(
        scope: Set<ChapterIdentity>,
        progressRows: Map<ChapterIdentity, ReadingPlanChapterProgress>,
    ): Float {
        val groupedByBook = scope.groupBy { it.bookOrdinal }
        var weightedDone = 0.0
        var weightedTotal = 0.0

        for ((bookOrdinal, chapters) in groupedByBook) {
            val weights = chapters.associateWith { chapter ->
                getChapterWeight(bookOrdinal, chapter.chapter)
            }
            for ((chapter, weight) in weights) {
                val completion = progressRows[chapter]?.completionPercent?.coerceIn(0f, 1f) ?: 0f
                weightedDone += completion * weight
                weightedTotal += weight
            }
        }

        if (weightedTotal <= 0.0) return 0f
        return (weightedDone / weightedTotal).toFloat().coerceIn(0f, 1f)
    }

    private fun getChapterWeight(bookOrdinal: Int, chapter: Int): Int {
        val indexed = cachedWordIndex.values.asSequence()
            .mapNotNull { it[ChapterIdentity(bookOrdinal, chapter)] }
            .firstOrNull()
        return max(indexed ?: 0, 1)
    }

    private fun ensureWordIndexInBackground(book: Book) {
        val moduleInitials = book.initials
        if (!indexingModules.add(moduleInitials)) return

        executor.execute {
            try {
                val passageBook = book as? AbstractPassageBook ?: return@execute
                val versification = passageBook.versification
                val version = book.bookMetaData.getProperty("Version") ?: ""
                val existingVersion = dao.getWordIndexVersion(moduleInitials)
                if (existingVersion != null && existingVersion != version) {
                    dao.deleteWordIndexByModule(moduleInitials)
                }

                val existing = dao.getWordIndex(moduleInitials, versification.name)
                if (existing.isNotEmpty() && existingVersion == version) {
                    cachedWordIndex[cacheKey(moduleInitials, versification.name)] = existing.associate { ChapterIdentity(it.bookOrdinal, it.chapter) to it.wordCount }
                    return@execute
                }

                val records = mutableListOf<WordCountIndexRecord>()
                for (b in versification.bookIterator) {
                    val chapterCount = versification.getLastChapter(b)
                    if (chapterCount <= 0) continue
                    for (chapter in 1..chapterCount) {
                        val words = chapterWordCount(book, versification, b, chapter)
                        records += WordCountIndexRecord(
                            moduleInitials = moduleInitials,
                            moduleVersion = version,
                            versification = versification.name,
                            bookOrdinal = b.ordinal,
                            chapter = chapter,
                            wordCount = words,
                        )
                    }
                }
                if (records.isNotEmpty()) {
                    dao.upsertWordIndex(records)
                    cachedWordIndex[cacheKey(moduleInitials, versification.name)] = records.associate { ChapterIdentity(it.bookOrdinal, it.chapter) to it.wordCount }
                }
                ABEventBus.post(CustomReadingPlanWordIndexUpdatedEvent(moduleInitials))
            } finally {
                indexingModules.remove(moduleInitials)
            }
        }
    }

    private fun chapterWordCount(book: Book, versification: org.crosswire.jsword.versification.Versification, bibleBook: BibleBook, chapter: Int): Int {
        val lastVerse = versification.getLastVerse(bibleBook, chapter)
        if (lastVerse <= 0) return 0
        val chapterRange = VerseRange(
            versification,
            Verse(versification, bibleBook, chapter, 1),
            Verse(versification, bibleBook, chapter, lastVerse),
        )
        val text = runCatching { SwordContentFacade.getCanonicalText(book, chapterRange) }.getOrDefault("")
        return text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    private fun fallbackChapterWords(
        versification: org.crosswire.jsword.versification.Versification,
        bibleBook: BibleBook,
        chapter: Int,
    ): Int {
        val verses = versification.getLastVerse(bibleBook, chapter)
        return fallbackWordsForVerseCount(verses)
    }

    internal fun fallbackWordsForVerseCount(verseCount: Int): Int =
        max(verseCount, 1) * FALLBACK_WORDS_PER_VERSE

    internal fun weightedProgress(chapterWeights: Map<ChapterIdentity, Int>, completionByChapter: Map<ChapterIdentity, Float>): Float {
        val total = chapterWeights.values.sum().toFloat()
        if (total <= 0f) return 0f
        val done = chapterWeights.entries.sumOf { (chapter, weight) ->
            (completionByChapter[chapter] ?: 0f).coerceIn(0f, 1f) * weight
        }
        return (done / total).coerceIn(0f, 1f)
    }

    private fun loadWordIndex(moduleInitials: String, versification: String): Map<ChapterIdentity, Int> =
        dao.getWordIndex(moduleInitials, versification)
            .associate { ChapterIdentity(it.bookOrdinal, it.chapter) to it.wordCount }

    private fun cacheKey(moduleInitials: String, versification: String): String = "$moduleInitials:$versification"
}
