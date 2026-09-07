package com.lerchenflo.schneaggchatv3server.util

import java.awt.image.BufferedImage

const val EXIF_ORIENTATION_NORMAL = 1

private const val JPEG_MARKER = 0xFF
private const val JPEG_START_OF_IMAGE = 0xD8
private const val JPEG_START_OF_SCAN = 0xDA
private const val JPEG_APP1 = 0xE1
private const val TIFF_TAG_ORIENTATION = 0x0112
private const val EXIF_HEADER_LENGTH = 6
private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif  "
private val MIRRORED_ORIENTATIONS = setOf(2, 4, 5, 7)

/**
 * ImageIO drops EXIF metadata and the images are stored as PNG, which has no orientation tag
 * at all, so a rotated upload has to be straightened before it is written. Walks the JPEG
 * marker segments to the APP1 block and pulls tag 0x0112 out of its TIFF directory.
 * Returns [EXIF_ORIENTATION_NORMAL] for anything that is not a rotated JPEG.
 */
fun readJpegExifOrientation(imageBytes: ByteArray): Int {
    if (!startsWithJpegHeader(imageBytes)) return EXIF_ORIENTATION_NORMAL

    var offset = 2
    while (offset + 4 <= imageBytes.size) {
        if (imageBytes.unsigned(offset) != JPEG_MARKER) return EXIF_ORIENTATION_NORMAL

        val marker = imageBytes.unsigned(offset + 1)
        if (marker == JPEG_START_OF_SCAN) return EXIF_ORIENTATION_NORMAL

        val segmentLength = imageBytes.readUShort(offset + 2, littleEndian = false)
        if (segmentLength < 2) return EXIF_ORIENTATION_NORMAL

        val segmentStart = offset + 4
        if (marker == JPEG_APP1 && hasExifHeader(imageBytes, segmentStart)) {
            return readOrientationFromTiff(imageBytes, segmentStart + EXIF_HEADER_LENGTH)
        }

        offset += 2 + segmentLength
    }

    return EXIF_ORIENTATION_NORMAL
}

/**
 * Bakes [orientation] into the pixels. Orientations 2/4/5/7 are mirrored first, then every
 * orientation but the normal one is rotated to match.
 */
fun applyExifOrientation(image: BufferedImage, orientation: Int): BufferedImage {
    val mirrored = if (orientation in MIRRORED_ORIENTATIONS) mirrorHorizontally(image) else image

    return when (orientation) {
        3, 4 -> rotate(mirrored, degrees = 180)
        6, 7 -> rotate(mirrored, degrees = 90)
        5, 8 -> rotate(mirrored, degrees = 270)
        else -> mirrored
    }
}

private fun startsWithJpegHeader(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 4 &&
            imageBytes.unsigned(0) == JPEG_MARKER &&
            imageBytes.unsigned(1) == JPEG_START_OF_IMAGE

private fun hasExifHeader(imageBytes: ByteArray, offset: Int): Boolean {
    if (offset + EXIF_HEADER_LENGTH > imageBytes.size) return false
    return EXIF_HEADER.indices.all { imageBytes[offset + it] == EXIF_HEADER[it] }
}

private fun readOrientationFromTiff(imageBytes: ByteArray, tiffStart: Int): Int {
    if (tiffStart + 8 > imageBytes.size) return EXIF_ORIENTATION_NORMAL

    val littleEndian = when (imageBytes.unsigned(tiffStart)) {
        0x49 -> true
        0x4D -> false
        else -> return EXIF_ORIENTATION_NORMAL
    }

    val directoryStart = tiffStart + imageBytes.readInt(tiffStart + 4, littleEndian)
    if (directoryStart + 2 > imageBytes.size) return EXIF_ORIENTATION_NORMAL

    val entryCount = imageBytes.readUShort(directoryStart, littleEndian)
    for (entry in 0 until entryCount) {
        val entryStart = directoryStart + 2 + entry * 12
        if (entryStart + 12 > imageBytes.size) return EXIF_ORIENTATION_NORMAL

        if (imageBytes.readUShort(entryStart, littleEndian) == TIFF_TAG_ORIENTATION) {
            return imageBytes.readUShort(entryStart + 8, littleEndian)
        }
    }

    return EXIF_ORIENTATION_NORMAL
}

private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.readUShort(offset: Int, littleEndian: Boolean): Int =
    if (littleEndian) unsigned(offset) or (unsigned(offset + 1) shl 8)
    else (unsigned(offset) shl 8) or unsigned(offset + 1)

private fun ByteArray.readInt(offset: Int, littleEndian: Boolean): Int =
    if (littleEndian) {
        unsigned(offset) or (unsigned(offset + 1) shl 8) or
                (unsigned(offset + 2) shl 16) or (unsigned(offset + 3) shl 24)
    } else {
        (unsigned(offset) shl 24) or (unsigned(offset + 1) shl 16) or
                (unsigned(offset + 2) shl 8) or unsigned(offset + 3)
    }

private fun mirrorHorizontally(image: BufferedImage): BufferedImage {
    val mirrored = BufferedImage(image.width, image.height, imageTypeOf(image))
    val graphics = mirrored.createGraphics()
    graphics.drawImage(image, image.width, 0, -image.width, image.height, null)
    graphics.dispose()
    return mirrored
}

private fun rotate(image: BufferedImage, degrees: Int): BufferedImage {
    val swapsAxes = degrees != 180
    val rotated = BufferedImage(
        if (swapsAxes) image.height else image.width,
        if (swapsAxes) image.width else image.height,
        imageTypeOf(image)
    )

    val graphics = rotated.createGraphics()
    graphics.translate((rotated.width - image.width) / 2.0, (rotated.height - image.height) / 2.0)
    graphics.rotate(Math.toRadians(degrees.toDouble()), image.width / 2.0, image.height / 2.0)
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return rotated
}

private fun imageTypeOf(image: BufferedImage): Int =
    if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
