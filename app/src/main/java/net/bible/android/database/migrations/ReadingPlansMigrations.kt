/*
 * Copyright (c) 2023-2024 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
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

private val rpTriggers_1_2 = makeMigration(1..2) { db ->
    db.execSQL(
        """
            CREATE TABLE IF NOT EXISTS `CustomReadingPlan` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `selectionSummary` TEXT NOT NULL,
                `selectionJson` TEXT NOT NULL,
                `minutesPerSession` INTEGER NOT NULL,
                `periodInDays` INTEGER NOT NULL,
                `selectedDaysMask` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent()
    )
}

private val rpTriggers_2_3 = makeMigration(2..3) { db ->
    db.execSQL("ALTER TABLE `CustomReadingPlan` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("UPDATE `CustomReadingPlan` SET `sortOrder` = rowid")
}

val readingPlanMigrations: Array<Migration> = arrayOf(
    rpTriggers_1_2,
    rpTriggers_2_3,
)

const val READING_PLAN_DATABASE_VERSION = 3
