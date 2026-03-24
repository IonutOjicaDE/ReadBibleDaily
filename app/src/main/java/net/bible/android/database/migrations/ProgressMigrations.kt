/*
 * Copyright (c) 2024 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
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

package net.bible.android.database.migrations

import androidx.room.migration.Migration

private val addMemorizationTarget = makeMigration(1..2) { db ->
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS MemorizationTarget (
            id BLOB NOT NULL PRIMARY KEY,
            kjvOrdinalStart INTEGER NOT NULL,
            kjvOrdinalEnd INTEGER NOT NULL,
            createdAt INTEGER NOT NULL
        )
    """)
}

private val addGlobalReadingProgressSettings = makeMigration(2..3) { db ->
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS GlobalReadingProgressSettings (
            id BLOB NOT NULL PRIMARY KEY,
            autoTrackReading INTEGER NOT NULL DEFAULT 0,
            autoMarkMemorized INTEGER NOT NULL DEFAULT 1
        )
    """)
}

private val addCustomReadingPlanProgress = makeMigration(3..4) { db ->
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS ChapterReadCounter (
            id BLOB NOT NULL PRIMARY KEY,
            bookOrdinal INTEGER NOT NULL,
            chapter INTEGER NOT NULL,
            readCount INTEGER NOT NULL,
            firstReadAt INTEGER NOT NULL,
            lastReadAt INTEGER NOT NULL,
            source TEXT NOT NULL
        )
    """)
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ChapterReadCounter_bookOrdinal_chapter ON ChapterReadCounter(bookOrdinal, chapter)")

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS WordCountIndexRecord (
            id BLOB NOT NULL PRIMARY KEY,
            moduleInitials TEXT NOT NULL,
            moduleVersion TEXT NOT NULL,
            versification TEXT NOT NULL,
            scope TEXT NOT NULL,
            bookOrdinal INTEGER NOT NULL,
            chapter INTEGER NOT NULL,
            wordCount INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )
    """)
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_WordCountIndexRecord_moduleInitials_versification_bookOrdinal_chapter ON WordCountIndexRecord(moduleInitials, versification, bookOrdinal, chapter)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_WordCountIndexRecord_moduleInitials_updatedAt ON WordCountIndexRecord(moduleInitials, updatedAt)")

    db.execSQL("""
        CREATE TABLE IF NOT EXISTS ReadingPlanChapterProgress (
            id BLOB NOT NULL PRIMARY KEY,
            planId TEXT NOT NULL,
            moduleInitials TEXT NOT NULL,
            bookOrdinal INTEGER NOT NULL,
            chapter INTEGER NOT NULL,
            completionPercent REAL NOT NULL,
            lastReadOrdinal INTEGER,
            chapterAnchor TEXT,
            updatedAt INTEGER NOT NULL
        )
    """)
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ReadingPlanChapterProgress_planId_moduleInitials_bookOrdinal_chapter ON ReadingPlanChapterProgress(planId, moduleInitials, bookOrdinal, chapter)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_ReadingPlanChapterProgress_planId_updatedAt ON ReadingPlanChapterProgress(planId, updatedAt)")
}

private val addModuleIdentityToPlanProgress = makeMigration(4..5) { db ->
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS ReadingPlanChapterProgress_new (
            id BLOB NOT NULL PRIMARY KEY,
            planId TEXT NOT NULL,
            moduleInitials TEXT NOT NULL,
            bookOrdinal INTEGER NOT NULL,
            chapter INTEGER NOT NULL,
            completionPercent REAL NOT NULL,
            lastReadOrdinal INTEGER,
            chapterAnchor TEXT,
            updatedAt INTEGER NOT NULL
        )
    """)
    db.execSQL("""
        INSERT INTO ReadingPlanChapterProgress_new (
            id, planId, moduleInitials, bookOrdinal, chapter, completionPercent, lastReadOrdinal, chapterAnchor, updatedAt
        )
        SELECT id, planId, '', bookOrdinal, chapter, completionPercent, lastReadOrdinal, chapterAnchor, updatedAt
        FROM ReadingPlanChapterProgress
    """)
    db.execSQL("DROP TABLE ReadingPlanChapterProgress")
    db.execSQL("ALTER TABLE ReadingPlanChapterProgress_new RENAME TO ReadingPlanChapterProgress")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ReadingPlanChapterProgress_planId_moduleInitials_bookOrdinal_chapter ON ReadingPlanChapterProgress(planId, moduleInitials, bookOrdinal, chapter)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_ReadingPlanChapterProgress_planId_updatedAt ON ReadingPlanChapterProgress(planId, updatedAt)")
}

val progressMigrations: Array<Migration> = arrayOf(
    addMemorizationTarget,
    addGlobalReadingProgressSettings,
    addCustomReadingPlanProgress,
    addModuleIdentityToPlanProgress,
)
