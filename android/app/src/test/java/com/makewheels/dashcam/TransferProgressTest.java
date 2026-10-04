package com.makewheels.dashcam;

import static org.junit.Assert.*;

import org.junit.Test;

public class TransferProgressTest {
  @Test
  public void importDoesNotReachCompleteBeforeBothHashesFinish() {
    assertEquals(1.0 / 3, TransferProgress.imported(TransferProgress.Phase.COPY, 100, 100), .0001);
    assertEquals(
        2.0 / 3, TransferProgress.imported(TransferProgress.Phase.VERIFY_SOURCE, 100, 100), .0001);
    assertEquals(
        5.0 / 6, TransferProgress.imported(TransferProgress.Phase.VERIFY_LOCAL, 50, 100), .0001);
    assertEquals(
        1, TransferProgress.imported(TransferProgress.Phase.VERIFY_LOCAL, 100, 100), .0001);
  }

  @Test
  public void progressCannotOverflowAndUnknownEtaIsExplicit() {
    assertEquals(0, TransferProgress.fraction(10, 0), 0);
    assertEquals(0, TransferProgress.fraction(-10, 100), 0);
    assertEquals(1, TransferProgress.fraction(110, 100), 0);
    assertEquals("计算中", TransferProgress.duration(-1));
    assertEquals("1 分 5 秒", TransferProgress.duration(65));
  }
}
