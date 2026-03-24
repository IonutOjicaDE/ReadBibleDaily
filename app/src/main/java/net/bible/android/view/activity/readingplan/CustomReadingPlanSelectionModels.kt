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

import android.content.Context
import net.bible.android.activity.R
import net.bible.service.common.CommonUtils
import net.bible.service.sword.SwordDocumentFacade
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.Versification
import java.io.Serializable

private const val EXPANDED_NODE_PREF_KEY = "custom_reading_plan.expanded_node_keys"
private const val BIBLE_SECTION_OT = "old_testament"
private const val BIBLE_SECTION_NT = "new_testament"

/** Serializable selection snapshot kept only in memory for the current implementation step. */
data class CustomReadingPlanSelection(
    val selectedNodeKeys: Set<String> = emptySet(),
) : Serializable

enum class CustomReadingPlanSelectionState {
    UNCHECKED,
    CHECKED,
    PARTIAL,
}

enum class CustomReadingPlanNodeType {
    BIBLE_MODULE,
    BIBLE_TESTAMENT,
    BIBLE_SUBSECTION,
    BIBLE_BOOK,
    DOCUMENT_PLACEHOLDER,
}

data class CustomReadingPlanTreeNode(
    val key: String,
    val label: String,
    val type: CustomReadingPlanNodeType,
    val children: List<CustomReadingPlanTreeNode> = emptyList(),
) {
    val isExpandable: Boolean get() = children.isNotEmpty()
}

data class VisibleCustomReadingPlanTreeNode(
    val node: CustomReadingPlanTreeNode,
    val depth: Int,
)

/**
 * Section definition for the current 4-level Bible hierarchy:
 * module -> testament -> subsection -> individual books.
 */
data class BibleSubsectionDefinition(
    val key: String,
    val title: String,
    val bookNames: List<String>,
)

interface CustomReadingPlanTreeSource {
    fun buildNodes(context: Context): List<CustomReadingPlanTreeNode>
}

object CustomReadingPlanTreeFactory {
    private val sources: List<CustomReadingPlanTreeSource> = listOf(BibleTreeSource())

    fun build(context: Context): List<CustomReadingPlanTreeNode> = sources.flatMap { it.buildNodes(context) }
}

class BibleTreeSource(
    private val subsectionProvider: BibleSubsectionProvider = StructuredBibleSubsectionProvider,
) : CustomReadingPlanTreeSource {
    override fun buildNodes(context: Context): List<CustomReadingPlanTreeNode> = SwordDocumentFacade.bibles
        .sortedBy { it.name.lowercase() }
        .map { bible ->
            CustomReadingPlanTreeNode(
                key = "bible:${bible.initials}",
                label = bible.name,
                type = CustomReadingPlanNodeType.BIBLE_MODULE,
                children = buildBibleSections(context, bible),
            )
        }

    private fun buildBibleSections(context: Context, bible: Book): List<CustomReadingPlanTreeNode> {
        val versification = (bible as? AbstractPassageBook)?.versification ?: return emptyList()
        return listOf(
            CustomReadingPlanTreeNode(
                key = "bible:${bible.initials}:$BIBLE_SECTION_OT",
                label = context.getString(R.string.custom_reading_plan_old_testament),
                type = CustomReadingPlanNodeType.BIBLE_TESTAMENT,
                children = subsectionProvider.oldTestament(context).mapNotNull { it.toTreeNodeOrNull(bible, versification) },
            ),
            CustomReadingPlanTreeNode(
                key = "bible:${bible.initials}:$BIBLE_SECTION_NT",
                label = context.getString(R.string.custom_reading_plan_new_testament),
                type = CustomReadingPlanNodeType.BIBLE_TESTAMENT,
                children = subsectionProvider.newTestament(context).mapNotNull { it.toTreeNodeOrNull(bible, versification) },
            ),
        )
    }

    private fun BibleSubsectionDefinition.toTreeNodeOrNull(bible: Book, versification: Versification): CustomReadingPlanTreeNode? {
        val books = resolveBooks(versification)
        if (books.isEmpty()) return null
        return CustomReadingPlanTreeNode(
            key = "bible:${bible.initials}:${this.key}",
            label = title,
            type = CustomReadingPlanNodeType.BIBLE_SUBSECTION,
            children = books.map { bibleBook ->
                CustomReadingPlanTreeNode(
                    key = "bible:${bible.initials}:book:${bibleBook.name}",
                    label = versification.getLongName(bibleBook),
                    type = CustomReadingPlanNodeType.BIBLE_BOOK,
                )
            },
        )
    }

    private fun BibleSubsectionDefinition.resolveBooks(versification: Versification): List<BibleBook> {
        val availableBooks = versification.bookIterator.asSequence().toSet()
        return bookNames.mapNotNull { name ->
            runCatching { BibleBook.valueOf(name) }
                .getOrNull()
                ?.takeIf { it in availableBooks }
        }
    }
}

interface BibleSubsectionProvider {
    fun oldTestament(context: Context): List<BibleSubsectionDefinition>
    fun newTestament(context: Context): List<BibleSubsectionDefinition>
}

object StructuredBibleSubsectionProvider : BibleSubsectionProvider {
    override fun oldTestament(context: Context): List<BibleSubsectionDefinition> = listOf(
        BibleSubsectionDefinition(
            key = "section:ot:pentateuch",
            title = context.getString(R.string.custom_reading_plan_subsection_ot_pentateuch),
            bookNames = listOf("GEN", "EXOD", "LEV", "NUM", "DEUT"),
        ),
        BibleSubsectionDefinition(
            key = "section:ot:historical",
            title = context.getString(R.string.custom_reading_plan_subsection_ot_historical),
            bookNames = listOf("JOSH", "JUDG", "RUTH", "SAM1", "SAM2", "KGS1", "KGS2", "CHR1", "CHR2", "EZRA", "NEH", "ESTH"),
        ),
        BibleSubsectionDefinition(
            key = "section:ot:poetic",
            title = context.getString(R.string.custom_reading_plan_subsection_ot_poetic),
            bookNames = listOf("JOB", "PS", "PROV", "ECCL", "SONG", "LAM"),
        ),
        BibleSubsectionDefinition(
            key = "section:ot:prophetic",
            title = context.getString(R.string.custom_reading_plan_subsection_ot_prophetic),
            bookNames = listOf("ISA", "JER", "EZEK", "DAN", "HOS", "JOEL", "AMOS", "OBAD", "JONAH", "MIC", "NAH", "HAB", "ZEPH", "HAG", "ZECH", "MAL"),
        ),
        BibleSubsectionDefinition(
            key = "section:ot:orthodox_additional",
            title = context.getString(R.string.custom_reading_plan_subsection_ot_orthodox_additional),
            bookNames = listOf("TOB", "JDT", "BAR", "EP_JER", "PR_AZAR", "ESD2", "WIS", "SIR", "SUS", "BEL", "MACC1", "MACC2", "MACC3", "PR_MAN"),
        ),
    )

    override fun newTestament(context: Context): List<BibleSubsectionDefinition> = listOf(
        BibleSubsectionDefinition(
            key = "section:nt:gospels",
            title = context.getString(R.string.custom_reading_plan_subsection_nt_gospels),
            bookNames = listOf("MATT", "MARK", "LUKE", "JOHN"),
        ),
        BibleSubsectionDefinition(
            key = "section:nt:acts",
            title = context.getString(R.string.custom_reading_plan_subsection_nt_acts),
            bookNames = listOf("ACTS"),
        ),
        BibleSubsectionDefinition(
            key = "section:nt:pauline",
            title = context.getString(R.string.custom_reading_plan_subsection_nt_pauline),
            bookNames = listOf("ROM", "COR1", "COR2", "GAL", "EPH", "PHIL", "COL", "THESS1", "THESS2", "TIM1", "TIM2", "TITUS", "PHLM", "HEB"),
        ),
        BibleSubsectionDefinition(
            key = "section:nt:catholic",
            title = context.getString(R.string.custom_reading_plan_subsection_nt_catholic),
            bookNames = listOf("JAS", "PET1", "PET2", "JOHN1", "JOHN2", "JOHN3", "JUDE"),
        ),
        BibleSubsectionDefinition(
            key = "section:nt:revelation",
            title = context.getString(R.string.custom_reading_plan_subsection_nt_revelation),
            bookNames = listOf("REV"),
        ),
    )
}

object CustomReadingPlanTreeSelection {
    fun flattenVisible(nodes: List<CustomReadingPlanTreeNode>, expandedKeys: Set<String>): List<VisibleCustomReadingPlanTreeNode> {
        val visible = mutableListOf<VisibleCustomReadingPlanTreeNode>()
        fun append(node: CustomReadingPlanTreeNode, depth: Int) {
            visible += VisibleCustomReadingPlanTreeNode(node, depth)
            if (node.key in expandedKeys) {
                node.children.forEach { append(it, depth + 1) }
            }
        }
        nodes.forEach { append(it, 0) }
        return visible
    }

    fun selectionState(node: CustomReadingPlanTreeNode, selectedNodeKeys: Set<String>): CustomReadingPlanSelectionState {
        if (node.children.isEmpty()) {
            return if (node.key in selectedNodeKeys) {
                CustomReadingPlanSelectionState.CHECKED
            } else {
                CustomReadingPlanSelectionState.UNCHECKED
            }
        }

        val descendantKeys = node.descendantKeys()
        val selectedDescendants = descendantKeys.count { it in selectedNodeKeys }
        return when {
            selectedDescendants == 0 && node.key !in selectedNodeKeys -> CustomReadingPlanSelectionState.UNCHECKED
            selectedDescendants == descendantKeys.size -> CustomReadingPlanSelectionState.CHECKED
            else -> CustomReadingPlanSelectionState.PARTIAL
        }
    }

    fun setSelected(node: CustomReadingPlanTreeNode, selectedNodeKeys: Set<String>, selected: Boolean): Set<String> {
        val updated = selectedNodeKeys.toMutableSet()
        val subtreeKeys = node.subtreeKeys()
        if (selected) updated += subtreeKeys else updated -= subtreeKeys
        return updated
    }

    fun validNodeKeys(nodes: List<CustomReadingPlanTreeNode>): Set<String> = nodes.flatMap { it.subtreeKeys() }.toSet()

    private fun CustomReadingPlanTreeNode.descendantKeys(): Set<String> = children.flatMap { it.subtreeKeys() }.toSet()

    private fun CustomReadingPlanTreeNode.subtreeKeys(): Set<String> = buildSet {
        add(key)
        children.forEach { addAll(it.subtreeKeys()) }
    }
}

object CustomReadingPlanExpansionStateStore {
    fun loadAndPrune(validKeys: Set<String>): Set<String> {
        val saved = CommonUtils.settings.getStringSet(EXPANDED_NODE_PREF_KEY)
        val pruned = saved.intersect(validKeys)
        if (pruned != saved) {
            CommonUtils.settings.setStringSet(EXPANDED_NODE_PREF_KEY, pruned)
        }
        return pruned
    }

    fun persist(expandedKeys: Set<String>, validKeys: Set<String>) {
        CommonUtils.settings.setStringSet(EXPANDED_NODE_PREF_KEY, expandedKeys.intersect(validKeys))
    }
}

object CustomReadingPlanSelectionSummaryFormatter {
    fun format(context: Context, selection: CustomReadingPlanSelection, treeNodes: List<CustomReadingPlanTreeNode>): String {
        if (selection.selectedNodeKeys.isEmpty()) {
            return context.getString(R.string.custom_reading_plan_selection_summary_none)
        }

        val allNodes = CustomReadingPlanTreeSelection.flattenVisible(treeNodes, CustomReadingPlanTreeSelection.validNodeKeys(treeNodes))
            .map { it.node }
        val selectedBooks = allNodes.filter {
            it.type == CustomReadingPlanNodeType.BIBLE_BOOK &&
                CustomReadingPlanTreeSelection.selectionState(it, selection.selectedNodeKeys) == CustomReadingPlanSelectionState.CHECKED
        }
        val selectedBibles = allNodes.filter {
            it.type == CustomReadingPlanNodeType.BIBLE_MODULE &&
                CustomReadingPlanTreeSelection.selectionState(it, selection.selectedNodeKeys) == CustomReadingPlanSelectionState.CHECKED
        }

        return when {
            selectedBibles.isNotEmpty() -> context.resources.getQuantityString(
                R.plurals.custom_reading_plan_selection_summary_bibles,
                selectedBibles.size,
                selectedBibles.size,
            )
            else -> context.resources.getQuantityString(
                R.plurals.custom_reading_plan_selection_summary_books,
                selectedBooks.size,
                selectedBooks.size,
            )
        }
    }
}
