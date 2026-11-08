package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.util.AudioGenerator
import com.example.util.ImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Image to Shorts", appName)
  }

  @Test
  fun `silent audio wav file generated with correct 3 second length`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wav = AudioGenerator.getOrCreateSilentWavFile(context)
    assertTrue("Audio file must exist", wav.exists())
    // 44 bytes header + 44100 * 3 * 2 bytes = 264,644 bytes
    assertEquals(264644L, wav.length())
  }

  @Test
  fun `decode 945x1663 png succeeds and adheres to 2048 max dimension`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val original = Bitmap.createBitmap(945, 1663, Bitmap.Config.ARGB_8888)
    val testFile = File(context.cacheDir, "sample_945x1663.png")
    FileOutputStream(testFile).use { out ->
      original.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    original.recycle()

    val uri = Uri.fromFile(testFile)
    val decoded = ImageProcessor.decodeAndOrient(context, uri, "sample_945x1663.png", "image/png")
    assertTrue(decoded.width > 0)
    assertTrue(decoded.height > 0)
    assertTrue(maxOf(decoded.width, decoded.height) <= 2048)
    decoded.recycle()
    testFile.delete()
  }
}
