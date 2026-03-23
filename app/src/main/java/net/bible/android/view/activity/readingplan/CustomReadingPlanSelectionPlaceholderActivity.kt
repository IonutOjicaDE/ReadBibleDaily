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

package net.bible.android.view.activity.readingplan

import android.os.Bundle
import android.view.MenuItem
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanSelectionPlaceholderActivityBinding
import net.bible.android.view.activity.base.ActivityBase

class CustomReadingPlanSelectionPlaceholderActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlanSelectionPlaceholderActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanSelectionPlaceholderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.custom_reading_plan_selection_title)

        val planTitle = intent.getStringExtra(EXTRA_PLAN_TITLE).orEmpty()
        val selectionSummary = intent.getStringExtra(EXTRA_SELECTION_SUMMARY).orEmpty()
        binding.placeholderTitle.text = getString(R.string.custom_reading_plan_selection_placeholder_title, planTitle)
        binding.placeholderSummary.text = getString(R.string.custom_reading_plan_selection_placeholder_summary, selectionSummary)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PLAN_TITLE = "plan_title"
        const val EXTRA_SELECTION_SUMMARY = "selection_summary"
    }
}
