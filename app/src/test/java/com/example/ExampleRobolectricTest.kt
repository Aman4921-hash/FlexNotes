package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.NoteViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FlexNotes Student", appName)
  }

  @Test
  fun `test createNote flow`() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = NoteViewModel(app)
    assertNotNull(viewModel)
    
    // Attempt creating a note
    viewModel.createNote(
      title = "Math proof notebook",
      coverTitle = "Math Proofs",
      folderId = null,
      canvasType = "PAGES",
      paperStyle = "BLANK",
      coverColorHex = "#E3F2FD"
    )
  }
}
