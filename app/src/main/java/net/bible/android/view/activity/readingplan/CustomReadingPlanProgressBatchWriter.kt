package net.bible.android.view.activity.readingplan

/**
 * Write-behind accumulator to avoid persisting progress on every scroll event.
 */
class CustomReadingPlanProgressBatchWriter(
    private val sink: (PendingProgress) -> Unit,
    private val minCompletionDelta: Float = 0.03f,
) {
    data class PendingProgress(
        val planId: String,
        val chapter: ChapterIdentity,
        val completion: Float,
        val position: ChapterResumePosition,
    )

    private val pending = linkedMapOf<String, PendingProgress>()
    private val lastFlushedCompletion = mutableMapOf<String, Float>()

    fun enqueue(progress: PendingProgress) {
        val key = progress.key()
        pending[key] = progress
        val previous = lastFlushedCompletion[key]
        if (previous == null || kotlin.math.abs(progress.completion - previous) >= minCompletionDelta || progress.completion >= 1f) {
            flushKey(key)
        }
    }

    fun flushAll() {
        pending.keys.toList().forEach(::flushKey)
    }

    private fun flushKey(key: String) {
        val value = pending.remove(key) ?: return
        sink(value)
        lastFlushedCompletion[key] = value.completion
    }

    private fun PendingProgress.key(): String = "${planId}:${chapter.moduleInitials}:${chapter.bookOrdinal}:${chapter.chapter}"
}
