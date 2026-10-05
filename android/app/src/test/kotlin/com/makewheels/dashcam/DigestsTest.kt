package com.makewheels.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class DigestsTest {
  @Test
  fun matchesOssCrc64AndSha256() {
    val digest = Digests.hash(ByteArrayInputStream("123456789".toByteArray(Charsets.UTF_8))) { }
    assertEquals("11051210869376104954", digest[1])
    assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", digest[0])
    assertEquals("9", digest[2])
  }

  @Test
  fun usesUnsignedCrcAndHandlesEmptyFile() {
    assertEquals(
        "16194522821198029556",
        Digests.hash(ByteArrayInputStream("hello video".toByteArray(Charsets.UTF_8))) { }[1])
    assertEquals("0", Digests.hash(ByteArrayInputStream(ByteArray(0))) { }[1])
  }
}
