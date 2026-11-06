package cn.embeddedquiz

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.*
import org.junit.Test

/** UI-03: every icon is a local Lucide vector with the same grid, stroke and caps. */
class AppIconsTest {
    private val icons: Map<String, ImageVector> =
        AppIcons::class.java.declaredFields
            .filter { it.type == ImageVector::class.java }
            .associate {
                it.isAccessible = true
                it.name to it.get(AppIcons) as ImageVector
            }

    private fun paths(icon: ImageVector) = icon.root.map { it as VectorPath }

    @Test
    fun everyIconSharesTheLucideGridAndStroke() {
        assertEquals(44, icons.size)
        icons.forEach { (name, icon) ->
            assertEquals(name, 24f, icon.viewportWidth)
            assertEquals(name, 24f, icon.viewportHeight)
            val paths = paths(icon)
            assertTrue(name, paths.isNotEmpty())
            paths.forEach {
                assertTrue(name, it.pathData.isNotEmpty())
                assertEquals(name, 2f, it.strokeLineWidth)
                assertEquals(name, StrokeCap.Round, it.strokeLineCap)
                assertEquals(name, StrokeJoin.Round, it.strokeLineJoin)
            }
        }
        // The license notice names every Lucide source actually used.
        assertTrue(AppIcons.sources.keys.containsAll(listOf("house", "clipboard-list", "book-open", "circle-alert", "layout-grid")))
    }

    @Test
    fun circlesRectsAndLinesAreConvertedNotDropped() {
        // circle-alert: circle + two lines; layout-grid: four rects; clipboard-check: rect + two paths.
        assertEquals(3, paths(AppIcons.Mistakes).size)
        assertEquals(4, paths(AppIcons.More).size)
        assertEquals(3, paths(AppIcons.ClipboardCheck).size)
        assertEquals(2, paths(AppIcons.Settings).size)
        assertEquals(4, paths(AppIcons.Coin).size)
        // tag's dot is filled in the source; its outline is not.
        val tag = paths(AppIcons.Tag)
        assertNull(tag[0].fill)
        assertNotNull(tag[1].fill)
        // The filled variants fill every path.
        assertTrue(paths(AppIcons.StarFilled).all { it.fill != null })
        assertTrue(paths(AppIcons.Star).all { it.fill == null })
    }
}
