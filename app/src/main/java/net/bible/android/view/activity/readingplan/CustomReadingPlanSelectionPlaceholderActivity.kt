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
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.bible.android.activity.R
import net.bible.android.activity.databinding.CustomReadingPlanSelectionPlaceholderActivityBinding
import net.bible.android.activity.databinding.CustomReadingPlanTreeItemBinding
import net.bible.android.view.activity.base.ActivityBase
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook

private data class TreeRowRenderModel(
    val visibleNode: VisibleCustomReadingPlanTreeNode,
    val selectionState: CustomReadingPlanSelectionState,
    val isExpanded: Boolean,
    val readState: ReadStateUiModel? = null,
)

private data class ReadStateUiModel(
    val isRead: Boolean,
    val isEnabled: Boolean,
)

private class TreeRowViewHolder(val binding: CustomReadingPlanTreeItemBinding) : RecyclerView.ViewHolder(binding.root)

class CustomReadingPlanSelectionPlaceholderActivity : ActivityBase() {
    private lateinit var binding: CustomReadingPlanSelectionPlaceholderActivityBinding
    private lateinit var treeAdapter: CustomReadingPlanTreeAdapter
    private var treeNodes: List<CustomReadingPlanTreeNode> = emptyList()
    private var visibleRows: List<TreeRowRenderModel> = emptyList()
    private var expandedKeys: MutableSet<String> = mutableSetOf()
    private var pendingSelection: Set<String> = emptySet()
    private lateinit var initialSelection: CustomReadingPlanSelection
    private var planId: String? = null
    private var readKeys: Set<CanonicalChapterKey> = emptySet()
    private var nodeChapterKeysByNode: Map<String, List<CanonicalChapterKey>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanSelectionPlaceholderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.custom_reading_plan_selection_title)

        initialSelection = savedInstanceState?.getSerializable(STATE_INITIAL_SELECTION) as? CustomReadingPlanSelection
            ?: intent.getSerializableExtra(EXTRA_SELECTION) as? CustomReadingPlanSelection
            ?: CustomReadingPlanSelection()
        planId = intent.getStringExtra(EXTRA_PLAN_ID)
        pendingSelection = savedInstanceState?.getStringArrayList(STATE_PENDING_SELECTION)?.toSet()
            ?: initialSelection.selectedNodeKeys

        treeNodes = CustomReadingPlanTreeFactory.build(this)
        nodeChapterKeysByNode = buildNodeChapterKeysByNode(treeNodes)
        val validKeys = CustomReadingPlanTreeSelection.validNodeKeys(treeNodes)
        expandedKeys = CustomReadingPlanExpansionStateStore.loadAndPrune(validKeys).toMutableSet()
        pendingSelection = pendingSelection.intersect(validKeys)

        setupList()
        renderSummary()
        refreshVisibleRows()
        setupButtons()
        loadReadState()
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
            onReadToggle = ::toggleRead,
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

    private fun refreshVisibleRows() {
        visibleRows = CustomReadingPlanTreeSelection.flattenVisible(treeNodes, expandedKeys)
            .map { visibleNode ->
                val selectionState = CustomReadingPlanTreeSelection.selectionState(visibleNode.node, pendingSelection)
                TreeRowRenderModel(
                    visibleNode = visibleNode,
                    selectionState = selectionState,
                    isExpanded = visibleNode.node.key in expandedKeys,
                    readState = visibleNode.node.toReadState(selectionState),
                )
            }
        treeAdapter.submit(visibleRows)
        binding.emptyState.isVisible = visibleRows.isEmpty()
        binding.selectionTree.isVisible = visibleRows.isNotEmpty()
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
        if (!node.isExpandable) return
        if (!expandedKeys.add(node.key)) {
            expandedKeys.remove(node.key)
        }
        CustomReadingPlanExpansionStateStore.persist(expandedKeys, CustomReadingPlanTreeSelection.validNodeKeys(treeNodes))
        refreshVisibleRows()
    }

    private fun toggleSelection(node: CustomReadingPlanTreeNode, checked: Boolean) {
        pendingSelection = CustomReadingPlanTreeSelection.setSelected(node, pendingSelection, checked)
        refreshVisibleRows()
    }

    private fun toggleRead(node: CustomReadingPlanTreeNode, read: Boolean) {
        val activePlanId = planId ?: return
        val chapterKeys = nodeChapterKeysByNode[node.key].orEmpty()
        if (chapterKeys.isEmpty()) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                chapterKeys
                    .map { it.bookOrdinal }
                    .distinct()
                    .forEach { bookOrdinal ->
                        CustomPlanChapterStateService.markBookRead(
                            planId = activePlanId,
                            bookOrdinal = bookOrdinal,
                            isRead = read,
                        )
                    }
            }
            loadReadState()
        }
    }

    private fun loadReadState() {
        val activePlanId = planId ?: run {
            readKeys = emptySet()
            refreshVisibleRows()
            return
        }

        lifecycleScope.launch {
            val chapterStates = withContext(Dispatchers.IO) {
                CustomPlanChapterStateService.loadPlanChapterStates(activePlanId)
            }
            readKeys = chapterStates.filter { it.isRead }.map { it.key }.toSet()
            refreshVisibleRows()
        }
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
        private val onReadToggle: (CustomReadingPlanTreeNode, Boolean) -> Unit,
    ) : RecyclerView.Adapter<TreeRowViewHolder>() {
        private val items = mutableListOf<TreeRowRenderModel>()

        fun submit(rows: List<TreeRowRenderModel>) {
            items.clear()
            items += rows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TreeRowViewHolder {
            val binding = CustomReadingPlanTreeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TreeRowViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: TreeRowViewHolder, position: Int) {
            val item = items[position]
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

                readStateToggle.isVisible = item.readState != null
                item.readState?.let { readState ->
                    readStateToggle.setOnCheckedChangeListener(null)
                    readStateToggle.isChecked = readState.isRead
                    readStateToggle.isEnabled = readState.isEnabled
                    readStateToggle.buttonTintList = readToggleTint()
                    readStateToggle.setOnClickListener { onReadToggle(node, !readState.isRead) }
                    label.alpha = if (readState.isRead) 0.55f else 1f
                } ?: run {
                    label.alpha = 1f
                    readStateToggle.setOnClickListener(null)
                }

                root.setOnClickListener {
                    if (node.isExpandable) onExpandToggle(node) else onSelectionToggle(node, item.selectionState != CustomReadingPlanSelectionState.CHECKED)
                }
            }
        }

        private fun readToggleTint(): ColorStateList {
            val checkedColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary, 0xFF2E7D32.toInt())
            val uncheckedColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF757575.toInt())
            return ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(checkedColor, uncheckedColor),
            )
        }
    }

    private fun CustomReadingPlanTreeNode.toReadState(selectionState: CustomReadingPlanSelectionState): ReadStateUiModel? {
        val chapterKeys = nodeChapterKeysByNode[key].orEmpty()
        if (chapterKeys.isEmpty()) return null
        val isRead = chapterKeys.all { it in readKeys }
        return ReadStateUiModel(
            isRead = isRead,
            isEnabled = selectionState != CustomReadingPlanSelectionState.UNCHECKED && planId != null,
        )
    }

    private fun buildNodeChapterKeysByNode(nodes: List<CustomReadingPlanTreeNode>): Map<String, List<CanonicalChapterKey>> {
        val map = mutableMapOf<String, List<CanonicalChapterKey>>()

        fun visit(node: CustomReadingPlanTreeNode): List<CanonicalChapterKey> {
            val keys = if (node.type == CustomReadingPlanNodeType.BIBLE_BOOK) {
                val moduleInitials = node.key.split(':').getOrNull(1)
                val bookName = node.key.split(':').lastOrNull()
                val bibleBook = runCatching { BibleBook.valueOf(bookName.orEmpty()) }.getOrNull()
                val document = moduleInitials?.let { SwordDocumentFacade.getDocumentByInitials(it) as? AbstractPassageBook }
                if (bibleBook == null || document == null) {
                    emptyList()
                } else {
                    val chapterCount = document.versification.getLastChapter(bibleBook)
                    (1..chapterCount).map { chapter ->
                        CanonicalChapterKey(bookOrdinal = bibleBook.ordinal, chapter = chapter)
                    }
                }
            } else {
                node.children.flatMap { child -> visit(child) }
            }.distinct().sortedWith(compareBy({ it.bookOrdinal }, { it.chapter }))

            map[node.key] = keys
            return keys
        }

        nodes.forEach { visit(it) }
        return map
    }

    companion object {
        const val EXTRA_PLAN_TITLE = "plan_title"
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_SELECTION_SUMMARY = "selection_summary"
        const val EXTRA_SELECTION = "selection"
        const val EXTRA_RESULT_SELECTION = "result_selection"
        const val EXTRA_RESULT_SELECTION_SUMMARY = "result_selection_summary"
        private const val STATE_INITIAL_SELECTION = "initial_selection"
        private const val STATE_PENDING_SELECTION = "pending_selection"
    }
}
