package com.lerchenflo.schneaggchatv3server.util

import org.bson.types.ObjectId
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.sqrt

@Component
class ImageManager {

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 700 * 1024 // 700KB
        private const val MESSAGE_IMAGES_DIR = "/app/images_messages"
        /** Rejects decompression bombs, allows every real camera photo that fits the 6 MB cap. */
        const val MAX_MESSAGE_IMAGE_PIXELS = 100_000_000L
        /** [checkMessageImage] decodes at roughly this long edge (1x to 2x of it). */
        private const val CHECK_DECODE_EDGE_PX = 1024
    }


    fun loadProfilePicFromFile(fileName: String): ByteArray {
        val imagesDir = File("/app/images")
        val targetFile = File(imagesDir, fileName)

        if (!targetFile.exists()) {
            throw IllegalArgumentException("Image file not found: $fileName")
        }

        return targetFile.readBytes()
    }

    fun saveProfilePic(image: MultipartFile, userId: String, group: Boolean): String {
        val filename = getProfilePicFileName(userId, group)

        // Read the image, make it round, and save
        val originalImage = readUprightImage(image)
        val roundImage = makeImageRound(originalImage)
        val finalImage = downscaleIfNeeded(roundImage)

        val imagesDir = File("/app/images")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val targetFile = File(imagesDir, filename)
        ImageIO.write(finalImage, "PNG", targetFile) // Use PNG to preserve transparency

        return filename
    }

    fun getProfilePicFileName(userId: String, group: Boolean): String {
        return "${if (group) "group" else "user"}_${userId}_profilepic.jpg"
    }

    fun deleteProfilePic(id: String, group: Boolean) {
        File("/app/images", getProfilePicFileName(id, group)).delete()
    }


    /**
     * Reads a stored chat image and its real format. Images keep the extension of their format;
     * those stored before the change to as-is storage are all PNG (`.png`), so every known
     * extension is tried.
     */
    fun loadMessageImage(messageId: ObjectId, group: Boolean): StoredMessageImage {
        val imagesDir = File(MESSAGE_IMAGES_DIR)
        for (format in MessageImageFormat.entries) {
            val file = File(imagesDir, getImageMessageFileName(messageId, group, format))
            if (file.exists()) return StoredMessageImage(file.readBytes(), format)
        }

        throw IllegalArgumentException("Image file not found: ${messageId.toHexString()}")
    }

    /**
     * Stores a chat image exactly as uploaded: full resolution, EXIF and GPS metadata kept (user
     * decision 2026-11-27). The size is capped by ValidationUtils.MAX_MESSAGE_IMAGE_SIZE and the
     * bytes are checked by [checkMessageImage]; MediaRetentionService removes the file after the
     * retention time.
     */
    fun saveImageMessage(image: MultipartFile, messageId: ObjectId, group: Boolean): String {
        val imageBytes = image.bytes
        val format = checkMessageImage(imageBytes)

        val imagesDir = File(MESSAGE_IMAGES_DIR)
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }

        // Downscaling is off. To shrink chat images again (e.g. the disk fills up), re-enable these
        // two lines and remove the two lines below them: that stores an upright PNG of at most
        // MAX_FILE_SIZE_BYTES, without metadata.
        //val targetFile = File(imagesDir, getImageMessageFileName(messageId, group, MessageImageFormat.PNG))
        //ImageIO.write(downscaleIfNeeded(readUprightImage(image)), "PNG", targetFile)
        val targetFile = File(imagesDir, getImageMessageFileName(messageId, group, format))
        targetFile.writeBytes(imageBytes)

        return targetFile.name
    }

    /**
     * The client is not trusted: the bytes must really be a JPEG, PNG or GIF (whatever content
     * type the upload claims), have at most [MAX_MESSAGE_IMAGE_PIXELS] pixels (a small compressed
     * file can unpack to gigabytes on the receiving phones) and decode. The check decodes
     * subsampled, so a big photo never needs its full size in memory.
     * @return the real format of the bytes
     */
    fun checkMessageImage(imageBytes: ByteArray): MessageImageFormat {
        val format = MessageImageFormat.detect(imageBytes)
            ?: throw IllegalArgumentException("Unsupported image format")
        val reader = ImageIO.getImageReadersByFormatName(format.imageIoName).asSequence().firstOrNull()
            ?: throw IllegalArgumentException("Unsupported image format")

        try {
            MemoryCacheImageInputStream(ByteArrayInputStream(imageBytes)).use { input ->
                reader.input = input

                val (width, height) = try {
                    reader.getWidth(0) to reader.getHeight(0)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid image data")
                }
                require(width > 0 && height > 0) { "Invalid image data" }
                require(width.toLong() * height <= MAX_MESSAGE_IMAGE_PIXELS) { "Image has too many pixels" }

                val step = maxOf(1, maxOf(width, height) / CHECK_DECODE_EDGE_PX)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                try {
                    reader.read(0, param)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid image data")
                }
            }
        } finally {
            reader.dispose()
        }

        return format
    }

    fun getImageMessageFileName(messageId: ObjectId, group: Boolean, format: MessageImageFormat): String {
        return "${if (group) "group" else "user"}_image_${messageId.toHexString()}_message.${format.extension}"
    }

    fun deleteMessageImage(messageId: ObjectId, group: Boolean) {
        MessageImageFormat.entries.forEach { format ->
            File(MESSAGE_IMAGES_DIR, getImageMessageFileName(messageId, group, format)).delete()
        }
    }



    /**
     * Uploads are stored as PNG, which cannot carry an orientation tag, so a rotated JPEG has
     * to have its EXIF orientation applied to the pixels while reading it.
     */
    private fun readUprightImage(image: MultipartFile): BufferedImage {
        val imageBytes = image.bytes
        val decodedImage = ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: throw IllegalArgumentException("Invalid image data")

        return applyExifOrientation(decodedImage, readJpegExifOrientation(imageBytes))
    }

    private fun makeImageRound(inputImage: BufferedImage): BufferedImage {
        val size = minOf(inputImage.width, inputImage.height)

        // Crop to square first (center crop)
        val x = (inputImage.width - size) / 2
        val y = (inputImage.height - size) / 2
        val squared = inputImage.getSubimage(x, y, size, size)

        // Create circular mask
        val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = output.createGraphics()

        // Enable anti-aliasing for smooth edges
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Create circular clip
        g2.clip = Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
        g2.drawImage(squared, 0, 0, null)
        g2.dispose()

        return output
    }

    private fun downscaleIfNeeded(image: BufferedImage): BufferedImage {
        // First check current size
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)

        if (baos.size() <= MAX_FILE_SIZE_BYTES) {
            return image // No downscaling needed
        }

        // Calculate scale factor needed
        val ratio = sqrt(MAX_FILE_SIZE_BYTES.toDouble() / baos.size())
        val newWidth = (image.width * ratio * 0.9).toInt() // 0.9 for safety margin
        val newHeight = (image.height * ratio * 0.9).toInt()

        // Create downscaled image
        val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB)
        val g2 = scaled.createGraphics()

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        g2.drawImage(image, 0, 0, newWidth, newHeight, null)
        g2.dispose()

        return scaled
    }
}

/** Formats a chat image can be stored in, told apart by the file's own first bytes. */
enum class MessageImageFormat(
    val extension: String,
    val mediaType: MediaType,
    val imageIoName: String,
    private val signatures: List<ByteArray>,
) {
    JPEG("jpg", MediaType.IMAGE_JPEG, "jpeg", listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))),
    PNG("png", MediaType.IMAGE_PNG, "png", listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))),
    GIF("gif", MediaType.IMAGE_GIF, "gif", listOf("GIF87a".toByteArray(), "GIF89a".toByteArray()));

    companion object {
        fun detect(bytes: ByteArray): MessageImageFormat? = entries.firstOrNull { format ->
            format.signatures.any { signature ->
                bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }
            }
        }
    }
}

/** A chat image file as stored, with the format its bytes really have. */
class StoredMessageImage(val bytes: ByteArray, val format: MessageImageFormat)
