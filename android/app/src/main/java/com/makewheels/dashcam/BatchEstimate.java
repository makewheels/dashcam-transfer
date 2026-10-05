package com.makewheels.dashcam;

/** Samples monotonic whole-task work; file and phase changes do not reset the estimate. */
final class BatchEstimate {
  long generation = Long.MIN_VALUE, sampledAt = 0;
  double sampledWork = 0, initialWork = 0, rate = 0;
  long lastUpdated = 0, runningMillis = 0;

  long remaining(long task, long now, double completed, double total, boolean running) {
    if (generation != task) {
      generation = task;
      sampledAt = now;
      sampledWork = initialWork = completed;
      lastUpdated = now;
      runningMillis = 0;
      rate = 0;
    }
    if (running) runningMillis += Math.max(0, now - lastUpdated);
    lastUpdated = now;
    if (total > 0 && completed >= total) return 0;
    if (!running) {
      sampledAt = now;
      sampledWork = completed;
    } else if (now - sampledAt >= 500) {
      double delta = completed - sampledWork;
      if (delta > 0) {
        double sample = Math.max(0, completed - initialWork) * 1000 / Math.max(1, runningMillis);
        rate = rate == 0 ? sample : rate * .75 + sample * .25;
      }
      // Preserve the latest measured rate during a phase transition or a short stall.
      sampledAt = now;
      sampledWork = completed;
    }
    return rate > 0 ? Math.max(1, (long) Math.ceil(Math.max(0, total - completed) / rate)) : -1;
  }
}
