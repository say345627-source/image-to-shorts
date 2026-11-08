package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.AudioGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
