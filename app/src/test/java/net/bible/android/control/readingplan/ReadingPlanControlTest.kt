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

package net.bible.android.control.readingplan

import android.content.res.Resources
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import net.bible.android.BibleApplication
import net.bible.android.TEST_SDK
import net.bible.android.TestBibleApplication
import net.bible.android.control.versification.TestData
import net.bible.android.database.IdType
import net.bible.android.database.ReadingPlanDatabase
import net.bible.android.database.readingplan.ReadingPlanDao
import net.bible.android.database.readingplan.ReadingPlanEntities.ReadingPlan
import net.bible.android.database.readingplan.ReadingPlanEntities.ReadingPlanStatus
import net.bible.android.view.activity.base.ActivityBase
import net.bible.android.view.activity.base.CurrentActivityHolder
import net.bible.service.common.AndBibleAddons
import net.bible.service.common.CommonUtils
import net.bible.service.db.DatabaseContainer
import net.bible.service.db.readingplan.ReadingPlanRepository
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(application = TestBibleApplication::class, sdk = [TEST_SDK])
class ReadingPlanControlTest {

    private lateinit var databaseContainer: DatabaseContainer
    private lateinit var originalReadingPlanDatabase: ReadingPlanDatabase
    private lateinit var readingPlanRepository: ReadingPlanRepository
    private lateinit var readingPlanControl: ReadingPlanControl

    @Before
    fun setUp() {
        databaseContainer = DatabaseContainer.instance
        originalReadingPlanDatabase = databaseContainer.readingPlanDb
        val readingPlanDatabase = mock(ReadingPlanDatabase::class.java)
        `when`(readingPlanDatabase.readingPlanDao()).thenReturn(InMemoryReadingPlanDao())
        databaseContainer.readingPlanDb = readingPlanDatabase
        readingPlanRepository = ReadingPlanRepository()
        readingPlanControl = ReadingPlanControl(
            CommonUtils.speakControl,
            CommonUtils.windowControl,
            readingPlanRepository,
        )
        readingPlanControl.setReadingPlan("")
    }

    @After
    fun tearDown() {
        if (::readingPlanControl.isInitialized) {
            readingPlanControl.setReadingPlan("")
            readingPlanControl.destroy()
        }
        if (::originalReadingPlanDatabase.isInitialized) {
            databaseContainer.readingPlanDb = originalReadingPlanDatabase
        }
        if (::readingPlanRepository.isInitialized) {
            DatabaseResetter.resetDatabase(readingPlanRepository.scope)
        } else {
            DatabaseResetter.resetDatabase()
        }
    }

    @Test
    fun `live session plan exists through normal control flow`() {
        val plan = readingPlanControl.createSessionPlan("Session plan", testVerseRange())

        readingPlanControl.startReadingPlan(plan)

        assertTrue(readingPlanControl.currentPlanExists)
    }

    @Test
    fun `classification failure falls back to successful generic lookup without cleanup`() {
        val planCode = "session_addon_${UUID.randomUUID()}"
        val addOn = createReadingPlanAddOn(planCode)
        val startDate = Date(4_000_000)
        val activity = mock(ActivityBase::class.java)
        val resources = mock(Resources::class.java)
        val realAssets = BibleApplication.application.resources.assets
        `when`(activity.resources).thenReturn(resources)
        `when`(resources.assets).thenReturn(realAssets)
        CurrentActivityHolder.activate(activity)

        try {
            `when`(resources.assets)
                .thenAnswer { throw IOException("classification failure") }
                .thenReturn(realAssets)
            readingPlanControl.setReadingPlan(planCode)
            seedRepositoryState(planCode, startDate, 6, "classification-status")

            assertTrue(readingPlanControl.currentPlanExists)
            awaitRepositoryWork()

            assertEquals(planCode, readingPlanControl.currentPlanCode)
            assertEquals(startDate, readingPlanRepository.getStartDate(planCode))
            assertEquals(6, readingPlanRepository.getCurrentDay(planCode))
            assertEquals("classification-status", readingPlanRepository.getReadingStatus(planCode, 6))
        } finally {
            CurrentActivityHolder.deactivate(activity)
            removeReadingPlanAddOn(addOn)
        }
    }

    @Test
    fun `classification and generic lookup failures return false without cleanup`() {
        val planCode = "session_missing_${UUID.randomUUID()}"
        val startDate = Date(5_000_000)
        val activity = mock(ActivityBase::class.java)
        val resources = mock(Resources::class.java)
        val realAssets = BibleApplication.application.resources.assets
        `when`(activity.resources).thenReturn(resources)
        `when`(resources.assets).thenReturn(realAssets)
        CurrentActivityHolder.activate(activity)

        try {
            `when`(resources.assets)
                .thenAnswer { throw IOException("classification failure") }
                .thenAnswer { throw IOException("generic lookup failure") }
            readingPlanControl.setReadingPlan(planCode)
            seedRepositoryState(planCode, startDate, 7, "classification-status")

            assertFalse(readingPlanControl.currentPlanExists)
            awaitRepositoryWork()

            assertEquals(planCode, readingPlanControl.currentPlanCode)
            assertEquals(startDate, readingPlanRepository.getStartDate(planCode))
            assertEquals(7, readingPlanRepository.getCurrentDay(planCode))
            assertEquals("classification-status", readingPlanRepository.getReadingStatus(planCode, 7))
        } finally {
            CurrentActivityHolder.deactivate(activity)
        }
    }

    @Test
    fun `expired selected session plan is cleared without removing unrelated state`() {
        val expiredPlanCode = "session_${UUID.randomUUID()}"
        val unrelatedPlanCode = "unrelated_${UUID.randomUUID()}"
        val expiredStartDate = Date(1_000_000)
        val unrelatedStartDate = Date(2_000_000)
        readingPlanControl.setReadingPlan(expiredPlanCode)
        seedRepositoryState(expiredPlanCode, expiredStartDate, 3, "expired-status")
        seedRepositoryState(unrelatedPlanCode, unrelatedStartDate, 4, "unrelated-status")

        assertFalse(readingPlanControl.currentPlanExists)
        awaitRepositoryWork()

        assertEquals("", readingPlanControl.currentPlanCode)
        assertNull(readingPlanRepository.getStartDate(expiredPlanCode))
        assertEquals(1, readingPlanRepository.getCurrentDay(expiredPlanCode))
        assertNull(readingPlanRepository.getReadingStatus(expiredPlanCode, 3))
        assertEquals(unrelatedStartDate, readingPlanRepository.getStartDate(unrelatedPlanCode))
        assertEquals(4, readingPlanRepository.getCurrentDay(unrelatedPlanCode))
        assertEquals("unrelated-status", readingPlanRepository.getReadingStatus(unrelatedPlanCode, 4))
    }

    @Test
    fun `unrelated missing plan does not trigger session cleanup`() {
        val missingPlanCode = "missing_${UUID.randomUUID()}"
        val startDate = Date(3_000_000)
        readingPlanControl.setReadingPlan(missingPlanCode)
        seedRepositoryState(missingPlanCode, startDate, 5, "missing-status")

        assertFalse(readingPlanControl.currentPlanExists)
        awaitRepositoryWork()

        assertEquals(missingPlanCode, readingPlanControl.currentPlanCode)
        assertEquals(startDate, readingPlanRepository.getStartDate(missingPlanCode))
        assertEquals(5, readingPlanRepository.getCurrentDay(missingPlanCode))
        assertEquals("missing-status", readingPlanRepository.getReadingStatus(missingPlanCode, 5))
    }

    private fun seedRepositoryState(planCode: String, startDate: Date, day: Int, status: String) {
        readingPlanRepository.startPlan(planCode, startDate)
        runBlocking {
            readingPlanRepository.setCurrentDay(planCode, day).join()
            readingPlanRepository.setReadingStatus(planCode, day, status).join()
        }
    }

    private fun awaitRepositoryWork() = runBlocking {
        readingPlanRepository.scope.coroutineContext[Job]?.children?.toList()?.joinAll()
    }

    private fun testVerseRange(): VerseRange {
        val start = Verse(TestData.KJV, BibleBook.JOHN, 3, 16)
        val end = Verse(TestData.KJV, BibleBook.JOHN, 3, 18)
        return VerseRange(TestData.KJV, start, end)
    }

    private fun createReadingPlanAddOn(planCode: String): TestAddOn {
        val initials = "ReadingPlanControlTest-${UUID.randomUUID()}"
        val root = File(CommonUtils.tmpDir, initials).apply { mkdirs() }
        File(root, "$planCode.properties").writeText("1=Gen.1.1")
        val confFile = File(root, "$initials.conf").apply {
            writeText(
                """
                    [$initials]
                    Description=Reading plan control test
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

    private class InMemoryReadingPlanDao : ReadingPlanDao {
        private val plans = ConcurrentHashMap<String, ReadingPlan>()
        private val statuses = ConcurrentHashMap<Pair<String, Int>, ReadingPlanStatus>()

        override suspend fun getStatus(planCode: String, planDay: Int): ReadingPlanStatus? =
            statuses[planCode to planDay]

        override suspend fun deleteStatusesBeforeDay(planCode: String, planDay: Int) {
            statuses.keys.removeIf { (code, day) -> code == planCode && day < planDay }
        }

        override suspend fun deleteStatusesForPlan(planCode: String) {
            statuses.keys.removeIf { (code, _) -> code == planCode }
        }

        override suspend fun addPlanStatus(
            planCode: String,
            planDay: Int,
            readingStatus: String,
            id: IdType,
        ) {
            statuses[planCode to planDay] = ReadingPlanStatus(planCode, planDay, readingStatus, id)
        }

        override suspend fun getPlan(planCode: String): ReadingPlan? = plans[planCode]?.copy()

        override suspend fun updatePlan(
            planCode: String,
            planStartDate: Date,
            planCurrentDay: Int,
            id: IdType,
        ) {
            plans[planCode] = ReadingPlan(planCode, planStartDate, planCurrentDay, id)
        }

        override suspend fun deletePlanInfo(planCode: String) {
            plans.remove(planCode)
        }
    }

    private data class TestAddOn(val book: Book, val root: File)
}
