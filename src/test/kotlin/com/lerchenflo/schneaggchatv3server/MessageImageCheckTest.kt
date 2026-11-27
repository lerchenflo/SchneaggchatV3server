package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.MessageImageFormat
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

/** Chat images are stored as uploaded, so the server checks the bytes themselves (ImageManager.checkMessageImage). */
class MessageImageCheckTest {

    private val imageManager = ImageManager()

    private fun encoded(format: String, width: Int = 40, height: Int = 30): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(3, 4, 0xFF8800)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeAndData = type.toByteArray() + data
        val crc = CRC32().apply { update(typeAndData) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + typeAndData + ByteBuffer.allocate(4).putInt(crc).array()
    }

    /** A valid PNG header claiming [width] x [height], with a tiny pixel block: unpacking it would need gigabytes. */
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val deflater = Deflater().apply { setInput(ByteArray(1000)); finish() }
        val pixels = ByteArray(100).let { it.copyOf(deflater.deflate(it)) }
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            pngChunk("IHDR", header) + pngChunk("IDAT", pixels) + pngChunk("IEND", ByteArray(0))
    }

    @Test
    fun `real JPEG, PNG and GIF are accepted with the format of their bytes`() {
        assertEquals(MessageImageFormat.JPEG, imageManager.checkMessageImage(encoded("jpeg")))
        assertEquals(MessageImageFormat.PNG, imageManager.checkMessageImage(encoded("png")))
        assertEquals(MessageImageFormat.GIF, imageManager.checkMessageImage(encoded("gif")))
    }

    @Test
    fun `a big photo is checked without being rejected`() {
        assertEquals(MessageImageFormat.JPEG, imageManager.checkMessageImage(encoded("jpeg", 4000, 3000)))
    }

    @Test
    fun `bytes that are no supported image are rejected whatever the upload claims`() {
        val notAnImage = assertThrows<IllegalArgumentException> { imageManager.checkMessageImage("hello".repeat(50).toByteArray()) }
        assertEquals("Unsupported image format", notAnImage.message)

        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray() + ByteArray(100)
        assertEquals("Unsupported image format", assertThrows<IllegalArgumentException> { imageManager.checkMessageImage(webp) }.message)
    }

    @Test
    fun `a broken file with an image signature is rejected`() {
        val brokenJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(500)
        assertEquals("Invalid image data", assertThrows<IllegalArgumentException> { imageManager.checkMessageImage(brokenJpeg) }.message)

        val truncatedPng = encoded("png").copyOf(30)
        assertEquals("Invalid image data", assertThrows<IllegalArgumentException> { imageManager.checkMessageImage(truncatedPng) }.message)
    }

    @Test
    fun `a decompression bomb is rejected by its pixel count before decoding`() {
        val bomb = pngHeaderOnly(12_000, 10_000) // 120 MP
        assertEquals("Image has too many pixels", assertThrows<IllegalArgumentException> { imageManager.checkMessageImage(bomb) }.message)
    }

    @Test
    fun `chat images may be up to 6 MB, profile pictures stay at 3 MB`() {
        val fourMb = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(4 * 1024 * 1024))
        val sevenMb = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(7 * 1024 * 1024))

        assertTrue(ValidationUtils.validateMessageImage(fourMb))
        assertFalse(ValidationUtils.validateMessageImage(sevenMb))
        assertFalse(ValidationUtils.validatePicture(fourMb))
    }
}
