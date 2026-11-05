package cn.embeddedquiz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons drawn on a 24-unit grid; tinting replaces the black stroke. */
object AppIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f, filled: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                paths.forEach {
                    addPath(
                        pathData = addPathNodes(it),
                        fill = if (filled) SolidColor(Color.Black) else null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}" +
            "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    val Home = stroke("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val Practice = stroke("practice", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Review = stroke("review", "M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")
    val Mistakes = stroke("mistakes", circle(12f, 12f, 9f), "M12 7.5v5.5", "M12 16.5h.01")
    val More =
        stroke(
            "more",
            roundRect(4f, 4f, 6.5f, 6.5f, 1.5f),
            roundRect(13.5f, 4f, 6.5f, 6.5f, 1.5f),
            roundRect(4f, 13.5f, 6.5f, 6.5f, 1.5f),
            roundRect(13.5f, 13.5f, 6.5f, 6.5f, 1.5f),
        )
    val Back = stroke("back", "M19 12H5", "M11 18l-6-6 6-6")
    val ChevronLeft = stroke("chevron-left", "M15 6l-6 6 6 6")
    val ChevronRight = stroke("chevron-right", "M9 6l6 6-6 6")
    val ChevronDown = stroke("chevron-down", "M6 9l6 6 6-6")
    val ChevronUp = stroke("chevron-up", "M6 15l6-6 6 6")
    val Note = stroke("note", "M5 4h14v11l-5 5H5z", "M14 20v-5h5", "M9 9h6")
    val Star = stroke("star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.8z")
    val StarFilled =
        stroke(
            "star-filled",
            "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.8z",
            filled = true,
        )
    val MoreVert = stroke("more-vert", "M12 5h.01", "M12 12h.01", "M12 19h.01", width = 2.6f)
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.6f)
    val Close = stroke("close", "M7 7l10 10", "M17 7L7 17", width = 2.6f)
    val Help = stroke("help", circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.3", "M12 17h.01")
    val Chat = stroke("chat", "M4 5h16v11H9l-5 4z")
    val Pencil = stroke("pencil", "M4 20h4L19 9l-4-4L4 16z")
    val Tag = stroke("tag", "M3 12V4h8l9 9-8 8z", "M7.5 7.5h.01")
    val Info = stroke("info", circle(12f, 12f, 9f), "M12 11v5.5", "M12 7.5h.01")
    val Clock = stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Send = stroke("send", "M5 12h14", "M13 6l6 6-6 6")
    val Code = stroke("code", "M8 7l-5 5 5 5", "M16 7l5 5-5 5")
    val Flag = stroke("flag", "M5 21V4", "M5 4h11l-2 4 2 4H5")
    val Search = stroke("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Play = stroke("play", "M8 5v14l11-7z", filled = true)
    val Shuffle = stroke("shuffle", "M16 4h4v4", "M4 20L20 4", "M20 16v4h-4", "M15 15l5 5", "M4 4l5 5")
    val ListOrder = stroke("list", "M9 6h11", "M9 12h11", "M9 18h11", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val Target = stroke("target", circle(12f, 12f, 9f), circle(12f, 12f, 5f), "M12 12h.01")
    val Book = stroke("book", "M4 19V5a2 2 0 0 1 2-2h14v16H6a2 2 0 0 0-2 2z", "M9 8h7")
    val Exam = stroke("exam", roundRect(5f, 4f, 14f, 17f, 2f), "M9 3h6v3H9z", "M8.5 13l2.5 2.5 4.5-4.5")
    val Folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val QuickNotes =
        stroke("quick-notes", "M6 3h9l4 4v14H6z", "M14 3v5h5", "M9 12h7", "M9 16h5")
    val Trash = stroke("trash", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val Import = stroke("import", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    // 更多 page rows: drawn thinner, as in the design.
    val Sync =
        stroke("sync", "M20 11a8 8 0 0 0-14.9-3.9", "M4 4v4h4", "M4 13a8 8 0 0 0 14.9 3.9", "M20 20v-4h-4", width = 1.8f)
    val TagThin = stroke("tag-thin", "M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z", circle(7.5f, 7.5f, 1.5f), width = 1.8f)
    val ClipboardCheck =
        stroke("clipboard-check", roundRect(5f, 4f, 14f, 17f, 2f), "M9 4V3h6v1", "M9 13l2 2 4-4", width = 1.8f)
    val BarChart = stroke("bar-chart", "M4 20h16", "M7 16v-5", "M12 16V7", "M17 16v-8", width = 1.8f)
    val Coin =
        stroke(
            "coin",
            circle(12f, 12f, 9f),
            "M9 7.5l3 4 3-4",
            "M8.5 12h7",
            "M8.5 15h7",
            "M12 11.5v6",
            width = 1.8f,
        )
    val Settings =
        stroke(
            "settings",
            circle(12f, 12f, 3f),
            circle(12f, 12f, 7f),
            "M12 2.5V5",
            "M12 19v2.5",
            "M2.5 12H5",
            "M19 12h2.5",
            "M5.3 5.3l1.8 1.8",
            "M16.9 16.9l1.8 1.8",
            "M5.3 18.7l1.8-1.8",
            "M16.9 7.1l1.8-1.8",
            width = 1.8f,
        )
    /** Placeholder for the profile card until an avatar (image or vector) is chosen. */
    val Person = stroke("person", circle(12f, 8.5f, 4f), "M4.5 20a7.5 7.5 0 0 1 15 0", width = 1.8f)
    val Flame =stroke("flame", "M12 3c1 3.5 5 5.5 5 10a5 5 0 0 1-10 0c0-2.5 1.5-4 2.5-5 .3 1.6 1 2.6 2 3 0-3 .5-5.5.5-8z")
}
