package net.bible.android.view.activity.readingplan

/** Formats sticky chapter indicator text and throttles high-frequency updates. */
object CustomReadingPlanStickyTitleFormatter {
    private const val THROTTLE_MS = 500L

    data class VisibleChapterProgress(
        val label: String,
        val completionPercent: Int,
    )

    fun format(items: List<VisibleChapterProgress>): String = items.joinToString(", ") {
        "${it.label} (${it.completionPercent.coerceIn(0, 100)}% read)"
    }

    fun shouldEmit(nowMs: Long, lastEmittedMs: Long?): Boolean {
        if (lastEmittedMs == null) return true
        return nowMs - lastEmittedMs >= THROTTLE_MS
    }
}
