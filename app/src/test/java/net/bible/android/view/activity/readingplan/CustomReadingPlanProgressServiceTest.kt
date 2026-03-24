package net.bible.android.view.activity.readingplan

import net.bible.android.TEST_SDK
import net.bible.android.TestBibleApplication
import net.bible.android.database.progress.PROGRESS_DATABASE_VERSION
import net.bible.android.database.progress.ReadingSource
import net.bible.test.DatabaseResetter.resetDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestBibleApplication::class, sdk = [TEST_SDK])
class CustomReadingPlanProgressServiceTest {

    @After
    fun tearDown() {
        resetDatabase()
    }

    @Test
    fun fallbackHeuristic_usesDeterministicVerseWeight() {
        assertEquals(20, CustomReadingPlanProgressService.fallbackWordsForVerseCount(1))
        assertEquals(1200, CustomReadingPlanProgressService.fallbackWordsForVerseCount(60))
        assertEquals(20, CustomReadingPlanProgressService.fallbackWordsForVerseCount(0))
    }

    @Test
    fun weightedProgress_mathIsWordWeighted() {
        val chapterA = ChapterIdentity(moduleInitials = "KJV", bookOrdinal = 1, chapter = 1)
        val chapterB = ChapterIdentity(moduleInitials = "KJV", bookOrdinal = 1, chapter = 2)

        val weighted = CustomReadingPlanProgressService.weightedProgress(
            chapterWeights = mapOf(chapterA to 100, chapterB to 300),
            completionByChapter = mapOf(chapterA to 1f, chapterB to 0.5f),
        )

        assertEquals(0.625f, weighted, 0.0001f)
    }

    @Test
    fun chapterCounters_incrementAndAggregate() {
        CustomReadingPlanProgressService.incrementChapterReadCount(1, 1, ReadingSource.MANUAL)
        CustomReadingPlanProgressService.incrementChapterReadCount(1, 1, ReadingSource.AUTO_SCROLL)
        CustomReadingPlanProgressService.incrementChapterReadCount(1, 2, ReadingSource.MANUAL)

        assertEquals(2, CustomReadingPlanProgressService.getChapterReadCount(1, 1))
        assertEquals(3, CustomReadingPlanProgressService.getBookReadCount(1))
        assertEquals(3, CustomReadingPlanProgressService.getSectionReadCount(listOf(1, 2)))
    }

    @Test
    fun saveAndLoadResume_roundTripsProgressState() {
        val chapter = ChapterIdentity(moduleInitials = "KJV", bookOrdinal = 40, chapter = 3)
        CustomReadingPlanProgressService.saveChapterResume(
            planId = "plan-a",
            chapter = chapter,
            completion = 0.42f,
            position = ChapterResumePosition(lastReadOrdinal = 123456, chapterAnchor = "p-4"),
        )

        val loaded = CustomReadingPlanProgressService.loadChapterResume("plan-a", chapter)

        assertEquals(0.42f, loaded?.completionPercent ?: 0f, 0.0001f)
        assertEquals(123456, loaded?.lastReadOrdinal)
        assertEquals("p-4", loaded?.chapterAnchor)
    }

    @Test
    fun progressDatabaseVersion_bumpedForNewProgressEntities() {
        assertTrue(PROGRESS_DATABASE_VERSION >= 5)
    }
}
