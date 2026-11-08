package com.example.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

object VideoConverter {

    /**
     * Converts a single image [imageUri] into an individual 3-second 1080x1920 30fps H.264 MP4
     * with silent AAC audio and saves it into MediaStore under Movies/ImageToShorts.
     */
    suspend fun convertImageToShort(context: Context, imageUri: Uri): Result<Uri> = withContext(Dispatchers.Default) {
        val baseName = ImageProcessor.getDisplayName(context, imageUri)

        // 1. Decode & orient image (downsampling if huge to avoid OOM)
        val sourceBitmap = ImageProcessor.decodeAndOrient(context, imageUri)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not decode image: $baseName"))

        // 2. Generate composite 1080x1920 frame with blurred background
        val compositeFrameFile = try {
            ImageProcessor.createShortsFrame(context, sourceBitmap)
        } finally {
            sourceBitmap.recycle()
        }

        // 3. Prepare silent audio WAV file
        val silentAudioFile = AudioGenerator.getOrCreateSilentWavFile(context)

        // 4. Encode using Jetpack Media3 Transformer
        val tempOutputFile = File(
            context.cacheDir,
            "shorts_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.mp4"
        )

        val encodeResult = encodeWithTransformer(
            context = context,
            imageFile = compositeFrameFile,
            audioFile = silentAudioFile,
            outputFile = tempOutputFile
        )

        // Clean up composite temporary image file immediately
        compositeFrameFile.delete()

        if (encodeResult.isFailure) {
            tempOutputFile.delete()
            return@withContext Result.failure(encodeResult.exceptionOrNull() ?: Exception("Unknown encoding error"))
        }

        // 5. Save to MediaStore under Movies/ImageToShorts
        return@withContext try {
            val savedUri = saveToMediaStore(context, tempOutputFile, baseName)
            tempOutputFile.delete()
            Result.success(savedUri)
        } catch (e: Exception) {
            tempOutputFile.delete()
            Result.failure(e)
        }
    }

    private suspend fun encodeWithTransformer(
        context: Context,
        imageFile: File,
        audioFile: File,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            try {
                // Video: 3000ms duration, 30fps, 1080x1920
                val imageMediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(imageFile))
                    .setImageDurationMs(3000)
                    .build()

                val presentationEffect = Presentation.createForWidthAndHeight(
                    1080,
                    1920,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )

                val videoEditedItem = EditedMediaItem.Builder(imageMediaItem)
                    .setFrameRate(30)
                    .setEffects(
                        Effects(
                            /* audioProcessors = */ emptyList(),
                            /* videoEffects = */ listOf(presentationEffect)
                        )
                    )
                    .build()

                val videoSequence = EditedMediaItemSequence(videoEditedItem)

                // Audio: 3-second silent audio track
                val audioMediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(audioFile))
                    .build()

                val audioEditedItem = EditedMediaItem.Builder(audioMediaItem)
                    .setRemoveVideo(true)
                    .build()

                val audioSequence = EditedMediaItemSequence(audioEditedItem)

                // Composition with concurrent video and audio tracks
                val composition = Composition.Builder(videoSequence, audioSequence).build()

                val transformer = Transformer.Builder(context)
                    .setLooper(Looper.getMainLooper())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) {
                                continuation.resume(Result.success(Unit))
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(exportException))
                            }
                        }
                    })
                    .build()

                continuation.invokeOnCancellation {
                    transformer.cancel()
                }

                transformer.start(composition, outputFile.absolutePath)
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }

    private fun saveToMediaStore(context: Context, sourceVideoFile: File, baseName: String): Uri {
        val resolver = context.contentResolver
        val fileName = "$baseName.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ImageToShorts")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry for $fileName")

        resolver.openOutputStream(videoUri)?.use { output ->
            sourceVideoFile.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        } ?: throw IllegalStateException("Failed to write video data to MediaStore")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(videoUri, values, null, null)
        }

        return videoUri
    }
}
