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
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanSelectionPlaceholderActivityBinding
import net.bible.android.activity.databinding.CustomReadingPlanTreeItemBinding
import net.bible.android.view.activity.base.ActivityBase

private data class TreeRowRenderModel(
    val visibleNode: VisibleCustomReadingPlanTreeNode,
    val selectionState: CustomReadingPlanSelectionState,
    val isExpanded: Boolean,
)

private class TreeRowViewHolder(val binding: CustomReadingPlanTreeItemBinding) : RecyclerView.ViewHolder(binding.root)

class CustomReadingPlanSelectionPlaceholderActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlanSelectionPlaceholderActivityBinding
    private lateinit var treeAdapter: CustomReadingPlanTreeAdapter
    private var treeNodes: List<CustomReadingPlanTreeNode> = emptyList()
    private var visibleRows: List<TreeRowRenderModel> = emptyList()
    private var expandedKeys: MutableSet<String> = mutableSetOf()
    private var pendingSelection: Set<String> = emptySet()
    private var treeLoading = false
    private lateinit var treeIndex: CustomReadingPlanTreeIndex
    private lateinit var initialSelection: CustomReadingPlanSelection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanSelectionPlaceholderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.custom_reading_plan_selection_title)

        initialSelection = savedInstanceState?.getSerializable(STATE_INITIAL_SELECTION) as? CustomReadingPlanSelection
            ?: intent.getSerializableExtra(EXTRA_SELECTION) as? CustomReadingPlanSelection
            ?: CustomReadingPlanSelection()
        pendingSelection = savedInstanceState?.getStringArrayList(STATE_PENDING_SELECTION)?.toSet()
            ?: initialSelection.selectedNodeKeys

        setupList()
        renderSummary()
        setupButtons()
        loadTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(STATE_INITIAL_SELECTION, initialSelection)
        outState.putStringArrayList(STATE_PENDING_SELECTION, ArrayList(pendingSelection))
    }

    override fun onBackPressed() {
        confirmSelection()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            confirmSelection()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupList() {
        treeAdapter = CustomReadingPlanTreeAdapter(
            onExpandToggle = ::toggleExpanded,
            onSelectionToggle = ::toggleSelection,
        )
        binding.selectionTree.apply {
            layoutManager = LinearLayoutManager(this@CustomReadingPlanSelectionPlaceholderActivity)
            adapter = treeAdapter
        }
    }

    private fun setupButtons() = binding.apply {
        cancelButton.setOnClickListener { cancelSelection() }
        confirmButton.setOnClickListener { confirmSelection() }
    }

    private fun loadTree() {
        updateLoadingState(true)
        lifecycleScope.launch {
            val loadedNodes = withContext(Dispatchers.Default) {
                CustomReadingPlanTreeFactory.build(this@CustomReadingPlanSelectionPlaceholderActivity)
            }
            treeNodes = loadedNodes
            treeIndex = CustomReadingPlanTreeSelection.buildIndex(treeNodes)
            expandedKeys = CustomReadingPlanExpansionStateStore.loadAndPrune(treeIndex.validKeys).toMutableSet()
            pendingSelection = pendingSelection.intersect(treeIndex.validKeys)
            updateLoadingState(false)
            refreshVisibleRows()
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        treeLoading = isLoading
        binding.loadingState.isVisible = isLoading
        binding.selectionTree.isVisible = !isLoading && visibleRows.isNotEmpty()
        binding.emptyState.isVisible = !isLoading && visibleRows.isEmpty()
        binding.confirmButton.isEnabled = !isLoading
    }

    private fun refreshVisibleRows() {
        if (!::treeIndex.isInitialized) return
        visibleRows = CustomReadingPlanTreeSelection.flattenVisible(treeNodes, expandedKeys)
            .map { visibleNode ->
                TreeRowRenderModel(
                    visibleNode = visibleNode,
                    selectionState = CustomReadingPlanTreeSelection.selectionState(visibleNode.node, pendingSelection, treeIndex),
                    isExpanded = visibleNode.node.key in expandedKeys,
                )
            }
        treeAdapter.submit(visibleRows)
        binding.emptyState.isVisible = !treeLoading && visibleRows.isEmpty()
        binding.selectionTree.isVisible = !treeLoading && visibleRows.isNotEmpty()
        renderSummary()
    }

    private fun renderSummary() {
        val selection = CustomReadingPlanSelection(pendingSelection)
        binding.placeholderTitle.text = getString(
            R.string.custom_reading_plan_selection_placeholder_title,
            intent.getStringExtra(EXTRA_PLAN_TITLE).orEmpty(),
        )
        binding.placeholderSummary.text = CustomReadingPlanSelectionSummaryFormatter.format(this, selection, treeNodes)
    }

    private fun toggleExpanded(node: CustomReadingPlanTreeNode) {
        if (!::treeIndex.isInitialized) return
        if (!node.isExpandable) return
        if (!expandedKeys.add(node.key)) {
            expandedKeys.remove(node.key)
        }
        CustomReadingPlanExpansionStateStore.persist(expandedKeys, treeIndex.validKeys)
        refreshVisibleRows()
    }

    private fun toggleSelection(node: CustomReadingPlanTreeNode, checked: Boolean) {
        if (!::treeIndex.isInitialized) return
        pendingSelection = CustomReadingPlanTreeSelection.setSelected(node, pendingSelection, checked, treeIndex)
        refreshVisibleRows()
    }

    private fun confirmSelection() {
        val selection = CustomReadingPlanSelection(pendingSelection)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_RESULT_SELECTION, selection)
            putExtra(
                EXTRA_RESULT_SELECTION_SUMMARY,
                CustomReadingPlanSelectionSummaryFormatter.format(this@CustomReadingPlanSelectionPlaceholderActivity, selection, treeNodes),
            )
        })
        finish()
    }

    private fun cancelSelection() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private inner class CustomReadingPlanTreeAdapter(
        private val onExpandToggle: (CustomReadingPlanTreeNode) -> Unit,
        private val onSelectionToggle: (CustomReadingPlanTreeNode, Boolean) -> Unit,
    ) : ListAdapter<TreeRowRenderModel, TreeRowViewHolder>(TreeRowDiffCallback) {

        fun submit(rows: List<TreeRowRenderModel>) {
            submitList(rows)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TreeRowViewHolder {
            val binding = CustomReadingPlanTreeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TreeRowViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TreeRowViewHolder, position: Int) {
            val item = getItem(position)
            holder.binding.apply {
                val node = item.visibleNode.node
                val indent = root.resources.getDimensionPixelSize(R.dimen.custom_reading_plan_tree_indent_step)
                contentContainer.setPaddingRelative(item.visibleNode.depth * indent, contentContainer.paddingTop, contentContainer.paddingEnd, contentContainer.paddingBottom)
                expandButton.isVisible = node.isExpandable
                expandButton.setImageResource(if (item.isExpanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24)
                expandButton.setOnClickListener { onExpandToggle(node) }
                label.text = node.label
                checkbox.setOnCheckedChangeListener(null)
                checkbox.checkedState = when (item.selectionState) {
                    CustomReadingPlanSelectionState.CHECKED -> MaterialCheckBox.STATE_CHECKED
                    CustomReadingPlanSelectionState.PARTIAL -> MaterialCheckBox.STATE_INDETERMINATE
                    CustomReadingPlanSelectionState.UNCHECKED -> MaterialCheckBox.STATE_UNCHECKED
                }
                checkbox.setOnClickListener {
                    val shouldCheck = item.selectionState != CustomReadingPlanSelectionState.CHECKED
                    onSelectionToggle(node, shouldCheck)
                }
                root.setOnClickListener {
                    if (node.isExpandable) onExpandToggle(node) else onSelectionToggle(node, item.selectionState != CustomReadingPlanSelectionState.CHECKED)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PLAN_TITLE = "plan_title"
        const val EXTRA_SELECTION_SUMMARY = "selection_summary"
        const val EXTRA_SELECTION = "selection"
        const val EXTRA_RESULT_SELECTION = "result_selection"
        const val EXTRA_RESULT_SELECTION_SUMMARY = "result_selection_summary"
        private const val STATE_INITIAL_SELECTION = "initial_selection"
        private const val STATE_PENDING_SELECTION = "pending_selection"
    }
}

private object TreeRowDiffCallback : DiffUtil.ItemCallback<TreeRowRenderModel>() {
    override fun areItemsTheSame(oldItem: TreeRowRenderModel, newItem: TreeRowRenderModel): Boolean =
        oldItem.visibleNode.node.key == newItem.visibleNode.node.key

    override fun areContentsTheSame(oldItem: TreeRowRenderModel, newItem: TreeRowRenderModel): Boolean =
        oldItem == newItem
}
