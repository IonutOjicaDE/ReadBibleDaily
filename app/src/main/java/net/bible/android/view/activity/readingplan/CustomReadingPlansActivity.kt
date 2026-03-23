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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanListItemBinding
import net.bible.android.activity.databinding.CustomReadingPlansActivityBinding
import net.bible.android.view.activity.base.ActivityBase

private data class CustomReadingPlanListRow(
    val plan: CustomReadingPlan?,
    val title: String,
    val description: String? = null,
    val hasToggle: Boolean = true,
    val opensAsCreate: Boolean = false,
)

private class CustomReadingPlanViewHolder(
    val binding: CustomReadingPlanListItemBinding,
) : RecyclerView.ViewHolder(binding.root)

class CustomReadingPlansActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlansActivityBinding
    private val planItems = mutableListOf<CustomReadingPlanListRow>()
    private lateinit var adapter: CustomReadingPlanAdapter

    private val editPlanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPlanItems()
    }

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
        CustomReadingPlanInMemoryRepository.initialize(this)

        adapter = CustomReadingPlanAdapter()
        binding.recyclerView.apply {
            val linearLayoutManager = LinearLayoutManager(this@CustomReadingPlansActivity)
            layoutManager = linearLayoutManager
            adapter = this@CustomReadingPlansActivity.adapter
            setHasFixedSize(false)
            if (itemDecorationCount == 0) {
                addItemDecoration(DividerItemDecoration(context, linearLayoutManager.orientation))
            }
        }
        refreshPlanItems()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshPlanItems() {
        planItems.clear()
        planItems += CustomReadingPlanInMemoryRepository.getPlans().map { plan ->
            CustomReadingPlanListRow(
                plan = plan,
                title = plan.title,
                description = plan.listSummary(this),
            )
        }
        planItems += createItem()
        adapter.notifyDataSetChanged()
    }

    private fun bindItem(
        itemBinding: CustomReadingPlanListItemBinding,
        item: CustomReadingPlanListRow,
    ) = itemBinding.run {
        title.text = item.title
        summary.text = item.description
        summary.visibility = if (item.description.isNullOrBlank()) View.GONE else View.VISIBLE

        configureToggle(toggle, item)
        root.setOnClickListener { openPlan(item) }
    }

    private fun configureToggle(toggle: SwitchCompat, item: CustomReadingPlanListRow) {
        if (!item.hasToggle) {
            toggle.visibility = View.GONE
            toggle.setOnCheckedChangeListener(null)
            return
        }

        val plan = item.plan ?: return
        toggle.visibility = View.VISIBLE
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = plan.isActive
        toggle.setOnCheckedChangeListener { _, isChecked ->
            CustomReadingPlanInMemoryRepository.updateActive(plan.id, isChecked)
        }
        toggle.setOnClickListener {
            CustomReadingPlanInMemoryRepository.updateActive(plan.id, toggle.isChecked)
        }
    }

    private fun createItem() = CustomReadingPlanListRow(
        plan = null,
        title = getString(R.string.custom_reading_plan_create),
        hasToggle = false,
        opensAsCreate = true,
    )

    private fun openPlan(item: CustomReadingPlanListRow) {
        val intent = Intent(this, CustomReadingPlanDetailActivity::class.java).apply {
            if (item.opensAsCreate) {
                putExtra(CustomReadingPlanDetailActivity.EXTRA_IS_NEW_PLAN, true)
            } else {
                putExtra(CustomReadingPlanDetailActivity.EXTRA_PLAN_ID, item.plan?.id)
            }
        }
        editPlanLauncher.launch(intent)
    }
}
