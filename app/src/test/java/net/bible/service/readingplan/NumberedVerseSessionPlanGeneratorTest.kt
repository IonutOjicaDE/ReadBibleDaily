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

import net.bible.android.control.versification.TestData
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.Versification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NumberedVerseSessionPlanGeneratorTest {
    @Test
    fun `one day contains the complete requested passage`() {
        val passage = verseRange(BibleBook.JOHN, 3, 16, 18)

        val days = assertExactNumberedCoverage(passage, dayCount = 1)

        assertEquals(listOf(passage.osisRef), days.map { it.readings.single().osisRef })
    }

    @Test
    fun `N numbered verses across N days produces one verse per day`() {
        val passage = verseRange(BibleBook.JOHN, 3, 16, 18)

        val days = assertExactNumberedCoverage(passage, dayCount = 3)

        assertEquals(listOf(1, 1, 1), days.numberedVerseCounts())
        assertEquals(listOf(16, 17, 18), days.map { it.readings.single().start.verse })
    }

    @Test
    fun `more verses than days are distributed deterministically`() {
        val passage = verseRange(BibleBook.JOHN, 3, 16, 25)

        val days = assertExactNumberedCoverage(passage, dayCount = 4)

        assertEquals(listOf(3, 3, 2, 2), days.numberedVerseCounts())
        assertEquals(
            listOf(16 to 18, 19 to 21, 22 to 23, 24 to 25),
            days.map { it.readings.single().start.verse to it.readings.single().end.verse },
        )
    }

    @Test
    fun `fewer numbered verses than days is rejected`() {
        assertIllegalArgument {
            NumberedVerseSessionPlanGenerator.generate(verseRange(BibleBook.JOHN, 3, 16, 17), 3)
        }
    }

    @Test
    fun `zero day count is rejected`() {
        assertIllegalArgument {
            NumberedVerseSessionPlanGenerator.generate(verseRange(BibleBook.JOHN, 3, 16, 17), 0)
        }
    }

    @Test
    fun `negative day count is rejected`() {
        assertIllegalArgument {
            NumberedVerseSessionPlanGenerator.generate(verseRange(BibleBook.JOHN, 3, 16, 17), -1)
        }
    }

    @Test
    fun `partial chapter range is covered exactly`() {
        val passage = verseRange(BibleBook.GEN, 1, 7, 13)

        val days = assertExactNumberedCoverage(passage, dayCount = 3)

        assertEquals(listOf(3, 2, 2), days.numberedVerseCounts())
    }

    @Test
    fun `chapter crossing range is covered exactly`() {
        val lastVerse = KJV.getLastVerse(BibleBook.GEN, 1)
        val passage = VerseRange(
            KJV,
            Verse(KJV, BibleBook.GEN, 1, lastVerse - 1),
            Verse(KJV, BibleBook.GEN, 2, 2),
        )

        val days = assertExactNumberedCoverage(passage, dayCount = 2)

        assertEquals(listOf(2, 2), days.numberedVerseCounts())
        assertEquals(lastVerse, days.first().readings.single().end.verse)
        assertEquals(1, days.last().readings.single().start.verse)
    }

    @Test
    fun `book crossing range is covered exactly`() {
        val lastChapter = KJV.getLastChapter(BibleBook.GEN)
        val lastVerse = KJV.getLastVerse(BibleBook.GEN, lastChapter)
        val passage = VerseRange(
            KJV,
            Verse(KJV, BibleBook.GEN, lastChapter, lastVerse - 1),
            Verse(KJV, BibleBook.EXOD, 1, 2),
        )

        val days = assertExactNumberedCoverage(passage, dayCount = 2)

        assertEquals(listOf(2, 2), days.numberedVerseCounts())
        assertEquals(BibleBook.GEN, days.first().readings.single().start.book)
        assertEquals(BibleBook.EXOD, days.last().readings.single().end.book)
    }

    @Test
    fun `verse zero introduction positions are not counted or emitted as boundaries`() {
        val passage = VerseRange(
            KJV,
            Verse(KJV, BibleBook.GEN, 1, KJV.getLastVerse(BibleBook.GEN, 1)),
            Verse(KJV, BibleBook.GEN, 2, 2),
        )

        val days = assertExactNumberedCoverage(passage, dayCount = 3)

        assertTrue(passage.cardinality > numberedVerses(passage).size)
        assertEquals(listOf(1, 1, 1), days.numberedVerseCounts())
        assertTrue(days.all { it.readings.single().start.verse > 0 })
        assertTrue(days.all { it.readings.single().end.verse > 0 })
    }

    @Test
    fun `generated ranges retain the source versification`() {
        val versification = TestData.KJVA
        val passage = VerseRange(
            versification,
            Verse(versification, BibleBook.MACC1, 1, 1),
            Verse(versification, BibleBook.MACC1, 1, 4),
        )

        val days = assertExactNumberedCoverage(passage, dayCount = 2)

        days.forEach { day ->
            assertSame(versification, day.readings.single().versification)
        }
    }

    @Test
    fun `passage without a numbered Scripture verse is rejected`() {
        val introduction = Verse(KJV, BibleBook.GEN, 1, 0)

        assertIllegalArgument {
            NumberedVerseSessionPlanGenerator.generate(VerseRange(KJV, introduction, introduction), 1)
        }
    }

    private fun assertExactNumberedCoverage(passage: VerseRange, dayCount: Int): List<SessionReadingPlanDay> {
        val days = NumberedVerseSessionPlanGenerator.generate(passage, dayCount)

        assertEquals(dayCount, days.size)
        assertTrue(days.all { it.readings.size == 1 })
        assertEquals(numberedVerses(passage), numberedVerses(days))
        return days
    }

    private fun numberedVerses(days: List<SessionReadingPlanDay>): List<Verse> =
        days.flatMap { numberedVerses(it.readings.single()) }

    private fun numberedVerses(range: VerseRange): List<Verse> =
        range.iterator().asSequence()
            .map { it as Verse }
            .filter { it.verse > 0 }
            .toList()

    private fun List<SessionReadingPlanDay>.numberedVerseCounts(): List<Int> =
        map { numberedVerses(it.readings.single()).size }

    private fun verseRange(
        book: BibleBook,
        chapter: Int,
        startVerse: Int,
        endVerse: Int,
    ): VerseRange = VerseRange(
        KJV,
        Verse(KJV, book, chapter, startVerse),
        Verse(KJV, book, chapter, endVerse),
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private companion object {
        val KJV: Versification = TestData.KJV
    }
}
