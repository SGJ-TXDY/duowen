package cn.embeddedquiz

import org.junit.Assert.*
import org.junit.Test

class MascotTest {
    private val sprite = MascotSprite

    @Test
    fun spriteRowsAreEvenAndSymmetric() {
        val sprites = sprite.BODY + sprite.FOOT + sprite.ARM_DOWN.second + sprite.ARM_UP.second
        sprites.forEach { line -> assertTrue(line, line.all { it in ".#bH" }) }
        sprite.BODY.forEach { assertEquals(sprite.BODY_WIDTH, it.length) }
        // The highlight sits on the left only; everything else mirrors.
        sprite.BODY.forEach { assertEquals(it.replace('H', 'b'), it.replace('H', 'b').reversed()) }
        assertEquals(sprite.FOOT, sprite.FOOT.map { it.reversed() })
    }

    @Test
    fun everyPoseIsMirroredApartFromTheHighlightAndFitsTheGrid() {
        val poses =
            (0..100).map { sprite.jumpPose(it / 100f) } + (0..600).map { sprite.idlePose(it / 600f) }
        poses.distinct().forEach { pose ->
            val cells = sprite.cells(pose)
            assertTrue(cells.all { it.x in 0 until sprite.GRID && it.y in 0 until sprite.GRID })
            val shape = cells.filter { it.ink != MascotInk.HIGHLIGHT }.map { it.x to it.y }.toSet()
            val bodyCells = cells.map { it.x to it.y }.toSet()
            // Mirror around the sprite's centre column (body columns 3..22).
            shape.forEach { (x, y) -> assertTrue("$pose $x,$y", (25 - x to y) in bodyCells) }
            assertEquals(2 * sprite.EYES.getValue(pose.eyes).size, cells.count { it.ink == MascotInk.EYE })
        }
    }

    @Test
    fun jumpRisesWithHappyFaceAndSettlesBack() {
        val restTop = sprite.cells(MascotPose()).minOf { it.y }
        val apex = sprite.jumpPose(0.4f)
        assertEquals(MascotPose(lift = 3, eyes = MascotEyes.HAPPY, armsUp = true), apex)
        assertEquals(restTop - 3, sprite.cells(apex).minOf { it.y })
        assertEquals(-1, sprite.jumpPose(0.05f).squash)
        assertEquals(-1, sprite.jumpPose(0.75f).squash)
        assertEquals(MascotPose(), sprite.jumpPose(1f))
        assertEquals(MascotPose(), sprite.jumpPose(0.99f))
        // Feet leave the ground only while lifted.
        val ground = sprite.cells(MascotPose()).maxOf { it.y }
        assertEquals(ground, sprite.cells(MascotPose(squash = -1)).maxOf { it.y })
        assertEquals(ground, sprite.cells(MascotPose(squash = 1)).maxOf { it.y })
        assertEquals(ground - 2, sprite.cells(sprite.jumpPose(0.55f)).maxOf { it.y })
    }

    @Test
    fun idleMostlyRestsWithOneBlinkAndOneStretch() {
        val frames = (0 until 600).map { sprite.idlePose(it / 600f) }
        // About 0.6s of the 6s loop moves; the rest is still.
        assertTrue(frames.count { it == MascotPose() } > 500)
        assertTrue(frames.any { it.eyes == MascotEyes.CLOSED })
        assertTrue(frames.any { it.squash == -1 })
        assertTrue(frames.any { it.armsUp })
        assertEquals(MascotPose(), sprite.idlePose(0f))
    }
}
