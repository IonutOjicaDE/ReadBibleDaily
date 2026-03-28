/*
 * Copyright (c) 2024 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
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

package net.bible.android.database.progress

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import net.bible.android.database.IdType

data class DailyReadingCount(
    val dayTimestamp: Long,
    val count: Int,
)

data class ReadCountAggregate(
    val readCount: Int?,
)

data class ChapterReadCountResult(
    val readCount: Int,
)

data class PlanChapterReadSummary(
    val totalChapters: Int,
    val readChapters: Int,
)

@Dao
interface ProgressDao {
    // Memorization queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMemorizedVerse(verse: MemorizedVerse)

    @Query("DELETE FROM MemorizedVerse WHERE kjvOrdinal = :kjvOrdinal")
    fun deleteMemorizedVerse(kjvOrdinal: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM MemorizedVerse WHERE kjvOrdinal = :kjvOrdinal)")
    fun isVerseMemorized(kjvOrdinal: Int): Boolean

    @Query("SELECT COUNT(*) FROM MemorizedVerse WHERE kjvOrdinal >= :startOrdinal AND kjvOrdinal <= :endOrdinal")
    fun countMemorizedVersesInRange(startOrdinal: Int, endOrdinal: Int): Int

    @Query("SELECT * FROM MemorizedVerse ORDER BY memorizedAt DESC")
    fun allMemorizedVerses(): List<MemorizedVerse>

    @Query("SELECT COUNT(*) FROM MemorizedVerse")
    fun countTotalMemorizedVerses(): Int

    @Query("SELECT kjvOrdinal FROM MemorizedVerse WHERE kjvOrdinal >= :startOrdinal AND kjvOrdinal <= :endOrdinal ORDER BY kjvOrdinal")
    fun memorizedOrdinalsInRange(startOrdinal: Int, endOrdinal: Int): List<Int>

    @Query("DELETE FROM MemorizedVerse WHERE kjvOrdinal >= :startOrdinal AND kjvOrdinal <= :endOrdinal")
    fun deleteMemorizedVersesInRange(startOrdinal: Int, endOrdinal: Int)

    @Query("SELECT (memorizedAt / 86400000) * 86400000 AS dayTimestamp, COUNT(*) AS count " +
        "FROM MemorizedVerse " +
        "WHERE memorizedAt >= :startMs AND memorizedAt <= :endMs " +
        "GROUP BY memorizedAt / 86400000 " +
        "ORDER BY dayTimestamp")
    fun getMemorizationCalendar(startMs: Long, endMs: Long): List<DailyReadingCount>

    // Memorization target queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMemorizationTarget(target: MemorizationTarget)

    @Query("DELETE FROM MemorizationTarget WHERE id = :id")
    fun deleteMemorizationTarget(id: IdType)

    @Query("SELECT * FROM MemorizationTarget WHERE kjvOrdinalStart = :startOrdinal AND kjvOrdinalEnd = :endOrdinal")
    fun findMemorizationTarget(startOrdinal: Int, endOrdinal: Int): MemorizationTarget?

    @Query("SELECT * FROM MemorizationTarget ORDER BY createdAt DESC")
    fun allMemorizationTargets(): List<MemorizationTarget>

    @Query("SELECT COUNT(*) FROM MemorizationTarget")
    fun countMemorizationTargets(): Int

    @Query("SELECT * FROM MemorizationTarget WHERE kjvOrdinalStart <= :endOrdinal AND kjvOrdinalEnd >= :startOrdinal")
    fun memorizationTargetsOverlapping(startOrdinal: Int, endOrdinal: Int): List<MemorizationTarget>

    // Reading queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChapterReadingRecord(record: ChapterReadingRecord)

    @Query("SELECT EXISTS(SELECT 1 FROM ChapterReadingRecord WHERE kjvBookOrdinal = :kjvBookOrdinal AND chapter = :chapter AND cycle = :cycle)")
    fun isChapterRead(kjvBookOrdinal: Int, chapter: Int, cycle: Int): Boolean

    @Query("DELETE FROM ChapterReadingRecord WHERE kjvBookOrdinal = :kjvBookOrdinal AND chapter = :chapter AND cycle = :cycle")
    fun deleteChapterReadingRecord(kjvBookOrdinal: Int, chapter: Int, cycle: Int)

    @Query("SELECT COUNT(*) FROM ChapterReadingRecord WHERE kjvBookOrdinal = :kjvBookOrdinal AND cycle = :cycle")
    fun countReadChaptersForBook(kjvBookOrdinal: Int, cycle: Int): Int

    @Query("SELECT COALESCE(MAX(cycle), 1) FROM ChapterReadingRecord")
    fun getLatestCycle(): Int

    @Query("SELECT * FROM ChapterReadingRecord WHERE cycle = :cycle ORDER BY readAt DESC")
    fun getRecordsForCycle(cycle: Int): List<ChapterReadingRecord>

    @Query("SELECT * FROM ChapterReadingRecord ORDER BY readAt DESC")
    fun allReadingRecords(): List<ChapterReadingRecord>

    @Query("SELECT COUNT(*) FROM ChapterReadingRecord WHERE cycle = :cycle")
    fun countTotalReadChapters(cycle: Int): Int

    @Query("SELECT COUNT(DISTINCT (readAt / 86400000)) FROM ChapterReadingRecord WHERE cycle = :cycle")
    fun countDistinctReadDays(cycle: Int): Int

    @Query("SELECT DISTINCT kjvBookOrdinal FROM ChapterReadingRecord WHERE cycle = :cycle")
    fun getDistinctReadBookOrdinals(cycle: Int): List<Int>

    @Query("SELECT chapter FROM ChapterReadingRecord WHERE kjvBookOrdinal = :kjvBookOrdinal AND cycle = :cycle ORDER BY chapter")
    fun getReadChaptersForBook(kjvBookOrdinal: Int, cycle: Int): List<Int>

    @Query("SELECT (readAt / 86400000) * 86400000 AS dayTimestamp, COUNT(*) AS count " +
        "FROM ChapterReadingRecord " +
        "WHERE readAt >= :startMs AND readAt <= :endMs AND cycle = :cycle " +
        "GROUP BY readAt / 86400000 ORDER BY dayTimestamp")
    fun getReadingCalendar(startMs: Long, endMs: Long, cycle: Int): List<DailyReadingCount>

    // Canonical read counters
    @Query("SELECT * FROM ChapterReadCounter WHERE bookOrdinal = :bookOrdinal AND chapter = :chapter LIMIT 1")
    fun getChapterReadCounter(bookOrdinal: Int, chapter: Int): ChapterReadCounter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChapterReadCounter(counter: ChapterReadCounter)

    @Query("SELECT COALESCE(SUM(readCount), 0) FROM ChapterReadCounter WHERE bookOrdinal = :bookOrdinal")
    fun getBookReadCount(bookOrdinal: Int): Int

    @Query("SELECT COALESCE(SUM(readCount), 0) FROM ChapterReadCounter WHERE bookOrdinal IN (:bookOrdinals)")
    fun getSectionReadCount(bookOrdinals: List<Int>): Int

    @Query("SELECT * FROM ChapterReadCounter WHERE bookOrdinal = :bookOrdinal ORDER BY chapter")
    fun getChapterCountersForBook(bookOrdinal: Int): List<ChapterReadCounter>

    // Word index
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertWordIndex(records: List<WordCountIndexRecord>)

    @Query("DELETE FROM WordCountIndexRecord WHERE moduleInitials = :moduleInitials")
    fun deleteWordIndexByModule(moduleInitials: String)

    @Query("SELECT * FROM WordCountIndexRecord WHERE moduleInitials = :moduleInitials AND versification = :versification")
    fun getWordIndex(moduleInitials: String, versification: String): List<WordCountIndexRecord>

    @Query("SELECT DISTINCT moduleVersion FROM WordCountIndexRecord WHERE moduleInitials = :moduleInitials LIMIT 1")
    fun getWordIndexVersion(moduleInitials: String): String?

    // Plan progress
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReadingPlanChapterProgress(progress: ReadingPlanChapterProgress)

    @Query("SELECT * FROM ReadingPlanChapterProgress WHERE planId = :planId AND moduleInitials = :moduleInitials AND bookOrdinal = :bookOrdinal AND chapter = :chapter LIMIT 1")
    fun loadReadingPlanChapterProgress(planId: String, moduleInitials: String, bookOrdinal: Int, chapter: Int): ReadingPlanChapterProgress?

    @Query("SELECT * FROM ReadingPlanChapterProgress WHERE planId = :planId")
    fun loadReadingPlanChapterProgressForPlan(planId: String): List<ReadingPlanChapterProgress>

    // Custom plan canonical chapter checklist
    @Query("SELECT * FROM CustomPlanChapterState WHERE planId = :planId ORDER BY bookOrdinal ASC, chapter ASC")
    fun loadCustomPlanChapterStates(planId: String): List<CustomPlanChapterState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCustomPlanChapterStates(states: List<CustomPlanChapterState>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCustomPlanChapterState(state: CustomPlanChapterState)

    @Query("DELETE FROM CustomPlanChapterState WHERE planId = :planId")
    fun deleteCustomPlanChapterStates(planId: String)

    @Query("""
        UPDATE CustomPlanChapterState
        SET isRead = :isRead, updatedAt = :updatedAt
        WHERE planId = :planId AND bookOrdinal = :bookOrdinal AND chapter = :chapter
    """)
    fun markCustomPlanChapterRead(
        planId: String,
        bookOrdinal: Int,
        chapter: Int,
        isRead: Boolean,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE CustomPlanChapterState
        SET isRead = :isRead, updatedAt = :updatedAt
        WHERE planId = :planId AND bookOrdinal = :bookOrdinal
    """)
    fun markCustomPlanBookRead(
        planId: String,
        bookOrdinal: Int,
        isRead: Boolean,
        updatedAt: Long,
    ): Int

    @Query("""
        SELECT
            COUNT(*) AS totalChapters,
            COALESCE(SUM(CASE WHEN isRead = 1 THEN 1 ELSE 0 END), 0) AS readChapters
        FROM CustomPlanChapterState
        WHERE planId = :planId
    """)
    fun getCustomPlanChapterReadSummary(planId: String): PlanChapterReadSummary

    @Query("""
        SELECT * FROM CustomPlanChapterState
        WHERE planId = :planId AND isRead = 0
        ORDER BY bookOrdinal ASC, chapter ASC
        LIMIT 1
    """)
    fun getFirstUnreadCustomPlanChapterState(planId: String): CustomPlanChapterState?

    @Query("""
        SELECT * FROM CustomPlanChapterState
        WHERE planId = :planId AND bookOrdinal = :bookOrdinal AND chapter = :chapter
        LIMIT 1
    """)
    fun getCustomPlanChapterState(planId: String, bookOrdinal: Int, chapter: Int): CustomPlanChapterState?

    @Query("UPDATE CustomPlanChapterState SET isRead = 0, updatedAt = :updatedAt WHERE planId = :planId")
    fun resetCustomPlanChapterStatesUnread(planId: String, updatedAt: Long)

    @Transaction
    fun replaceCustomPlanChapterStates(planId: String, states: List<CustomPlanChapterState>) {
        deleteCustomPlanChapterStates(planId)
        if (states.isNotEmpty()) {
            upsertCustomPlanChapterStates(states)
        }
    }

    @Transaction
    fun updateCustomPlanChapterReadAndResetIfNeeded(
        planId: String,
        bookOrdinal: Int,
        chapter: Int,
        isRead: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val updatedRows = markCustomPlanChapterRead(planId, bookOrdinal, chapter, isRead, updatedAt)
        if (updatedRows == 0) {
            upsertCustomPlanChapterState(
                CustomPlanChapterState(
                    planId = planId,
                    bookOrdinal = bookOrdinal,
                    chapter = chapter,
                    isRead = isRead,
                    updatedAt = updatedAt,
                )
            )
        }
        val summary = getCustomPlanChapterReadSummary(planId)
        val shouldReset = summary.totalChapters > 0 && summary.totalChapters == summary.readChapters
        if (shouldReset) {
            resetCustomPlanChapterStatesUnread(planId, updatedAt)
        }
        return shouldReset
    }

    @Transaction
    fun toggleCustomPlanChapterReadAndResetIfNeeded(
        planId: String,
        bookOrdinal: Int,
        chapter: Int,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = getCustomPlanChapterState(planId, bookOrdinal, chapter)
        return updateCustomPlanChapterReadAndResetIfNeeded(
            planId = planId,
            bookOrdinal = bookOrdinal,
            chapter = chapter,
            isRead = !(current?.isRead ?: false),
            updatedAt = updatedAt,
        )
    }

    @Transaction
    fun updateCustomPlanBookReadAndResetIfNeeded(
        planId: String,
        bookOrdinal: Int,
        isRead: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        markCustomPlanBookRead(
            planId = planId,
            bookOrdinal = bookOrdinal,
            isRead = isRead,
            updatedAt = updatedAt,
        )
        val summary = getCustomPlanChapterReadSummary(planId)
        val shouldReset = summary.totalChapters > 0 && summary.totalChapters == summary.readChapters
        if (shouldReset) {
            resetCustomPlanChapterStatesUnread(planId, updatedAt)
        }
        return shouldReset
    }
}

@Dao
interface GlobalReadingProgressSettingsDao {
    @Query("SELECT * FROM GlobalReadingProgressSettings LIMIT 1")
    fun get(): GlobalReadingProgressSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(settings: GlobalReadingProgressSettings)
}
