package com.makewheels.dashcam;

import static org.junit.Assert.*;

import org.junit.Test;

public class BatchEstimateTest {
  @Test
  public void fileAndPhaseTransitionsKeepWholeBatchEstimate() {
    BatchEstimate estimate = new BatchEstimate();
    assertEquals(-1, estimate.remaining(1, 0, 0, 3000, true));
    assertEquals(29, estimate.remaining(1, 1000, 100, 3000, true));
    // A new file starts at zero locally, but total work continues monotonically.
    assertEquals(28, estimate.remaining(1, 2000, 200, 3000, true));
    assertEquals(28, estimate.remaining(1, 3000, 200, 3000, true));
    assertEquals(29, estimate.remaining(1, 4000, 300, 3000, true));
  }

  @Test
  public void pauseDoesNotCountIdleTimeAsTransferTime() {
    BatchEstimate estimate = new BatchEstimate();
    estimate.remaining(1, 0, 0, 1000, true);
    assertEquals(9, estimate.remaining(1, 1000, 100, 1000, true));
    assertEquals(9, estimate.remaining(1, 60000, 100, 1000, false));
    assertEquals(8, estimate.remaining(1, 61000, 200, 1000, true));
  }

  @Test
  public void newTaskResetsAndCompletionHasZeroRemaining() {
    BatchEstimate estimate = new BatchEstimate();
    estimate.remaining(1, 0, 0, 1000, true);
    estimate.remaining(1, 1000, 100, 1000, true);
    assertEquals(-1, estimate.remaining(2, 2000, 500, 1000, true));
    assertEquals(4, estimate.remaining(2, 3000, 600, 1000, true));
    assertEquals(0, estimate.remaining(2, 7000, 1000, 1000, false));
  }
}
