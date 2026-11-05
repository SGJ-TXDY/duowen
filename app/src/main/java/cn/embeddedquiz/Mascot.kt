package cn.embeddedquiz

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MascotEyes {
    OPEN,
    CLOSED,
    HAPPY,
}

/** One frame of the mascot; every offset is in whole grid cells so it stays pixel art. */
data class MascotPose(
    /** Cells above the ground. */
    val lift: Int = 0,
    /** -1 squashes the body one row shorter, 1 stretches it one row taller. */
    val squash: Int = 0,
    val eyes: MascotEyes = MascotEyes.OPEN,
    val armsUp: Boolean = false,
)

enum class MascotInk {
    OUTLINE,
    BODY,
    HIGHLIGHT,
    EYE,
    MOUTH,
}

data class MascotCell(val x: Int, val y: Int, val ink: MascotInk)

/**
 * The app mascot, redrawn on a grid from the concept art. Body, face, arms and feet are separate
 * layers so a pose only moves the parts it needs. Right-hand parts mirror the left ones.
 */
object MascotSprite {
    const val GRID = 26
    const val BODY_WIDTH = 20
    const val IDLE_MS = 6000
    const val JUMP_MS = 1200
    private const val OFFSET_X = 3
    /** Row of the feet's lowest cells at rest. */
    private const val GROUND = 24
    /** The plain body row removed by a squash or doubled by a stretch. */
    private const val FLEX_ROW = 12

    val BODY =
        listOf(
            ".......######.......",
            ".....##bbbbbb##.....",
            "....#bbbbbbbbbb#....",
            "...#bbHHbbbbbbbb#...",
            "...#bHHbbbbbbbbb#...",
            "..#bbbbbbbbbbbbbb#..",
            "..#bHbbbbbbbbbbbb#..",
            ".#bbbbbbbbbbbbbbbb#.",
            ".#bbbbbbbbbbbbbbbb#.",
            "#bbbbbbbbbbbbbbbbbb#",
            "#bbbbbbbbbbbbbbbbbb#",
            "#bbbbbbbbbbbbbbbbbb#",
            "#bbbbbbbbbbbbbbbbbb#",
            ".#bbbbbbbbbbbbbbbb#.",
            ".#bbbbbbbbbbbbbbbb#.",
            "..##bbbbbbbbbbbb##..",
            "....############....",
        )

    /** Left eye cells as (row, column) in body coordinates. */
    val EYES =
        mapOf(
            MascotEyes.OPEN to listOf(8 to 5, 8 to 6, 9 to 5, 9 to 6),
            MascotEyes.CLOSED to listOf(9 to 5, 9 to 6),
            MascotEyes.HAPPY to listOf(8 to 5, 8 to 6, 9 to 4, 9 to 7),
        )

    val MOUTH = listOf(10 to 8, 10 to 11, 11 to 9, 11 to 10)

    /** Left arm drawn from its first row, three cells left of the body. */
    val ARM_DOWN = 10 to listOf(".##", "#bb", ".##")
    val ARM_UP = 7 to listOf("##.", "#b#", ".#b", "..#")

    /** Left foot, drawn from body row 17 at column 4. */
    val FOOT = listOf("#b#", "###")

    private fun ink(ch: Char) =
        when (ch) {
            '#' -> MascotInk.OUTLINE
            'b' -> MascotInk.BODY
            'H' -> MascotInk.HIGHLIGHT
            else -> null
        }

    private val cache = HashMap<MascotPose, List<MascotCell>>()

    fun cells(pose: MascotPose): List<MascotCell> =
        synchronized(cache) { cache.getOrPut(pose) { build(pose) } }

    private fun build(pose: MascotPose): List<MascotCell> {
        val squash = pose.squash.coerceIn(-1, 1)
        val top = GROUND - 18 - squash - pose.lift
        val grid = LinkedHashMap<Pair<Int, Int>, MascotInk>()
        fun at(x: Int, y: Int, ink: MascotInk?) {
            if (ink != null && x in 0 until GRID && y in 0 until GRID) grid[x to y] = ink
        }
        fun y(row: Int) = top + row + if (row > FLEX_ROW) squash else 0
        fun put(row: Int, col: Int, ink: MascotInk?) = at(col + OFFSET_X, y(row), ink)
        fun mirror(col: Int) = BODY_WIDTH - 1 - col

        BODY.forEachIndexed { row, line ->
            if (squash == -1 && row == FLEX_ROW) return@forEachIndexed
            line.forEachIndexed { col, ch ->
                put(row, col, ink(ch))
                if (squash == 1 && row == FLEX_ROW) at(col + OFFSET_X, top + row + 1, ink(ch))
            }
        }
        EYES.getValue(pose.eyes).forEach { (row, col) ->
            put(row, col, MascotInk.EYE)
            put(row, mirror(col), MascotInk.EYE)
        }
        MOUTH.forEach { (row, col) -> put(row, col, MascotInk.MOUTH) }
        val (armRow, arm) = if (pose.armsUp) ARM_UP else ARM_DOWN
        arm.forEachIndexed { i, line ->
            line.forEachIndexed { j, ch ->
                put(armRow + i, j - 3, ink(ch))
                put(armRow + i, mirror(j - 3), ink(ch))
            }
        }
        FOOT.forEachIndexed { i, line ->
            line.forEachIndexed { j, ch ->
                put(17 + i, 4 + j, ink(ch))
                put(17 + i, mirror(4 + j), ink(ch))
            }
        }
        return grid.map { (xy, ink) -> MascotCell(xy.first, xy.second, ink) }
    }

    /** Tap reaction over [0, 1]: crouch, jump three cells with arms up and happy eyes, land, settle. */
    fun jumpPose(t: Float): MascotPose =
        when {
            t >= 1f -> MascotPose()
            t < 0.12f -> MascotPose(squash = -1)
            t < 0.22f -> MascotPose(lift = 1, squash = 1, eyes = MascotEyes.HAPPY, armsUp = true)
            t < 0.32f -> MascotPose(lift = 2, squash = 1, eyes = MascotEyes.HAPPY, armsUp = true)
            t < 0.50f -> MascotPose(lift = 3, eyes = MascotEyes.HAPPY, armsUp = true)
            t < 0.60f -> MascotPose(lift = 2, eyes = MascotEyes.HAPPY, armsUp = true)
            t < 0.70f -> MascotPose(lift = 1, eyes = MascotEyes.HAPPY)
            t < 0.82f -> MascotPose(squash = -1, eyes = MascotEyes.HAPPY)
            t < 0.97f -> MascotPose(eyes = MascotEyes.HAPPY)
            else -> MascotPose()
        }

    /** Idle loop over [0, 1] of [IDLE_MS]: one blink near 2s, a small crouch and arm lift near 5s. */
    fun idlePose(t: Float): MascotPose {
        val second = t * IDLE_MS / 1000f
        return when {
            second >= 2f && second < 2.12f -> MascotPose(eyes = MascotEyes.CLOSED)
            second >= 5f && second < 5.2f -> MascotPose(squash = -1)
            second >= 5.2f && second < 5.45f -> MascotPose(armsUp = true)
            else -> MascotPose()
        }
    }
}

/**
 * The mascot in the profile card. It blinks and stretches now and then, and jumps when tapped.
 * With system animations turned off it only stops looping and smiles on tap.
 */
@Composable
fun Mascot(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.more.mascot
    val context = LocalContext.current
    val reduced =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    val idle =
        if (reduced) null
        else
            rememberInfiniteTransition(label = "mascot-idle")
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(MascotSprite.IDLE_MS, easing = LinearEasing)),
                    label = "mascot-idle",
                )
    val jump = remember { Animatable(1f) }
    var cheering by remember { mutableStateOf(false) }
    // Only a change of pose redraws; the idle clock alone ticks every frame.
    val pose by
        remember(idle) {
            derivedStateOf {
                when {
                    cheering -> MascotPose(eyes = MascotEyes.HAPPY)
                    jump.value < 1f -> MascotSprite.jumpPose(jump.value)
                    idle != null -> MascotSprite.idlePose(idle.value)
                    else -> MascotPose()
                }
            }
        }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    Canvas(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "跳一下",
            ) {
                // A tap during a jump is ignored rather than restarting it.
                if (jump.isRunning || cheering) return@clickable
                scope.launch {
                    if (reduced) {
                        cheering = true
                        try {
                            delay(1000)
                        } finally {
                            cheering = false
                        }
                    } else {
                        jump.snapTo(0f)
                        jump.animateTo(1f, tween(MascotSprite.JUMP_MS, easing = LinearEasing))
                    }
                }
            }
            .semantics { contentDescription = "吉祥物" }
    ) {
        // Whole-pixel cells keep the edges crisp; the leftover is split around the sprite.
        val fit = minOf(size.width, size.height) / MascotSprite.GRID
        val cell = if (fit >= 1f) floor(fit) else fit
        val origin =
            Offset((size.width - cell * MascotSprite.GRID) / 2, (size.height - cell * MascotSprite.GRID) / 2)
        val square = Size(cell, cell)
        MascotSprite.cells(pose).forEach {
            drawRect(colors.of(it.ink), origin + Offset(it.x * cell, it.y * cell), square)
        }
    }
}
