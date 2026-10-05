package com.makewheels.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchEstimateTest {
  @Test
  fun fileAndPhaseTransitionsKeepWholeBatchEstimate() {
    val estimate = BatchEstimate()
    assertEquals(-1L, estimate.remaining(1, 0, 0.0, 3000.0, true))
    assertEquals(29L, estimate.remaining(1, 1000, 100.0, 3000.0, true))
    // A new file starts at zero locally, but total work continues monotonically.
    assertEquals(28L, estimate.remaining(1, 2000, 200.0, 3000.0, true))
    assertEquals(28L, estimate.remaining(1, 3000, 200.0, 3000.0, true))
    assertEquals(29L, estimate.remaining(1, 4000, 300.0, 3000.0, true))
  }

  @Test
  fun pauseDoesNotCountIdleTimeAsTransferTime() {
    val estimate = BatchEstimate()
    estimate.remaining(1, 0, 0.0, 1000.0, true)
    assertEquals(9L, estimate.remaining(1, 1000, 100.0, 1000.0, true))
    assertEquals(9L, estimate.remaining(1, 60000, 100.0, 1000.0, false))
    assertEquals(8L, estimate.remaining(1, 61000, 200.0, 1000.0, true))
  }

  @Test
  fun newTaskResetsAndCompletionHasZeroRemaining() {
    val estimate = BatchEstimate()
    estimate.remaining(1, 0, 0.0, 1000.0, true)
    estimate.remaining(1, 1000, 100.0, 1000.0, true)
    assertEquals(-1L, estimate.remaining(2, 2000, 500.0, 1000.0, true))
    assertEquals(4L, estimate.remaining(2, 3000, 600.0, 1000.0, true))
    assertEquals(0L, estimate.remaining(2, 7000, 1000.0, 1000.0, false))
  }
}
