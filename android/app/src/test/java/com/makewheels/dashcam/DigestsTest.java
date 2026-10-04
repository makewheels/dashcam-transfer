package com.makewheels.dashcam;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public final class DigestsTest {
  @Test
  public void matchesOssCrc64AndSha256() throws Exception {
    String[] digest =
        Digests.hash(new ByteArrayInputStream("123456789".getBytes("UTF-8")), n -> {});
    assertEquals("11051210869376104954", digest[1]);
    assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", digest[0]);
    assertEquals("9", digest[2]);
  }

  @Test
  public void usesUnsignedCrcAndHandlesEmptyFile() throws Exception {
    assertEquals(
        "16194522821198029556",
        Digests.hash(new ByteArrayInputStream("hello video".getBytes("UTF-8")), n -> {})[1]);
    assertEquals("0", Digests.hash(new ByteArrayInputStream(new byte[0]), n -> {})[1]);
  }
}
