package cn.embeddedquiz

import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** The launcher icon is generated from the mascot sprite by tools/mascot_icon.py; it must not drift. */
class MascotLauncherIconTest {
    private val res = File("src/main/res")

    private class Icon(val cell: Double, val ox: Double, val oy: Double, val paths: List<Pair<Int, Set<Pair<Int, Int>>>>)

    private fun read(name: String): Icon {
        val xml = File(res, "drawable/$name.xml").readText()
        val head = Regex("""cell=([\d.]+) origin=([\d.]+),([\d.]+)""").find(xml)!!.groupValues
        val cell = head[1].toDouble()
        val ox = head[2].toDouble()
        val oy = head[3].toDouble()
        fun index(v: Double, origin: Double): Int {
            val i = (v - origin) / cell
            assertEquals("off-grid coordinate $v", i.roundToInt().toDouble(), i, 1e-6)
            return i.roundToInt()
        }
        val paths =
            Regex("""fillColor="#([0-9A-F]{8})" android:pathData="([^"]+)"""").findAll(xml).map { m ->
                val cells = HashSet<Pair<Int, Int>>()
                Regex("""M([\d.]+),([\d.]+)h([\d.]+)v([\d.]+)h-([\d.]+)z""").findAll(m.groupValues[2]).forEach { r ->
                    val (x, y, w, h, back) = r.destructured
                    assertEquals(w, back)
                    assertEquals(cell, h.toDouble(), 1e-9)
                    val x0 = index(x.toDouble(), ox)
                    val y0 = index(y.toDouble(), oy)
                    val count = (w.toDouble() / cell).roundToInt()
                    for (i in 0 until count) assertTrue("overlapping run", cells.add(x0 + i to y0))
                }
                m.groupValues[1].toLong(16).toInt() to cells
            }.toList()
        return Icon(cell, ox, oy, paths)
    }

    private val rest = MascotSprite.cells(MascotPose())

    @Test
    fun colourIconPaintsTheRestingSpriteInLightColours() {
        val icon = read("ic_launcher_mascot")
        val colors = mascotColors(dark = false)
        // Later paths paint over earlier ones, as the vector drawable does.
        val painted = HashMap<Pair<Int, Int>, Int>()
        icon.paths.forEach { (argb, cells) -> cells.forEach { painted[it] = argb } }
        assertEquals(rest.associate { (it.x to it.y) to colors.of(it.ink).toArgb() }, painted)
    }

    @Test
    fun monochromeIsTheSilhouetteWithTheFaceCutOut() {
        val icon = read("ic_launcher_mascot_mono")
        assertEquals(1, icon.paths.size)
        val face = setOf(MascotInk.EYE, MascotInk.MOUTH)
        assertEquals(rest.filter { it.ink !in face }.map { it.x to it.y }.toSet(), icon.paths.single().second)
    }

    @Test
    fun spriteStaysInsideTheAdaptiveIconSafeCircle() {
        val icon = read("ic_launcher_mascot")
        rest.forEach { c ->
            for (dx in 0..1) for (dy in 0..1) {
                val x = icon.ox + (c.x + dx) * icon.cell
                val y = icon.oy + (c.y + dy) * icon.cell
                assertTrue("cell ${c.x},${c.y} leaves the 66dp safe zone", hypot(x - 54, y - 54) <= 33 + 1e-6)
            }
        }
    }

    @Test
    fun launcherUsesTheMascotDrawables() {
        val launcher = File(res, "mipmap-anydpi/ic_launcher.xml").readText()
        assertTrue(launcher.contains("""<foreground android:drawable="@drawable/ic_launcher_mascot" />"""))
        assertTrue(launcher.contains("""<monochrome android:drawable="@drawable/ic_launcher_mascot_mono" />"""))
    }
}
