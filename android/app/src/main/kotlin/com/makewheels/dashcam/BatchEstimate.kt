package com.makewheels.dashcam

/** Samples monotonic whole-task work; file and phase changes do not reset the estimate. */
internal class BatchEstimate {
  var generation = Long.MIN_VALUE
  private var sampledAt = 0L
  private var sampledWork = 0.0
  private var initialWork = 0.0
  var rate = 0.0
  private var lastUpdated = 0L
  private var runningMillis = 0L

  fun remaining(task: Long, now: Long, completed: Double, total: Double, running: Boolean): Long {
    if (generation != task) {
      generation = task
      sampledAt = now
      sampledWork = completed
      initialWork = completed
      lastUpdated = now
      runningMillis = 0
      rate = 0.0
    }
    if (running) runningMillis += maxOf(0L, now - lastUpdated)
    lastUpdated = now
    if (total > 0 && completed >= total) return 0
    if (!running) {
      sampledAt = now
      sampledWork = completed
    } else if (now - sampledAt >= 500) {
      val delta = completed - sampledWork
      if (delta > 0) {
        val sample = maxOf(0.0, completed - initialWork) * 1000 / maxOf(1L, runningMillis)
        rate = if (rate == 0.0) sample else rate * .75 + sample * .25
      }
      // Preserve the latest measured rate during a phase transition or a short stall.
      sampledAt = now
      sampledWork = completed
    }
    return if (rate > 0) maxOf(1L, Math.ceil(maxOf(0.0, total - completed) / rate).toLong()) else -1
  }
}
