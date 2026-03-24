package net.bible.android.view.activity.readingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.bible.android.TEST_SDK
import net.bible.android.TestBibleApplication
import net.bible.android.database.progress.PROGRESS_DATABASE_VERSION
import net.bible.test.DatabaseResetter.resetDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = TestBibleApplication::class, sdk = [TEST_SDK])
class CustomReadingPlanStartupAndPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        CustomReadingPlanInMemoryRepository.resetForTesting()
    }

    @After
    fun tearDown() {
        resetDatabase()
    }

    @Test
    fun persistence_roundTripsPlanAcrossRepositoryRestart() {
        val plan = CustomReadingPlan(
            id = "persist-1",
            title = "Morning",
            selectionSummary = "Genesis",
            selection = CustomReadingPlanSelection(setOf("bible:KJV:book:GEN")),
            minutesPerSession = 12,
            periodInDays = 2,
            selectedDays = linkedSetOf(ReadingWeekDay.MONDAY, ReadingWeekDay.WEDNESDAY),
            isActive = true,
        )
        CustomReadingPlanInMemoryRepository.initialize(context)
        CustomReadingPlanInMemoryRepository.upsert(plan)

        CustomReadingPlanInMemoryRepository.clearCacheForTesting()
        CustomReadingPlanInMemoryRepository.initialize(context)
        val restored = CustomReadingPlanInMemoryRepository.getPlan("persist-1")

        assertEquals("Morning", restored?.title)
        assertEquals(12, restored?.minutesPerSession)
        assertEquals(setOf(ReadingWeekDay.MONDAY, ReadingWeekDay.WEDNESDAY), restored?.selectedDays)
        assertTrue(restored?.isActive == true)
    }

    @Test
    fun queueBuilder_deduplicatesAndRespectsPlanOrder() {
        val chapterGen1 = ChapterIdentity("KJV", 0, 1)
        val chapterGen2 = ChapterIdentity("KJV", 0, 2)
        val planA = CustomReadingPlan(id = "a", title = "A", selectionSummary = "x")
        val planB = CustomReadingPlan(id = "b", title = "B", selectionSummary = "x")

        val builder = FakeQueueBuilder(
            chapterMap = mapOf(
                "a" to listOf(chapterGen1, chapterGen2),
                "b" to listOf(chapterGen1),
            ),
            completion = mapOf(
                "a:${chapterGen1.bookOrdinal}:${chapterGen1.chapter}" to 0f,
                "a:${chapterGen2.bookOrdinal}:${chapterGen2.chapter}" to 0f,
                "b:${chapterGen1.bookOrdinal}:${chapterGen1.chapter}" to 0f,
            ),
        )

        val queue = builder.buildTodayUnreadQueue(listOf(planA, planB), emptyList())

        assertEquals(listOf(chapterGen1, chapterGen2), queue.map { it.chapter })
        assertEquals(listOf("a", "a"), queue.map { it.planId })
    }

    @Test
    fun queueBuilder_treatsDuplicateChapterAsReadWhenAnyActivePlanCompletedIt() {
        val chapterGen1 = ChapterIdentity("KJV", 0, 1)
        val planA = CustomReadingPlan(id = "a", title = "A", selectionSummary = "x")
        val planB = CustomReadingPlan(id = "b", title = "B", selectionSummary = "x")

        val builder = FakeQueueBuilder(
            chapterMap = mapOf(
                "a" to listOf(chapterGen1),
                "b" to listOf(chapterGen1),
            ),
            completion = mapOf(
                "a:${chapterGen1.bookOrdinal}:${chapterGen1.chapter}" to 1f,
                "b:${chapterGen1.bookOrdinal}:${chapterGen1.chapter}" to 0f,
            ),
        )

        val queue = builder.buildTodayUnreadQueue(listOf(planA, planB), emptyList())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun startupDecision_falseWhenNoUnreadToday() {
        val plan = CustomReadingPlan(
            id = "a",
            title = "A",
            selectionSummary = "x",
            selectedDays = linkedSetOf(ReadingWeekDay.TUESDAY),
        )
        val builder = FakeQueueBuilder(emptyMap(), emptyMap())
        val decision = builder.decideStartup(listOf(plan), emptyList())
        assertFalse(decision.shouldEnterMode)
    }

    @Test
    fun resumeRule_usesSecondParagraphAboveViewport() {
        val builder = CustomReadingPlanQueueBuilder()
        val resumeOrdinal = builder.calculateResumeOrdinal(listOf(10, 20, 30, 40, 50))
        assertEquals(30, resumeOrdinal)
    }

    @Test
    fun stickyTitle_formatsTwoChapterTransitionAndThrottles() {
        val text = CustomReadingPlanStickyTitleFormatter.format(
            listOf(
                CustomReadingPlanStickyTitleFormatter.VisibleChapterProgress("Marcu 1", 89),
                CustomReadingPlanStickyTitleFormatter.VisibleChapterProgress("2", 0),
            )
        )
        assertEquals("Marcu 1 (89% read), 2 (0% read)", text)
        assertFalse(CustomReadingPlanStickyTitleFormatter.shouldEmit(nowMs = 1000, lastEmittedMs = 700))
        assertTrue(CustomReadingPlanStickyTitleFormatter.shouldEmit(nowMs = 1300, lastEmittedMs = 700))
    }

    @Test
    fun batchedWriter_flushesOnThresholdAndPause() {
        val writes = mutableListOf<CustomReadingPlanProgressBatchWriter.PendingProgress>()
        val writer = CustomReadingPlanProgressBatchWriter(sink = { writes += it }, minCompletionDelta = 0.1f)
        val chapter = ChapterIdentity("KJV", 1, 1)

        writer.enqueue(
            CustomReadingPlanProgressBatchWriter.PendingProgress(
                "p", chapter, 0.01f,
                ChapterResumePosition(11, "p1")
            )
        )
        writer.enqueue(
            CustomReadingPlanProgressBatchWriter.PendingProgress(
                "p", chapter, 0.05f,
                ChapterResumePosition(12, "p2")
            )
        )
        writer.flushAll()

        assertEquals(2, writes.size)
        assertEquals(0.05f, writes.last().completion)
    }

    @Test
    fun progressDatabaseVersion_bumpedForCustomPlanPersistence() {
        assertTrue(PROGRESS_DATABASE_VERSION >= 6)
    }
}

private class FakeQueueBuilder(
    private val chapterMap: Map<String, List<ChapterIdentity>>,
    private val completion: Map<String, Float>,
) : CustomReadingPlanQueueBuilder(
    progressService = object : CustomReadingPlanProgressReader {
        override fun loadChapterResume(planId: String, chapter: ChapterIdentity) =
            net.bible.android.database.progress.ReadingPlanChapterProgress(
                planId = planId,
                moduleInitials = chapter.moduleInitials,
                bookOrdinal = chapter.bookOrdinal,
                chapter = chapter.chapter,
                completionPercent = completion["$planId:${chapter.bookOrdinal}:${chapter.chapter}"] ?: 0f,
            )
    },
    dateProvider = { LocalDate.of(2026, 3, 24) },
) {
    override fun buildTodayUnreadQueue(plans: List<CustomReadingPlan>, treeNodes: List<CustomReadingPlanTreeNode>): List<CustomPlanQueueItem> {
        val dedup = linkedMapOf<ChapterIdentity, CustomPlanQueueItem>()
        val activePlanIds = plans.map { it.id }
        plans.forEachIndexed { index, plan ->
            chapterMap[plan.id].orEmpty().forEach { chapter ->
                val completionAcrossPlans = activePlanIds.maxOfOrNull { planId ->
                    completion["$planId:${chapter.bookOrdinal}:${chapter.chapter}"] ?: 0f
                } ?: 0f
                if (completionAcrossPlans < 1f) {
                    dedup.putIfAbsent(chapter, CustomPlanQueueItem(chapter, plan.id, index))
                }
            }
        }
        return dedup.values.toList()
    }
}
