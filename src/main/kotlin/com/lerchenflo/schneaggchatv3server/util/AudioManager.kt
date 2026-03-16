package com.lerchenflo.schneaggchatv3server.util

import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.io.File
import javax.imageio.ImageIO

@Component
class AudioManager {
    companion object {
        // todo maby change to be smaller for audio messages
        private const val MAX_FILE_SIZE_BYTES = 700 * 1024 // 700KB
    }

    fun loadMessageAudioFromFile(fileName: String): ByteArray {
        val audiosDir = File("/app/audios_messages")
        val targetFile = File(audiosDir, fileName)

        if (!targetFile.exists()) {
            throw IllegalArgumentException("Audio file not found: $fileName")
        }

        return targetFile.readBytes()
    }

    fun saveAudioMessage(audio: MultipartFile, messageId: ObjectId, group: Boolean): String {
        val filename = getAudioMessageFileName(messageId, group)
        //val downscaledImage = downscaleIfNeeded(ImageIO.read(image.inputStream))

        val audiosDir = File("/app/audios_messages")
        if (!audiosDir.exists()) {
            audiosDir.mkdirs()
        }
        val targetFile = File(audiosDir, filename)
        audio.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        //println("Image saved in storage: $filename")

        return filename
    }

    fun getAudioMessageFileName(messageId: ObjectId, group: Boolean): String {
        val prefix = if (group) "group" else "user"
        return "${prefix}_audio_${messageId.toHexString()}_message.m4a"
    }
}