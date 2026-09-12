/*
 * Copyright (c) 2026 Sykerö Software / Tuomas Airaksinen and the AndBible contributors.
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

package net.bible.service.readingplan

import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange

/**
 * Splits a continuous passage into balanced session days by numbered Scripture verses.
 *
 * JSword's ordinal traversal includes verse-0 introduction positions at chapter and book
 * boundaries. They must not affect the size of a day or be used as a daily range boundary.
 */
internal object NumberedVerseSessionPlanGenerator {
    fun generate(passage: VerseRange, dayCount: Int): List<SessionReadingPlanDay> {
        require(dayCount > 0) { "Day count must be positive." }

        val verses = numberedVerses(passage)
        require(verses.isNotEmpty()) { "Passage must contain a numbered Scripture verse." }
        require(dayCount <= verses.size) { "Day count cannot exceed the number of numbered Scripture verses." }

        val versesPerDay = verses.size / dayCount
        val daysWithOneExtraVerse = verses.size % dayCount
        val days = ArrayList<SessionReadingPlanDay>(dayCount)
        var startIndex = 0

        repeat(dayCount) { dayIndex ->
            val verseCount = versesPerDay + if (dayIndex < daysWithOneExtraVerse) 1 else 0
            val endIndex = startIndex + verseCount - 1
            val reading = VerseRange(passage.versification, verses[startIndex], verses[endIndex])
            days += SessionReadingPlanDay(listOf(reading))
            startIndex = endIndex + 1
        }

        return days
    }

    private fun numberedVerses(passage: VerseRange): List<Verse> =
        passage.iterator().asSequence()
            .map { it as Verse }
            .filter { it.verse > 0 }
            .toList()
}
