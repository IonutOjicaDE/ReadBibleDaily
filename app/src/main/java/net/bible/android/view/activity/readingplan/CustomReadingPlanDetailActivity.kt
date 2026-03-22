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
import net.bible.android.activity.databinding.CustomReadingPlanDetailActivityBinding
import net.bible.android.view.activity.base.ActivityBase

class CustomReadingPlanDetailActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlanDetailActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanDetailActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val planTitle = intent.getStringExtra(EXTRA_PLAN_TITLE).orEmpty()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = planTitle

        binding.title.text = planTitle
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
    }
}
