package net.bible.android.view.activity.readingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.bible.android.activity.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomReadingPlanUiFormatterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CustomReadingPlanInMemoryRepository.resetForTesting()
    }

    @Test
    fun estimatePages_usesConfiguredWordsPerPagePlaceholder() {
        assertEquals(9, CustomReadingPlanUiFormatter.estimatePages(10))
    }

    @Test
    fun completionDurationText_switchesToMonthsForLongerDurations() {
        assertEquals("1 month and 10 days", CustomReadingPlanUiFormatter.completionDurationText(context, 40))
    }

    @Test
    fun selectedDaysSummary_returnsEveryDayForFullSelection() {
        val summary = CustomReadingPlanUiFormatter.selectedDaysSummary(context, ReadingWeekDay.entries.toSet())
        assertEquals(context.getString(R.string.custom_reading_plan_days_all), summary)
    }

    @Test
    fun buildAdditionalInfo_requiresAtLeastOneWeekday() {
        val plan = CustomReadingPlan(
            title = "Draft",
            selectionSummary = defaultSelectionSummary(context),
            selectedDays = linkedSetOf(),
        )

        val info = CustomReadingPlanUiFormatter.buildAdditionalInfo(context, plan)

        assertEquals(context.getString(R.string.custom_reading_plan_info_select_weekday), info.first)
        assertTrue(info.second.contains("Draft"))
    }

    @Test
    fun initialize_doesNotRecreateSamplePlansAfterListBecomesEmpty() {
        CustomReadingPlanInMemoryRepository.initialize(context)
        CustomReadingPlanInMemoryRepository.getPlans().map { it.id }.forEach {
            CustomReadingPlanInMemoryRepository.delete(it)
        }

        CustomReadingPlanInMemoryRepository.initialize(context)

        assertTrue(CustomReadingPlanInMemoryRepository.getPlans().isEmpty())
    }
}
