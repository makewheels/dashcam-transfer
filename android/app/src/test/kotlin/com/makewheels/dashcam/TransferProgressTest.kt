package com.makewheels.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferProgressTest {
  @Test
  fun importDoesNotReachCompleteBeforeBothHashesFinish() {
    assertEquals(1.0 / 3, TransferProgress.imported(TransferProgress.Phase.COPY, 100, 100), .0001)
    assertEquals(
        2.0 / 3, TransferProgress.imported(TransferProgress.Phase.VERIFY_SOURCE, 100, 100), .0001)
    assertEquals(
        5.0 / 6, TransferProgress.imported(TransferProgress.Phase.VERIFY_LOCAL, 50, 100), .0001)
    assertEquals(1.0, TransferProgress.imported(TransferProgress.Phase.VERIFY_LOCAL, 100, 100), .0001)
  }

  @Test
  fun progressCannotOverflowAndUnknownEtaIsExplicit() {
    assertEquals(0.0, TransferProgress.fraction(10, 0), 0.0)
    assertEquals(0.0, TransferProgress.fraction(-10, 100), 0.0)
    assertEquals(1.0, TransferProgress.fraction(110, 100), 0.0)
    assertEquals("计算中", TransferProgress.duration(-1))
    assertEquals("1 分 5 秒", TransferProgress.duration(65))
  }
}
