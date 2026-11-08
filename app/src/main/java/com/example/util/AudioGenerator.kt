package com.example.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates a 3-second silent 44.1kHz 16-bit mono PCM WAV file.
 */
object AudioGenerator {

    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val DURATION_SECONDS = 3
    private const val TOTAL_SAMPLES = SAMPLE_RATE * DURATION_SECONDS
    private const val DATA_SIZE = TOTAL_SAMPLES * CHANNELS * (BITS_PER_SAMPLE / 8) // 132,300 * 2 = 264,600 bytes
    private const val HEADER_SIZE = 44

    fun getOrCreateSilentWavFile(context: Context): File {
        val audioFile = File(context.cacheDir, "silent_3s.wav")
        if (audioFile.exists() && audioFile.length() == (HEADER_SIZE + DATA_SIZE).toLong()) {
            return audioFile
        }

        FileOutputStream(audioFile).use { output ->
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            
            // "RIFF" chunk descriptor
            header.put("RIFF".toByteArray())
            header.putInt(36 + DATA_SIZE) // overall size
            header.put("WAVE".toByteArray())

            // "fmt " sub-chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1.toShort()) // AudioFormat 1 = PCM
            header.putShort(CHANNELS.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)) // ByteRate
            header.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort()) // BlockAlign
            header.putShort(BITS_PER_SAMPLE.toShort())

            // "data" sub-chunk
            header.put("data".toByteArray())
            header.putInt(DATA_SIZE)

            output.write(header.array())

            // Write 3 seconds of zero PCM bytes (silence) in 4KB chunks
            val buffer = ByteArray(4096)
            var bytesWritten = 0
            while (bytesWritten < DATA_SIZE) {
                val toWrite = minOf(buffer.size, DATA_SIZE - bytesWritten)
                output.write(buffer, 0, toWrite)
                bytesWritten += toWrite
            }
            output.flush()
        }

        return audioFile
    }
}
