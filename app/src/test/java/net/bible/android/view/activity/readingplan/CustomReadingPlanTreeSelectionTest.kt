package net.bible.android.view.activity.readingplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomReadingPlanTreeSelectionTest {
    private val book1 = CustomReadingPlanTreeNode("book:gen", "Genesis", CustomReadingPlanNodeType.BIBLE_BOOK)
    private val book2 = CustomReadingPlanTreeNode("book:exod", "Exodus", CustomReadingPlanNodeType.BIBLE_BOOK)
    private val subsection = CustomReadingPlanTreeNode(
        "subsection:ot:pentateuch",
        "Pentateuch",
        CustomReadingPlanNodeType.BIBLE_SUBSECTION,
        children = listOf(book1, book2),
    )

    @Test
    fun `selecting parent cascades to descendants`() {
        val selected = CustomReadingPlanTreeSelection.setSelected(subsection, emptySet(), true)

        assertTrue(selected.containsAll(setOf("subsection:ot:pentateuch", "book:gen", "book:exod")))
        assertEquals(CustomReadingPlanSelectionState.CHECKED, CustomReadingPlanTreeSelection.selectionState(subsection, selected))
    }

    @Test
    fun `partial descendants yield partial state`() {
        val selected = setOf("book:gen")

        assertEquals(CustomReadingPlanSelectionState.PARTIAL, CustomReadingPlanTreeSelection.selectionState(subsection, selected))
    }

    @Test
    fun `flattenVisible only includes expanded descendants`() {
        val root = CustomReadingPlanTreeNode("root", "Bible", CustomReadingPlanNodeType.BIBLE_MODULE, listOf(subsection))

        val collapsed = CustomReadingPlanTreeSelection.flattenVisible(listOf(root), emptySet())
        val expanded = CustomReadingPlanTreeSelection.flattenVisible(listOf(root), setOf("root", "subsection:ot:pentateuch"))

        assertEquals(listOf("root"), collapsed.map { it.node.key })
        assertEquals(listOf("root", "subsection:ot:pentateuch", "book:gen", "book:exod"), expanded.map { it.node.key })
    }
}
