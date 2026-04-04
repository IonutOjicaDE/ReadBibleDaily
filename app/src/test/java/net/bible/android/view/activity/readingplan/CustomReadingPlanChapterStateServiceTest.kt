package net.bible.android.view.activity.readingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.bible.android.database.progress.ProgressDatabase
import net.bible.test.DatabaseResetter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomReadingPlanChapterStateServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(ProgressDatabase.dbFileName)
        DatabaseResetter.resetDatabase()
    }

    @After
    fun tearDown() {
        DatabaseResetter.resetDatabase()
    }

    @Test
    fun reconcilePlanChapterStates_preservesReadFlagsAndCanonicalOrder() = runBlocking {
        val planId = "plan-alpha"

        CustomReadingPlanChapterStateService.reconcilePlanChapterStates(
            planId,
            listOf(1 to 2, 1 to 1, 2 to 1),
        )
        CustomReadingPlanChapterStateService.setChapterRead(planId, 1, 2, true)

        CustomReadingPlanChapterStateService.reconcilePlanChapterStates(
            planId,
            listOf(2 to 1, 1 to 2, 3 to 1),
        )

        val rows = CustomReadingPlanChapterStateService.loadPlanChapterStates(planId)

        assertEquals(listOf(1 to 2, 2 to 1, 3 to 1), rows.map { it.bookId to it.chapter })
        assertTrue(rows.first { it.bookId == 1 && it.chapter == 2 }.isRead)
        assertFalse(rows.first { it.bookId == 2 && it.chapter == 1 }.isRead)
        assertFalse(rows.first { it.bookId == 3 && it.chapter == 1 }.isRead)
    }

    @Test
    fun readUnreadApis_updateCountsAndFirstUnread() = runBlocking {
        val planId = "plan-beta"

        CustomReadingPlanChapterStateService.reconcilePlanChapterStates(
            planId,
            listOf(1 to 1, 1 to 2, 2 to 1),
        )

        assertEquals(3, CustomReadingPlanChapterStateService.countTotalChapters(planId))
        assertEquals(0, CustomReadingPlanChapterStateService.countReadChapters(planId))
        assertEquals(1 to 1, CustomReadingPlanChapterStateService.firstUnreadChapter(planId)?.let { it.bookId to it.chapter })

        CustomReadingPlanChapterStateService.setChapterRead(planId, 1, 1, true)
        CustomReadingPlanChapterStateService.setChapterRead(planId, 1, 2, true)

        assertEquals(2, CustomReadingPlanChapterStateService.countReadChapters(planId))
        assertEquals(2 to 1, CustomReadingPlanChapterStateService.firstUnreadChapter(planId)?.let { it.bookId to it.chapter })

        CustomReadingPlanChapterStateService.setChapterRead(planId, 2, 1, true)
        assertNull(CustomReadingPlanChapterStateService.firstUnreadChapter(planId))
    }

    @Test
    fun setChapterRead_upsertsMissingRow() = runBlocking {
        val planId = "plan-gamma"

        CustomReadingPlanChapterStateService.setChapterRead(planId, 10, 5, true)

        val rows = CustomReadingPlanChapterStateService.loadPlanChapterStates(planId)
        assertEquals(1, rows.size)
        assertEquals(10, rows.single().bookId)
        assertEquals(5, rows.single().chapter)
        assertTrue(rows.single().isRead)
        assertNull(CustomReadingPlanChapterStateService.firstUnreadChapter(planId))
    }
}
