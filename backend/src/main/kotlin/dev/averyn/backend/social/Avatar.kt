package dev.averyn.backend.social

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The side of the stored avatar in pixels. */
const val AVATAR_SIDE = 256

/** The biggest upload read for an avatar. */
const val MAX_AVATAR_BYTES = 5 * 1024 * 1024

/** A picture of more pixels than this is refused before it is decoded (a small file can unpack to gigabytes). */
private const val MAX_AVATAR_PIXELS = 25_000_000L

/**
 * The avatar as the server stores it (TDD-0018 F9): decoded, cut to the central square, scaled to [AVATAR_SIDE] and
 * written again as a JPEG. Only the pixels survive: EXIF (including GPS), ICC and every other block are gone because
 * nothing but the image is written. Returns null when the bytes are not an image the JDK reads, or are too large.
 *
 * ponytail: the EXIF orientation is not applied, so a photo taken sideways stays sideways; the apps send an upright
 * square, and the web editor does the same. Add it if users upload raw camera files.
 */
fun reencodeAvatar(input: ByteArray): ByteArray? =
    try {
        ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_AVATAR_PIXELS) return null
                squareJpeg(reader.read(0))
            } finally {
                reader.dispose()
            }
        }
    } catch (_: Exception) {
        null
    }

private fun squareJpeg(source: BufferedImage): ByteArray {
    val side = minOf(source.width, source.height)
    val x = (source.width - side) / 2
    val y = (source.height - side) / 2
    val out = BufferedImage(AVATAR_SIDE, AVATAR_SIDE, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = java.awt.Color.WHITE // a transparent picture has no RGB behind it: JPEG has no alpha
        g.fillRect(0, 0, AVATAR_SIDE, AVATAR_SIDE)
        g.drawImage(source, 0, 0, AVATAR_SIDE, AVATAR_SIDE, x, y, x + side, y + side, null)
    } finally {
        g.dispose()
    }
    return ByteArrayOutputStream().also { check(ImageIO.write(out, "jpg", it)) { "no JPEG writer" } }.toByteArray()
}
