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

import kotlinx.coroutines.runBlocking
import net.bible.android.TEST_SDK
import net.bible.android.TestBibleApplication
import net.bible.android.control.versification.TestData
import net.bible.android.database.ReadingPlanDatabase
import net.bible.android.database.readingplan.ReadingPlanDao
import net.bible.service.common.AndBibleAddons
import net.bible.service.common.CommonUtils
import net.bible.service.db.DatabaseContainer
import net.bible.test.DatabaseResetter
import org.crosswire.common.util.NetUtil
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.sword.NullBackend
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = TestBibleApplication::class, sdk = [TEST_SDK])
class ReadingPlanTextFileDaoTest {

    @After
    fun tearDown() {
        DatabaseResetter.resetDatabase()
    }

    @Test
    fun `adding a session plan appends its DTO to readingPlanList`() {
        val dao = ReadingPlanTextFileDao()
        val existingCodes = existingPlanCodes(dao)

        val addedPlan = dao.addSessionPlan("  Session plan  ", testVerseRange())
        val plans = dao.readingPlanList

        assertEquals(existingCodes, plans.dropLast(1).map { it.planCode })
        assertEquals(addedPlan.planCode, plans.last().planCode)
    }

    @Test
    fun `session plan DTO retains its trimmed name versification and one non-date-based day`() {
        val dao = ReadingPlanTextFileDao()
        val verseRange = testVerseRange()

        val addedPlan = dao.addSessionPlan("  Session plan  ", verseRange)
        val plan = dao.readingPlanList.single { it.planCode == addedPlan.planCode }

        assertEquals("Session plan", plan.planName)
        assertEquals(verseRange.versification, plan.versification)
        assertEquals(1, plan.numberOfPlanDays)
        assertEquals(1, dao.getNumberOfPlanDays(plan.planCode))
        assertFalse(plan.isDateBasedPlan)
        assertTrue(plan.planCode.startsWith("session_"))
        UUID.fromString(plan.planCode.removePrefix("session_"))
    }

    @Test
    fun `session plan reading flows return one parsed contiguous verse range`() {
        val dao = ReadingPlanTextFileDao()
        val verseRange = testVerseRange()
        val plan = dao.addSessionPlan("Session plan", verseRange)

        val readingList = dao.getReadingList(plan.planCode)
        assertEquals(1, readingList.size)
        assertReadingEquals(verseRange, readingList.single())
        assertReadingEquals(verseRange, dao.getReading(plan.planCode, 1))
    }

    @Test
    fun `session plan can contain ordered days`() {
        val dao = ReadingPlanTextFileDao()
        val days = listOf(
            SessionReadingPlanDay(listOf(verseRange(BibleBook.JOHN, 1, 1, 3))),
            SessionReadingPlanDay(listOf(verseRange(BibleBook.JOHN, 2, 1, 3))),
            SessionReadingPlanDay(listOf(verseRange(BibleBook.JOHN, 3, 1, 3))),
        )

        val plan = dao.addSessionPlan("Multi-day session plan", days)
        val readings = dao.getReadingList(plan.planCode)

        assertEquals(3, plan.numberOfPlanDays)
        assertEquals(3, dao.getNumberOfPlanDays(plan.planCode))
        assertEquals(listOf(1, 2, 3), readings.map { it.day })
        assertEquals(
            days.map { it.readings.single().osisRef },
            readings.map { it.getReadingKey(1).osisRef },
        )
    }

    @Test
    fun `session plan day can contain multiple readings preserving OSIS references`() {
        val dao = ReadingPlanTextFileDao()
        val firstReading = verseRange(BibleBook.JOHN, 1, 1, 3)
        val secondReading = verseRange(BibleBook.PS, 1, 1, 2)

        val plan = dao.addSessionPlan(
            "Multiple readings",
            listOf(SessionReadingPlanDay(listOf(firstReading, secondReading))),
        )
        val reading = dao.getReadingList(plan.planCode).single()

        assertEquals(2, reading.numReadings)
        assertEquals(firstReading.osisRef, reading.getReadingKey(1).osisRef)
        assertEquals(secondReading.osisRef, reading.getReadingKey(2).osisRef)
    }

    @Test
    fun `invalid session plan input is rejected without registering a plan`() {
        val firstCode = "session_${UUID.randomUUID()}"
        val secondCode = "session_${UUID.randomUUID()}"
        val candidates = ArrayDeque(listOf(firstCode, secondCode))
        val dao = ReadingPlanTextFileDao { candidates.removeFirst() }

        assertSessionPlanCreationFails {
            dao.addSessionPlan("No days", emptyList())
        }
        assertSessionPlanCreationFails {
            dao.addSessionPlan("Empty day", listOf(SessionReadingPlanDay(emptyList())))
        }

        val validPlan = dao.addSessionPlan("Valid plan", testVerseRange())

        assertEquals(firstCode, validPlan.planCode)
        assertEquals(SessionPlanState.ACTIVE, dao.sessionPlanState(firstCode))
        assertEquals(listOf(secondCode), candidates.toList())
    }

    @Test
    fun `incompatible session plan versifications are rejected atomically`() {
        val firstCode = "session_${UUID.randomUUID()}"
        val secondCode = "session_${UUID.randomUUID()}"
        val candidates = ArrayDeque(listOf(firstCode, secondCode))
        val dao = ReadingPlanTextFileDao { candidates.removeFirst() }

        assertSessionPlanCreationFails {
            dao.addSessionPlan(
                "Incompatible versifications",
                listOf(
                    SessionReadingPlanDay(listOf(testVerseRange())),
                    SessionReadingPlanDay(listOf(TestData.KJVA_1MACC_1_2_3)),
                ),
            )
        }

        val validPlan = dao.addSessionPlan("Valid plan", testVerseRange())

        assertEquals(firstCode, validPlan.planCode)
        assertEquals(SessionPlanState.ACTIVE, dao.sessionPlanState(firstCode))
        assertEquals(listOf(secondCode), candidates.toList())
    }

    @Test
    fun `adding a session plan preserves every existing plan code`() {
        val dao = ReadingPlanTextFileDao()
        val existingCodes = existingPlanCodes(dao)

        dao.addSessionPlan("Session plan", testVerseRange())

        assertTrue(dao.readingPlanList.map { it.planCode }.containsAll(existingCodes))
    }

    @Test
    fun `session plans belong only to the DAO instance that created them`() {
        val firstDao = ReadingPlanTextFileDao()
        val plan = firstDao.addSessionPlan("Session plan", testVerseRange())

        val secondDao = ReadingPlanTextFileDao()
        secondDao.addSessionPlan("Second session plan", testVerseRange())

        assertTrue(firstDao.readingPlanList.any { it.planCode == plan.planCode })
        assertFalse(secondDao.readingPlanList.any { it.planCode == plan.planCode })
    }

    @Test
    fun `newly added session plan is active`() {
        val dao = ReadingPlanTextFileDao()
        val plan = dao.addSessionPlan("Session plan", testVerseRange())

        assertEquals(SessionPlanState.ACTIVE, dao.sessionPlanState(plan.planCode))
    }

    @Test
    fun `absent prefixed plan is expired`() {
        val dao = ReadingPlanTextFileDao()

        assertEquals(SessionPlanState.EXPIRED, dao.sessionPlanState("session_${UUID.randomUUID()}"))
    }

    @Test
    fun `missing non-prefixed plan is not a session plan`() {
        val dao = ReadingPlanTextFileDao()

        assertEquals(SessionPlanState.NOT_SESSION, dao.sessionPlanState("missing_${UUID.randomUUID()}"))
    }

    @Test
    fun `manual prefixed plan is not a session plan`() {
        val planCode = "session_manual_${UUID.randomUUID()}"
        val manualPlan = createManualPlan(planCode)

        try {
            assertEquals(SessionPlanState.NOT_SESSION, ReadingPlanTextFileDao().sessionPlanState(planCode))
        } finally {
            manualPlan.delete()
        }
    }

    @Test
    fun `add-on prefixed plan is not a session plan`() {
        val planCode = "session_addon_${UUID.randomUUID()}"
        val addOn = createReadingPlanAddOn(planCode)

        try {
            assertEquals(SessionPlanState.NOT_SESSION, ReadingPlanTextFileDao().sessionPlanState(planCode))
        } finally {
            removeReadingPlanAddOn(addOn)
        }
    }

    @Test
    fun `bundled plan is not a session plan`() {
        val dao = ReadingPlanTextFileDao()
        val planCode = dao.internalPlanCodes.first()

        assertEquals(SessionPlanState.NOT_SESSION, dao.sessionPlanState(planCode))
    }

    @Test
    fun `session plan from another DAO instance is expired`() {
        val plan = ReadingPlanTextFileDao().addSessionPlan("Session plan", testVerseRange())

        assertEquals(SessionPlanState.EXPIRED, ReadingPlanTextFileDao().sessionPlanState(plan.planCode))
    }

    @Test
    fun `code generation enforces prefix and retries manual add-on and session collisions`() {
        val bundledCode = ReadingPlanTextFileDao().internalPlanCodes.first()
        val manualCode = "session_manual_${UUID.randomUUID()}"
        val addOnCode = "session_addon_${UUID.randomUUID()}"
        val existingSessionCode = "session_${UUID.randomUUID()}"
        val finalCode = "session_${UUID.randomUUID()}"
        val manualPlan = createManualPlan(manualCode)
        val addOn = createReadingPlanAddOn(addOnCode)
        val candidates = ArrayDeque(
            listOf(existingSessionCode, bundledCode, manualCode, addOnCode, existingSessionCode, finalCode)
        )

        try {
            val dao = ReadingPlanTextFileDao { candidates.removeFirst() }
            val existingSessionPlan = dao.addSessionPlan("Existing session", testVerseRange())
            val addedPlan = dao.addSessionPlan("New session", testVerseRange())

            assertEquals(existingSessionCode, existingSessionPlan.planCode)
            assertEquals(finalCode, addedPlan.planCode)
            assertTrue(candidates.isEmpty())
        } finally {
            removeReadingPlanAddOn(addOn)
            manualPlan.delete()
        }
    }

    @Test
    fun `duplicate visible names receive different session plan codes`() {
        val dao = ReadingPlanTextFileDao()

        val firstPlan = dao.addSessionPlan("Same name", testVerseRange())
        val secondPlan = dao.addSessionPlan("Same name", testVerseRange())

        assertEquals("Same name", firstPlan.planName)
        assertEquals("Same name", secondPlan.planName)
        assertNotEquals(firstPlan.planCode, secondPlan.planCode)
    }

    @Test
    fun `failed session plan creation rolls back definition and caches`() {
        val retainedCode = "session_${UUID.randomUUID()}"
        val failedCode = "session_${UUID.randomUUID()}"
        val candidates = ArrayDeque(listOf(retainedCode, failedCode, failedCode))
        val dao = ReadingPlanTextFileDao { candidates.removeFirst() }
        val retainedPlan = dao.addSessionPlan("Retained plan", testVerseRange())
        val normalPlanCodes = existingPlanCodes(dao)
        val expectedFailure = IllegalStateException("Forced DTO failure")
        var failCandidate = true
        val readingPlanDao = mock(ReadingPlanDao::class.java)
        val readingPlanDatabase = mock(ReadingPlanDatabase::class.java)
        `when`(readingPlanDatabase.readingPlanDao()).thenReturn(readingPlanDao)
        runBlocking {
            `when`(readingPlanDao.getPlan(anyString())).thenAnswer { invocation ->
                if (failCandidate && invocation.getArgument<String>(0) == failedCode) {
                    throw expectedFailure
                }
                null
            }
        }
        val databaseContainer = DatabaseContainer.instance
        val originalDatabase = databaseContainer.readingPlanDb
        databaseContainer.readingPlanDb = readingPlanDatabase

        try {
            val thrown = try {
                dao.addSessionPlan("Failed plan", testVerseRange())
                fail("Expected session-plan creation to fail")
                null
            } catch (e: IllegalStateException) {
                e
            }

            assertSame(expectedFailure, thrown)
            failCandidate = false
            assertEquals(SessionPlanState.EXPIRED, dao.sessionPlanState(failedCode))
            assertLookupFails { dao.getReadingPlanInfoDto(failedCode) }
            assertLookupFails { dao.getReading(failedCode, 1) }
            assertEquals(retainedPlan.planCode, dao.getReadingPlanInfoDto(retainedCode).planCode)
            assertEquals(
                normalPlanCodes + retainedPlan.planCode,
                dao.readingPlanList.map { it.planCode }
            )

            val retriedPlan = dao.addSessionPlan("Retried plan", testVerseRange())

            assertEquals(failedCode, retriedPlan.planCode)
            assertEquals(SessionPlanState.ACTIVE, dao.sessionPlanState(failedCode))
            assertEquals(
                normalPlanCodes + listOf(retainedPlan.planCode, retriedPlan.planCode),
                dao.readingPlanList.map { it.planCode }
            )
            assertTrue(candidates.isEmpty())
        } finally {
            databaseContainer.readingPlanDb = originalDatabase
        }
    }

    private fun assertReadingEquals(expected: VerseRange, reading: OneDaysReadingsDto) {
        assertEquals(1, reading.numReadings)
        val parsedReading = reading.getReadingKey(1)
        assertTrue(parsedReading is VerseRange)
        assertEquals(expected.osisRef, parsedReading.osisRef)
        assertEquals(expected.versification, (parsedReading as VerseRange).versification)
    }

    private fun testVerseRange(): VerseRange {
        val start = Verse(TestData.KJV, BibleBook.JOHN, 3, 16)
        val end = Verse(TestData.KJV, BibleBook.JOHN, 3, 18)
        return VerseRange(TestData.KJV, start, end)
    }

    private fun verseRange(book: BibleBook, chapter: Int, startVerse: Int, endVerse: Int): VerseRange {
        val start = Verse(TestData.KJV, book, chapter, startVerse)
        val end = Verse(TestData.KJV, book, chapter, endVerse)
        return VerseRange(TestData.KJV, start, end)
    }

    private fun assertSessionPlanCreationFails(block: () -> Unit) {
        try {
            block()
            fail("Expected session plan creation to fail")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid plans must not be registered.
        }
    }

    private fun assertLookupFails(block: () -> Unit) {
        try {
            block()
            fail("Expected the rolled-back session plan lookup to fail")
        } catch (_: Exception) {
            // Expected: the rolled-back session definition must not be resolvable from a cache.
        }
    }

    private fun createManualPlan(planCode: String): File {
        // The DAO companion retains the external-storage path from the first Robolectric test sandbox.
        val manualPlanDir = ReadingPlanTextFileDao::class.java
            .getDeclaredField("USER_READING_PLAN_FOLDER")
            .apply { isAccessible = true }
            .get(null) as File
        return File(manualPlanDir, "$planCode.properties").apply {
            parentFile!!.mkdirs()
            writeText("1=Gen.1.1")
        }
    }

    private fun existingPlanCodes(dao: ReadingPlanTextFileDao): List<String> {
        val codes = dao.internalPlanCodes.toMutableList()
        codes += dao.userPlanCodes().orEmpty().filterNot(codes::contains)
        codes += AndBibleAddons.providedReadingPlans.keys.filterNot(codes::contains)
        return codes
    }

    private fun createReadingPlanAddOn(planCode: String): TestAddOn {
        val initials = "SessionPlanTest-${UUID.randomUUID()}"
        val root = File(CommonUtils.tmpDir, initials).apply { mkdirs() }
        val confFile = File(root, "$initials.conf").apply {
            writeText(
                """
                    [$initials]
                    Description=Session plan collision test
                    Category=And Bible
                    ModDrv=RawGenBook
                    DataPath=./
                    AndBibleMinimumVersion=0
                    AndBibleProvidesReadingPlan=$planCode.properties
                """.trimIndent()
            )
        }
        val metadata = SwordBookMetaData(confFile, NetUtil.getURI(root))
        val book = SwordBook(metadata, NullBackend())
        Books.installed().addBook(book)
        AndBibleAddons.clearCaches()
        return TestAddOn(book, root)
    }

    private fun removeReadingPlanAddOn(addOn: TestAddOn) {
        Books.installed().removeBook(addOn.book)
        AndBibleAddons.clearCaches()
        addOn.root.deleteRecursively()
    }

    private data class TestAddOn(val book: Book, val root: File)
}
