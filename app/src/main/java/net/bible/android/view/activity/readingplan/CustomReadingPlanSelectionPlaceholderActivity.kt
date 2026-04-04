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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
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
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook

private data class TreeRowRenderModel(
    val visibleNode: VisibleCustomReadingPlanTreeNode,
    val selectionState: CustomReadingPlanSelectionState,
    val isExpanded: Boolean,
)

private enum class AggregateState {
    ALL_ON,
    ALL_OFF,
    PARTIAL,
    EMPTY,
}

private const val PARTIAL_CHECKBOX_ALPHA = 0.55f

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
    private lateinit var planId: String
    private var chapterKeysByNodeKey: Map<String, List<Pair<Int, Int>>> = emptyMap()
    private var bookNodeKeysByNodeKey: Map<String, List<String>> = emptyMap()
    private var initialChapterReadByKey: Map<Pair<Int, Int>, Boolean> = emptyMap()
    private var pendingChapterReadByKey: MutableMap<Pair<Int, Int>, Boolean> = mutableMapOf()
    private var bookReadStateByNodeKey: Map<String, Boolean> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = CustomReadingPlanSelectionPlaceholderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.custom_reading_plan_selection_title)

        initialSelection = savedInstanceState?.getSerializable(STATE_INITIAL_SELECTION) as? CustomReadingPlanSelection
            ?: intent.getSerializableExtra(EXTRA_SELECTION) as? CustomReadingPlanSelection
            ?: CustomReadingPlanSelection()
        planId = intent.getStringExtra(EXTRA_PLAN_ID).orEmpty()
        pendingSelection = savedInstanceState?.getStringArrayList(STATE_PENDING_SELECTION)?.toSet()
            ?: initialSelection.selectedNodeKeys
        pendingChapterReadByKey = savedInstanceState
            ?.getStringArrayList(STATE_PENDING_CHAPTER_READ_OVERRIDES)
            ?.let(::decodeChapterReadOverrides)
            ?.toMutableMap()
            ?: decodeChapterReadOverrides(intent.getStringArrayListExtra(EXTRA_PENDING_CHAPTER_READ_OVERRIDES)).toMutableMap()

        setupList()
        renderSummary()
        setupButtons()
        loadTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(STATE_INITIAL_SELECTION, initialSelection)
        outState.putStringArrayList(STATE_PENDING_SELECTION, ArrayList(pendingSelection))
        outState.putStringArrayList(
            STATE_PENDING_CHAPTER_READ_OVERRIDES,
            encodeChapterReadOverrides(pendingChapterReadByKey),
        )
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
            onNodeMenuClick = ::showNodeMenu,
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
            val chapterCache = withContext(Dispatchers.Default) { buildNodeChapterCache(treeNodes, treeIndex) }
            chapterKeysByNodeKey = chapterCache.chapterKeysByNodeKey
            bookNodeKeysByNodeKey = chapterCache.bookNodeKeysByNodeKey
            expandedKeys = CustomReadingPlanExpansionStateStore.loadAndPrune(treeIndex.validKeys).toMutableSet()
            pendingSelection = pendingSelection.intersect(treeIndex.validKeys)
            initialChapterReadByKey = loadPersistedChapterReadByKey()
            refreshReadStateCache()
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

    private fun showNodeMenu(anchor: View, row: TreeRowRenderModel) {
        val node = row.visibleNode.node
        if (node.type != CustomReadingPlanNodeType.BIBLE_BOOK && node.type != CustomReadingPlanNodeType.BIBLE_SUBSECTION && node.type != CustomReadingPlanNodeType.BIBLE_TESTAMENT) {
            return
        }

        val popupMenu = PopupMenu(this, anchor)
        val isChecked = row.selectionState == CustomReadingPlanSelectionState.CHECKED

        when (node.type) {
            CustomReadingPlanNodeType.BIBLE_BOOK -> {
                popupMenu.menu.add(
                    Menu.NONE,
                    MENU_INCLUDE_EXCLUDE,
                    Menu.NONE,
                    if (isChecked) getString(R.string.custom_reading_plan_menu_exclude_book) else getString(R.string.custom_reading_plan_menu_include_book),
                )
                val isFullyRead = bookReadStateByNodeKey[node.key] == true
                popupMenu.menu.add(
                    Menu.NONE,
                    MENU_MARK_SINGLE_BOOK,
                    Menu.NONE,
                    if (isFullyRead) getString(R.string.custom_reading_plan_menu_mark_unread) else getString(R.string.custom_reading_plan_menu_mark_read),
                )
            }
            CustomReadingPlanNodeType.BIBLE_SUBSECTION,
            CustomReadingPlanNodeType.BIBLE_TESTAMENT,
            -> {
                val includeItem = popupMenu.menu.add(Menu.NONE, MENU_INCLUDE_SECTION, Menu.NONE, getString(R.string.custom_reading_plan_menu_include_books))
                val excludeItem = popupMenu.menu.add(Menu.NONE, MENU_EXCLUDE_SECTION, Menu.NONE, getString(R.string.custom_reading_plan_menu_exclude_books))
                val markReadItem = popupMenu.menu.add(Menu.NONE, MENU_MARK_ALL_READ, Menu.NONE, getString(R.string.custom_reading_plan_menu_mark_all_read))
                val markUnreadItem = popupMenu.menu.add(Menu.NONE, MENU_MARK_ALL_UNREAD, Menu.NONE, getString(R.string.custom_reading_plan_menu_mark_all_unread))

                when (selectionAggregateState(node)) {
                    AggregateState.ALL_ON -> {
                        includeItem.isEnabled = false
                        excludeItem.isEnabled = true
                    }
                    AggregateState.ALL_OFF -> {
                        includeItem.isEnabled = true
                        excludeItem.isEnabled = false
                    }
                    AggregateState.PARTIAL -> {
                        includeItem.isEnabled = true
                        excludeItem.isEnabled = true
                    }
                    AggregateState.EMPTY -> {
                        includeItem.isEnabled = false
                        excludeItem.isEnabled = false
                    }
                }

                when (readAggregateState(node)) {
                    AggregateState.ALL_ON -> {
                        markReadItem.isEnabled = false
                        markUnreadItem.isEnabled = true
                    }
                    AggregateState.ALL_OFF -> {
                        markReadItem.isEnabled = true
                        markUnreadItem.isEnabled = false
                    }
                    AggregateState.PARTIAL -> {
                        markReadItem.isEnabled = true
                        markUnreadItem.isEnabled = true
                    }
                    AggregateState.EMPTY -> {
                        markReadItem.isEnabled = false
                        markUnreadItem.isEnabled = false
                    }
                }
            }
            else -> Unit
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_INCLUDE_EXCLUDE -> {
                    toggleSelection(node, !isChecked)
                    true
                }
                MENU_MARK_SINGLE_BOOK -> {
                    lifecycleScope.launch {
                        val isFullyRead = bookReadStateByNodeKey[node.key] == true
                        markNodeChapters(node, !isFullyRead)
                    }
                    true
                }
                MENU_INCLUDE_SECTION -> {
                    toggleSelection(node, true)
                    true
                }
                MENU_EXCLUDE_SECTION -> {
                    toggleSelection(node, false)
                    true
                }
                MENU_MARK_ALL_READ -> {
                    lifecycleScope.launch { markNodeChapters(node, true) }
                    true
                }
                MENU_MARK_ALL_UNREAD -> {
                    lifecycleScope.launch { markNodeChapters(node, false) }
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    private suspend fun markNodeChapters(node: CustomReadingPlanTreeNode, isRead: Boolean) {
        val chapterKeys = chapterKeysByNodeKey[node.key].orEmpty()
        if (chapterKeys.isEmpty()) return
        chapterKeys.forEach { chapterKey ->
            pendingChapterReadByKey[chapterKey] = isRead
        }
        refreshReadStateCache()
        refreshVisibleRows()
    }

    private suspend fun refreshReadStateCache() {
        bookReadStateByNodeKey = chapterKeysByNodeKey
            .filterKeys { it.contains(":book:") }
            .mapValues { (_, chapterKeys) ->
                val bookId = chapterKeys.firstOrNull()?.first ?: return@mapValues false
                chapterKeys.isNotEmpty() &&
                    chapterKeys
                        .filter { (candidateBookId, _) -> candidateBookId == bookId }
                        .all { chapterKey -> resolvedChapterRead(chapterKey) }
            }
    }

    private suspend fun loadPersistedChapterReadByKey(): Map<Pair<Int, Int>, Boolean> {
        if (planId.isBlank()) return emptyMap()
        return CustomReadingPlanChapterStateService.loadPlanChapterStates(planId)
            .associate { (it.bookId to it.chapter) to it.isRead }
    }

    private fun resolvedChapterRead(chapterKey: Pair<Int, Int>): Boolean {
        return pendingChapterReadByKey[chapterKey] ?: initialChapterReadByKey[chapterKey] ?: false
    }

    private fun selectionAggregateState(node: CustomReadingPlanTreeNode): AggregateState {
        return when (CustomReadingPlanTreeSelection.selectionState(node, pendingSelection, treeIndex)) {
            CustomReadingPlanSelectionState.CHECKED -> AggregateState.ALL_ON
            CustomReadingPlanSelectionState.UNCHECKED -> AggregateState.ALL_OFF
            CustomReadingPlanSelectionState.PARTIAL -> AggregateState.PARTIAL
        }
    }

    private fun readAggregateState(node: CustomReadingPlanTreeNode): AggregateState {
        val includedBookKeys = bookNodeKeysByNodeKey[node.key].orEmpty()
        if (includedBookKeys.isEmpty()) return AggregateState.EMPTY
        val readCount = includedBookKeys.count { bookReadStateByNodeKey[it] == true }
        return when {
            readCount == 0 -> AggregateState.ALL_OFF
            readCount == includedBookKeys.size -> AggregateState.ALL_ON
            else -> AggregateState.PARTIAL
        }
    }

    private data class NodeChapterCache(
        val chapterKeysByNodeKey: Map<String, List<Pair<Int, Int>>>,
        val bookNodeKeysByNodeKey: Map<String, List<String>>,
    )

    private fun buildNodeChapterCache(
        nodes: List<CustomReadingPlanTreeNode>,
        index: CustomReadingPlanTreeIndex,
    ): NodeChapterCache {
        val nodesByKey = CustomReadingPlanTreeSelection
            .flattenVisible(nodes, index.validKeys)
            .map { it.node }
            .associateBy { it.key }

        fun chapterKeysForBook(node: CustomReadingPlanTreeNode): List<Pair<Int, Int>> {
            val parts = node.key.split(':')
            val moduleInitials = parts.getOrNull(1) ?: return emptyList()
            val bookName = parts.lastOrNull() ?: return emptyList()
            val bibleBook = runCatching { BibleBook.valueOf(bookName) }.getOrNull() ?: return emptyList()
            val document = SwordDocumentFacade.getDocumentByInitials(moduleInitials) as? AbstractPassageBook ?: return emptyList()
            return (1..document.versification.getLastChapter(bibleBook)).map { chapter -> bibleBook.ordinal to chapter }
        }

        val bookNodeKeysByNode = nodesByKey.mapValues { (key, _) ->
            val subtreeKeys = setOf(key) + index.descendantKeysByNode[key].orEmpty()
            subtreeKeys
                .mapNotNull { nodesByKey[it] }
                .filter { it.type == CustomReadingPlanNodeType.BIBLE_BOOK }
                .map { it.key }
        }

        val chapterKeysByNode = nodesByKey.mapValues { (key, _) ->
            val subtreeKeys = setOf(key) + index.descendantKeysByNode[key].orEmpty()
            subtreeKeys
                .mapNotNull { nodesByKey[it] }
                .filter { it.type == CustomReadingPlanNodeType.BIBLE_BOOK }
                .flatMap(::chapterKeysForBook)
                .distinct()
                .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        }

        return NodeChapterCache(
            chapterKeysByNodeKey = chapterKeysByNode,
            bookNodeKeysByNodeKey = bookNodeKeysByNode,
        )
    }

    private fun confirmSelection() {
        val selection = CustomReadingPlanSelection(pendingSelection)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_RESULT_SELECTION, selection)
            putExtra(
                EXTRA_RESULT_SELECTION_SUMMARY,
                CustomReadingPlanSelectionSummaryFormatter.format(this@CustomReadingPlanSelectionPlaceholderActivity, selection, treeNodes),
            )
            putStringArrayListExtra(
                EXTRA_RESULT_PENDING_CHAPTER_READ_OVERRIDES,
                encodeChapterReadOverrides(pendingChapterReadByKey),
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
        private val onNodeMenuClick: (View, TreeRowRenderModel) -> Unit,
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
                menuButton.isVisible = node.type == CustomReadingPlanNodeType.BIBLE_BOOK ||
                    node.type == CustomReadingPlanNodeType.BIBLE_SUBSECTION ||
                    node.type == CustomReadingPlanNodeType.BIBLE_TESTAMENT
                menuButton.setOnClickListener { onNodeMenuClick(it, item) }
                checkbox.setOnCheckedChangeListener(null)
                checkbox.checkedState = when (item.selectionState) {
                    CustomReadingPlanSelectionState.CHECKED -> MaterialCheckBox.STATE_CHECKED
                    CustomReadingPlanSelectionState.PARTIAL -> MaterialCheckBox.STATE_CHECKED
                    CustomReadingPlanSelectionState.UNCHECKED -> MaterialCheckBox.STATE_UNCHECKED
                }
                checkbox.alpha = if (item.selectionState == CustomReadingPlanSelectionState.PARTIAL) {
                    PARTIAL_CHECKBOX_ALPHA
                } else {
                    1.0f
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
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_SELECTION_SUMMARY = "selection_summary"
        const val EXTRA_SELECTION = "selection"
        const val EXTRA_PENDING_CHAPTER_READ_OVERRIDES = "pending_chapter_read_overrides"
        const val EXTRA_RESULT_SELECTION = "result_selection"
        const val EXTRA_RESULT_SELECTION_SUMMARY = "result_selection_summary"
        const val EXTRA_RESULT_PENDING_CHAPTER_READ_OVERRIDES = "result_pending_chapter_read_overrides"
        private const val STATE_INITIAL_SELECTION = "initial_selection"
        private const val STATE_PENDING_SELECTION = "pending_selection"
        private const val STATE_PENDING_CHAPTER_READ_OVERRIDES = "pending_chapter_read_overrides"

        private const val MENU_INCLUDE_EXCLUDE = 1
        private const val MENU_MARK_SINGLE_BOOK = 2
        private const val MENU_INCLUDE_SECTION = 3
        private const val MENU_EXCLUDE_SECTION = 4
        private const val MENU_MARK_ALL_READ = 5
        private const val MENU_MARK_ALL_UNREAD = 6

        private fun encodeChapterReadOverrides(overrides: Map<Pair<Int, Int>, Boolean>): ArrayList<String> = ArrayList(
            overrides.map { (key, isRead) ->
                "${key.first}:${key.second}:${if (isRead) 1 else 0}"
            }
        )

        fun decodeChapterReadOverrides(raw: List<String>?): Map<Pair<Int, Int>, Boolean> = raw.orEmpty().mapNotNull { encoded ->
            val parts = encoded.split(':')
            if (parts.size != 3) return@mapNotNull null
            val bookId = parts[0].toIntOrNull() ?: return@mapNotNull null
            val chapter = parts[1].toIntOrNull() ?: return@mapNotNull null
            val isRead = parts[2] == "1"
            (bookId to chapter) to isRead
        }.toMap()
    }
}

private object TreeRowDiffCallback : DiffUtil.ItemCallback<TreeRowRenderModel>() {
    override fun areItemsTheSame(oldItem: TreeRowRenderModel, newItem: TreeRowRenderModel): Boolean =
        oldItem.visibleNode.node.key == newItem.visibleNode.node.key

    override fun areContentsTheSame(oldItem: TreeRowRenderModel, newItem: TreeRowRenderModel): Boolean =
        oldItem == newItem
}
