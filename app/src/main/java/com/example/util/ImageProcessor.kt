package com.example.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {

    private const val TARGET_WIDTH = 1080
    private const val TARGET_HEIGHT = 1920
    private const val BLUR_DOWNSCALE_W = 135
    private const val BLUR_DOWNSCALE_H = 240
    private const val MAX_LONGEST_SIDE = 2048

    /**
     * Resolves display name from Uri or falls back to timestamp-based name.
     */
    fun getDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = cursor.getString(index)
                        }
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        if (name.isNullOrBlank()) {
            name = "image_${System.currentTimeMillis()}"
        }
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) name.substring(0, dotIndex) else name
    }

    /**
     * Decodes the image at [uri]:
     * 1. Copies the picked Uri to app cache using contentResolver.openInputStream(uri).
     * 2. Decodes using ImageDecoder.createSource(contentResolver, uri) on Android 9+
     *    with fallback to ImageDecoder on the cached file, and BitmapFactory as final fallback.
     * 3. Opens a fresh input stream for each attempt (never reuses a consumed stream).
     * 4. Downsamples so the longest side is at most 2048 px.
     * 5. Handles EXIF orientation.
     * 6. Cleans up cache and includes exact exception text with fileName and mimeType.
     */
    fun decodeAndOrient(context: Context, uri: Uri, fileName: String, mimeType: String): Bitmap {
        val resolver = context.contentResolver

        // 1. Copy uri stream to a temporary cache file to avoid broken/unseekable content streams
        val cachedFile = File(context.cacheDir, "picked_${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("contentResolver.openInputStream returned null")

            if (cachedFile.length() == 0L) {
                throw IOException("Copied 0 bytes from input stream (empty or inaccessible stream)")
            }
        } catch (e: Throwable) {
            cachedFile.delete()
            throw IllegalStateException("Failed to read image stream for '$fileName' (MIME: $mimeType): ${e.localizedMessage ?: e.message}", e)
        }

        var decodedBitmap: Bitmap? = null
        var lastError: Throwable? = null

        try {
            // 2. Try ImageDecoder on Android 9+ (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // First try ImageDecoder directly from contentResolver and uri
                try {
                    val source = ImageDecoder.createSource(resolver, uri)
                    decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val width = info.size.width
                        val height = info.size.height
                        val longest = maxOf(width, height)
                        if (longest > MAX_LONGEST_SIDE) {
                            val scale = MAX_LONGEST_SIDE.toFloat() / longest
                            val targetW = (width * scale).roundToInt().coerceAtLeast(1)
                            val targetH = (height * scale).roundToInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e
                    Log.w("ImageProcessor", "ImageDecoder(contentResolver, uri) failed, trying cached file", e)
                    // If uri source fails, try ImageDecoder on the cached file
                    try {
                        val fileSource = ImageDecoder.createSource(cachedFile)
                        decodedBitmap = ImageDecoder.decodeBitmap(fileSource) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val width = info.size.width
                            val height = info.size.height
                            val longest = maxOf(width, height)
                            if (longest > MAX_LONGEST_SIDE) {
                                val scale = MAX_LONGEST_SIDE.toFloat() / longest
                                val targetW = (width * scale).roundToInt().coerceAtLeast(1)
                                val targetH = (height * scale).roundToInt().coerceAtLeast(1)
                                decoder.setTargetSize(targetW, targetH)
                            }
                        }
                    } catch (e2: Throwable) {
                        lastError = e2
                        Log.w("ImageProcessor", "ImageDecoder(cachedFile) failed, falling back to BitmapFactory", e2)
                    }
                }
            }

            // 3. Fallback to BitmapFactory with fresh input stream for each attempt
            if (decodedBitmap == null) {
                try {
                    // Fresh stream 1: read dimensions only
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    FileInputStream(cachedFile).use { stream ->
                        BitmapFactory.decodeStream(stream, null, boundsOptions)
                    }
                    val origW = boundsOptions.outWidth
                    val origH = boundsOptions.outHeight
                    if (origW <= 0 || origH <= 0) {
                        throw IOException("BitmapFactory bounds decode returned non-positive dimensions: ${origW}x${origH}")
                    }

                    // Downsample so the longest side is at most 2048 px
                    val longestSide = maxOf(origW, origH)
                    var sampleSize = 1
                    while ((longestSide / sampleSize) > MAX_LONGEST_SIDE) {
                        sampleSize *= 2
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }

                    // Fresh stream 2: actual bitmap decode
                    val rawBitmap = FileInputStream(cachedFile).use { stream ->
                        BitmapFactory.decodeStream(stream, null, decodeOptions)
                    } ?: throw IOException("BitmapFactory.decodeStream returned null from cached file")

                    // Fresh stream 3: EXIF orientation
                    val orientation = try {
                        FileInputStream(cachedFile).use { stream ->
                            ExifInterface(stream).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                        }
                    } catch (_: Throwable) {
                        ExifInterface.ORIENTATION_NORMAL
                    }

                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            matrix.postRotate(90f)
                            matrix.postScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            matrix.postRotate(270f)
                            matrix.postScale(-1f, 1f)
                        }
                    }

                    decodedBitmap = if (matrix.isIdentity) {
                        rawBitmap
                    } else {
                        val oriented = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        if (oriented != rawBitmap) {
                            rawBitmap.recycle()
                        }
                        oriented
                    }
                } catch (e: Throwable) {
                    lastError = e
                }
            }

            if (decodedBitmap == null) {
                val errText = lastError?.localizedMessage ?: lastError?.message ?: "Bitmap could not be decoded"
                throw IllegalStateException("Failed to decode '$fileName' (MIME: $mimeType): $errText", lastError)
            }

            return decodedBitmap
        } finally {
            cachedFile.delete()
        }
    }

    /**
     * Composites a 1080x1920 (9:16 vertical) frame:
     * - Background: enlarged, cropped-to-fill blurred copy of the image.
     * - Foreground: full image centered without cropping.
     * Writes the resulting JPEG to a temporary file in cacheDir and returns it.
     */
    fun createShortsFrame(context: Context, sourceBitmap: Bitmap): File {
        val composite = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)

        val srcW = sourceBitmap.width.toFloat()
        val srcH = sourceBitmap.height.toFloat()

        // --- BACKGROUND: Blurred enlarged aspect-fill ---
        val lowResBg = Bitmap.createBitmap(BLUR_DOWNSCALE_W, BLUR_DOWNSCALE_H, Bitmap.Config.ARGB_8888)
        val lowResCanvas = Canvas(lowResBg)
        val bgScale = max(BLUR_DOWNSCALE_W / srcW, BLUR_DOWNSCALE_H / srcH)
        val scaledBgW = srcW * bgScale
        val scaledBgH = srcH * bgScale
        val bgLeft = (BLUR_DOWNSCALE_W - scaledBgW) / 2f
        val bgTop = (BLUR_DOWNSCALE_H - scaledBgH) / 2f

        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        lowResCanvas.drawBitmap(
            sourceBitmap,
            null,
            RectF(bgLeft, bgTop, bgLeft + scaledBgW, bgTop + scaledBgH),
            filterPaint
        )

        // Apply fast box blur (3 iterations of horizontal + vertical pass)
        fastBoxBlur(lowResBg, radius = 8, iterations = 3)

        // Draw blurred background stretched across full 1080x1920 canvas
        canvas.drawBitmap(
            lowResBg,
            null,
            Rect(0, 0, TARGET_WIDTH, TARGET_HEIGHT),
            filterPaint
        )
        lowResBg.recycle()

        // Subtle dark scrim over blurred background for contrast
        canvas.drawColor(Color.argb(45, 0, 0, 0))

        // --- FOREGROUND: Full image centered (aspect fit, no cropping) ---
        val fgScale = min(TARGET_WIDTH / srcW, TARGET_HEIGHT / srcH)
        val fgW = srcW * fgScale
        val fgH = srcH * fgScale
        val fgLeft = (TARGET_WIDTH - fgW) / 2f
        val fgTop = (TARGET_HEIGHT - fgH) / 2f

        // Draw soft drop shadow if the image has margins
        if (fgW < TARGET_WIDTH - 8 || fgH < TARGET_HEIGHT - 8) {
            val shadowPaint = Paint().apply {
                color = Color.argb(90, 0, 0, 0)
                maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRect(fgLeft + 6f, fgTop + 10f, fgLeft + fgW - 6f, fgTop + fgH + 8f, shadowPaint)
        }

        // Draw the crisp foreground image
        canvas.drawBitmap(
            sourceBitmap,
            null,
            RectF(fgLeft, fgTop, fgLeft + fgW, fgTop + fgH),
            filterPaint
        )

        // Save composite bitmap to a temporary JPEG file in cacheDir
        val tempFile = File(context.cacheDir, "shorts_frame_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(tempFile).use { out ->
            composite.compress(Bitmap.CompressFormat.JPEG, 96, out)
            out.flush()
        }
        composite.recycle()

        return tempFile
    }

    /**
     * Highly optimized in-place box blur for low-resolution bitmaps.
     */
    private fun fastBoxBlur(bitmap: Bitmap, radius: Int, iterations: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val r = radius.coerceAtLeast(1)
        val div = 2 * r + 1

        val temp = IntArray(w * h)

        for (it in 0 until iterations) {
            // Horizontal pass
            for (y in 0 until h) {
                val rowOffset = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0

                // Initialize window
                for (i in -r..r) {
                    val p = pix[rowOffset + i.coerceIn(0, w - 1)]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }

                for (x in 0 until w) {
                    temp[rowOffset + x] = (-0x1000000) or
                            ((rSum / div) shl 16) or
                            ((gSum / div) shl 8) or
                            (bSum / div)

                    val pLeft = pix[rowOffset + (x - r).coerceIn(0, w - 1)]
                    val pRight = pix[rowOffset + (x + r + 1).coerceIn(0, w - 1)]

                    rSum += ((pRight shr 16) and 0xFF) - ((pLeft shr 16) and 0xFF)
                    gSum += ((pRight shr 8) and 0xFF) - ((pLeft shr 8) and 0xFF)
                    bSum += (pRight and 0xFF) - (pLeft and 0xFF)
                }
            }

            // Vertical pass
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0

                // Initialize window
                for (i in -r..r) {
                    val p = temp[i.coerceIn(0, h - 1) * w + x]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }

                for (y in 0 until h) {
                    pix[y * w + x] = (-0x1000000) or
                            ((rSum / div) shl 16) or
                            ((gSum / div) shl 8) or
                            (bSum / div)

                    val pTop = temp[(y - r).coerceIn(0, h - 1) * w + x]
                    val pBottom = temp[(y + r + 1).coerceIn(0, h - 1) * w + x]

                    rSum += ((pBottom shr 16) and 0xFF) - ((pTop shr 16) and 0xFF)
                    gSum += ((pBottom shr 8) and 0xFF) - ((pTop shr 8) and 0xFF)
                    bSum += (pBottom and 0xFF) - (pTop and 0xFF)
                }
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
