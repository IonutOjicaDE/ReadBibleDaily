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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanListItemBinding
import net.bible.android.activity.databinding.CustomReadingPlansActivityBinding
import net.bible.android.view.activity.base.ActivityBase

private const val STATE_PLAN_TITLES = "plan_titles"
private const val STATE_PLAN_ACTIVE = "plan_active"

private data class CustomReadingPlanListItem(
    val title: String,
    val description: String? = null,
    var isActive: Boolean = true,
    val hasToggle: Boolean = true,
    val opensAsCreate: Boolean = false,
)

private class CustomReadingPlanViewHolder(
    val binding: CustomReadingPlanListItemBinding,
) : RecyclerView.ViewHolder(binding.root)

class CustomReadingPlansActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlansActivityBinding
    private val planItems = mutableListOf<CustomReadingPlanListItem>()
    private lateinit var adapter: CustomReadingPlanAdapter

    private inner class CustomReadingPlanAdapter : RecyclerView.Adapter<CustomReadingPlanViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomReadingPlanViewHolder {
            val binding = CustomReadingPlanListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return CustomReadingPlanViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CustomReadingPlanViewHolder, position: Int) {
            bindItem(holder.binding, planItems[position])
        }

        override fun getItemCount(): Int = planItems.size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlansActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = CustomReadingPlanAdapter()
        planItems.clear()
        planItems.addAll(buildInitialPlans(savedInstanceState))

        binding.recyclerView.apply {
            val linearLayoutManager = LinearLayoutManager(this@CustomReadingPlansActivity)
            layoutManager = linearLayoutManager
            adapter = this@CustomReadingPlansActivity.adapter
            setHasFixedSize(false)
            if (itemDecorationCount == 0) {
                addItemDecoration(DividerItemDecoration(context, linearLayoutManager.orientation))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_PLAN_TITLES,
            ArrayList(planItems.filter { it.hasToggle }.map { it.title })
        )
        outState.putBooleanArray(
            STATE_PLAN_ACTIVE,
            planItems.filter { it.hasToggle }.map { it.isActive }.toBooleanArray()
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun bindItem(
        itemBinding: CustomReadingPlanListItemBinding,
        item: CustomReadingPlanListItem,
    ) = itemBinding.run {
        title.text = item.title
        summary.text = item.description
        summary.visibility = if (item.description.isNullOrBlank()) View.GONE else View.VISIBLE

        configureToggle(toggle, item)

        root.setOnClickListener {
            openPlan(item)
        }
    }

    private fun configureToggle(toggle: SwitchCompat, item: CustomReadingPlanListItem) {
        if (!item.hasToggle) {
            toggle.visibility = View.GONE
            toggle.setOnCheckedChangeListener(null)
            return
        }

        toggle.visibility = View.VISIBLE
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = item.isActive
        toggle.setOnCheckedChangeListener { _, isChecked ->
            item.isActive = isChecked
        }
        toggle.setOnClickListener {
            item.isActive = toggle.isChecked
        }
    }

    private fun buildInitialPlans(savedInstanceState: Bundle?): List<CustomReadingPlanListItem> {
        val storedTitles = savedInstanceState?.getStringArrayList(STATE_PLAN_TITLES)
        val storedActive = savedInstanceState?.getBooleanArray(STATE_PLAN_ACTIVE)
        val defaultTitles = listOf(
            getString(R.string.custom_reading_plan_new_testament),
            getString(R.string.custom_reading_plan_old_testament),
        )

        val titles = storedTitles ?: ArrayList(defaultTitles)
        val persistedPlans = titles.mapIndexed { index, title ->
            CustomReadingPlanListItem(
                title = title,
                description = getString(R.string.custom_reading_plan_default_summary),
                isActive = storedActive?.getOrNull(index) ?: true,
            )
        }
        return persistedPlans + createItem()
    }

    private fun createItem() = CustomReadingPlanListItem(
        title = getString(R.string.custom_reading_plan_create),
        hasToggle = false,
        opensAsCreate = true,
    )

    private fun openPlan(item: CustomReadingPlanListItem) {
        val intent = Intent(this, CustomReadingPlanDetailActivity::class.java).apply {
            putExtra(
                CustomReadingPlanDetailActivity.EXTRA_PLAN_TITLE,
                if (item.opensAsCreate) {
                    getString(R.string.custom_reading_plan_new_title)
                } else {
                    item.title
                }
            )
        }
        startActivity(intent)
    }
}
