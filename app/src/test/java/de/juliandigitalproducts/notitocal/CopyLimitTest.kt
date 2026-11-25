package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.capture.MediaProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CopyLimitTest {
  @Test fun copiesUpToTheLimit() {
    val out = ByteArrayOutputStream()
    assertEquals(100L, MediaProcessor.copyLimited(ByteArrayInputStream(ByteArray(100)), out, 100))
    assertEquals(100, out.size())
  }

  @Test fun stopsAboveTheLimit() {
    assertThrows(IOException::class.java) {
      MediaProcessor.copyLimited(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100)
    }
  }
}
