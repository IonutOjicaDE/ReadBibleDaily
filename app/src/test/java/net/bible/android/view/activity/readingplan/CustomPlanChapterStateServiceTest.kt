package net.bible.android.view.activity.readingplan

import net.bible.android.TEST_SDK
import net.bible.android.TestBibleApplication
import net.bible.android.database.progress.PROGRESS_DATABASE_VERSION
import net.bible.test.DatabaseResetter.resetDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestBibleApplication::class, sdk = [TEST_SDK])
class CustomPlanChapterStateServiceTest {

    @After
    fun tearDown() {
        resetDatabase()
    }

    @Test
    fun newPlanCreation_insertsUnreadInCanonicalOrder() {
        val planId = "plan-new"
        val created = kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(
                    CanonicalChapterKey(40, 2),
                    CanonicalChapterKey(1, 3),
                    CanonicalChapterKey(1, 1),
                ),
            )
        }

        assertEquals(
            listOf(
                CanonicalChapterKey(1, 1),
                CanonicalChapterKey(1, 3),
                CanonicalChapterKey(40, 2),
            ),
            created.map { it.key }
        )
        assertTrue(created.all { !it.isRead })
    }

    @Test
    fun reconcileEdit_addBooks_preservesReadAndAddsUnread() {
        val planId = "plan-edit-add"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)),
            )
            CustomPlanChapterStateService.markChapterRead(planId, CanonicalChapterKey(1, 2), true)
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2), CanonicalChapterKey(1, 3)),
            )
        }

        val states = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(listOf(false, true, false), states.map { it.isRead })
    }

    @Test
    fun reconcileEdit_removeBooks_onlyChangesOnConfirm() {
        val planId = "plan-edit-remove"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2), CanonicalChapterKey(1, 3)),
            )
        }

        val beforeConfirm = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(3, beforeConfirm.size)

        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)),
            )
        }

        val afterConfirm = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)), afterConfirm.map { it.key })
    }

    @Test
    fun cancelEdit_withoutConfirm_keepsStateUnchanged() {
        val planId = "plan-cancel"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)),
            )
            CustomPlanChapterStateService.markChapterRead(planId, CanonicalChapterKey(1, 1), true)
        }

        val persisted = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(listOf(true, false), persisted.map { it.isRead })
    }

    @Test
    fun canonicalOrderingInvariant_alwaysHolds() {
        val planId = "plan-order"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(
                    CanonicalChapterKey(40, 1),
                    CanonicalChapterKey(1, 2),
                    CanonicalChapterKey(1, 1),
                ),
            )
            CustomPlanChapterStateService.toggleChapterRead(planId, CanonicalChapterKey(1, 2))
        }

        val keys = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId).map { it.key } }
        assertEquals(
            listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2), CanonicalChapterKey(40, 1)),
            keys,
        )
    }

    @Test
    fun allRead_autoResetsBackToUnread() {
        val planId = "plan-reset"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)),
            )
            CustomPlanChapterStateService.markChapterRead(planId, CanonicalChapterKey(1, 1), true)
            val resetTriggered = CustomPlanChapterStateService.markChapterRead(planId, CanonicalChapterKey(1, 2), true)
            assertTrue(resetTriggered)
        }

        val summary = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.getPlanProgressSummary(planId) }
        assertEquals(2, summary.totalChapters)
        assertEquals(0, summary.readChapters)
        assertEquals(2, summary.unreadChapters)
        val firstUnread = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.getFirstUnreadChapter(planId) }
        assertNotNull(firstUnread)
    }

    @Test
    fun duplicateCanonicalKeys_areDeduplicatedSafely() {
        val planId = "plan-dedupe"
        val states = kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(
                    CanonicalChapterKey(1, 1),
                    CanonicalChapterKey(1, 1),
                    CanonicalChapterKey(1, 2),
                    CanonicalChapterKey(1, 2),
                ),
            )
        }

        assertEquals(2, states.size)
        assertEquals(listOf(CanonicalChapterKey(1, 1), CanonicalChapterKey(1, 2)), states.map { it.key })
        assertFalse(states.any { it.isRead })
    }

    @Test
    fun markBookRead_marksAllBookChaptersAndSupportsUnread() {
        val planId = "plan-book-toggle"
        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.reconcilePlanChapterStates(
                planId,
                listOf(
                    CanonicalChapterKey(1, 1),
                    CanonicalChapterKey(1, 2),
                    CanonicalChapterKey(2, 1),
                ),
            )
            CustomPlanChapterStateService.markBookRead(planId, bookOrdinal = 1, isRead = true)
        }

        val afterRead = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(listOf(true, true, false), afterRead.map { it.isRead })

        kotlinx.coroutines.runBlocking {
            CustomPlanChapterStateService.markBookRead(planId, bookOrdinal = 1, isRead = false)
        }
        val afterUnread = kotlinx.coroutines.runBlocking { CustomPlanChapterStateService.loadPlanChapterStates(planId) }
        assertEquals(listOf(false, false, false), afterUnread.map { it.isRead })
    }

    @Test
    fun progressDatabaseVersion_bumpedForCustomPlanChapterState() {
        assertTrue(PROGRESS_DATABASE_VERSION >= 8)
    }
}
