package com.lerchenflo.schneaggchatv3server.util

import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

@Component
class ImageManager {

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 700 * 1024 // 700KB
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
        val originalImage = ImageIO.read(image.inputStream)
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


    fun loadMessageImageFromFile(fileName: String): ByteArray {
        val imagesDir = File("/app/images_messages")
        val targetFile = File(imagesDir, fileName)

        if (!targetFile.exists()) {
            throw IllegalArgumentException("Image file not found: $fileName")
        }

        return targetFile.readBytes()
    }

    fun saveImageMessage(image: MultipartFile, messageId: ObjectId, group: Boolean): String {
        val filename = getImageMessageFileName(messageId, group)
        val downscaledImage = downscaleIfNeeded(ImageIO.read(image.inputStream))

        val imagesDir = File("/app/images_messages")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val targetFile = File(imagesDir, filename)
        ImageIO.write(downscaledImage, "PNG", targetFile)

        //println("Image saved in storage: $filename")

        return filename
    }

    fun getImageMessageFileName(messageId: ObjectId, group: Boolean): String {
        return "${if (group) "group" else "user"}_image_${messageId.toHexString()}_message.png"
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