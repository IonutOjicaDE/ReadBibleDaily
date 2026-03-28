package net.bible.android.view.activity.readingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.bible.android.database.ReadingPlanDatabase
import net.bible.test.DatabaseResetter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomReadingPlanRepositoryPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetState()
        context.deleteDatabase(ReadingPlanDatabase.dbFileName)
        resetState()
    }

    @After
    fun tearDown() {
        resetState()
    }

    @Test
    fun initialize_seedsOnlyOnceAndLoadsPersistedPlansOnRestart() {
        CustomReadingPlanInMemoryRepository.initialize(context)
        val firstLoad = CustomReadingPlanInMemoryRepository.getPlans()

        CustomReadingPlanInMemoryRepository.resetForTesting()
        DatabaseResetter.resetDatabase()

        CustomReadingPlanInMemoryRepository.initialize(context)
        val secondLoad = CustomReadingPlanInMemoryRepository.getPlans()

        assertEquals(2, firstLoad.size)
        assertEquals(firstLoad.map { it.id }, secondLoad.map { it.id })
    }

    @Test
    fun upsertAndDelete_persistAcrossRepositoryReset() {
        CustomReadingPlanInMemoryRepository.initialize(context)
        val plan = CustomReadingPlanInMemoryRepository.getPlans().first()
        val updated = plan.copy(
            title = "Persisted title",
            selectionSummary = "Persisted summary",
            selection = CustomReadingPlanSelection(setOf("bible:KJV:new_testament", "bible:KJV:book:JOHN")),
            minutesPerSession = 22,
            periodInDays = 3,
            selectedDays = linkedSetOf(ReadingWeekDay.TUESDAY, ReadingWeekDay.THURSDAY),
            isActive = false,
        )
        CustomReadingPlanInMemoryRepository.upsert(updated)

        CustomReadingPlanInMemoryRepository.resetForTesting()
        DatabaseResetter.resetDatabase()
        CustomReadingPlanInMemoryRepository.initialize(context)

        val restored = CustomReadingPlanInMemoryRepository.getPlan(updated.id)
        assertNotNull(restored)
        assertEquals(updated, restored)

        CustomReadingPlanInMemoryRepository.delete(updated.id)
        CustomReadingPlanInMemoryRepository.resetForTesting()
        DatabaseResetter.resetDatabase()
        CustomReadingPlanInMemoryRepository.initialize(context)

        assertNull(CustomReadingPlanInMemoryRepository.getPlan(updated.id))
        assertFalse(CustomReadingPlanInMemoryRepository.getPlans().any { it.id == updated.id })
    }

    private fun resetState() {
        CustomReadingPlanInMemoryRepository.resetForTesting()
        DatabaseResetter.resetDatabase()
    }
}
