package net.bible.android.view.activity.readingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.bible.android.activity.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomReadingPlanUiFormatterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

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
}
