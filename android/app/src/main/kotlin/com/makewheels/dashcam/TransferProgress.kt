package com.makewheels.dashcam

/** Progress rules shared by the task card and file rows. */
internal object TransferProgress {
  enum class Phase { IDLE, COPY, VERIFY_SOURCE, VERIFY_LOCAL, REMOVE_SOURCE, VERIFY_UPLOAD, UPLOAD, VERIFY_CLOUD, RECYCLE }

  @JvmStatic
  fun fraction(done: Long, total: Long): Double =
      if (total <= 0) 0.0 else maxOf(0.0, minOf(1.0, done.toDouble() / total))

  @JvmStatic
  fun imported(phase: Phase, done: Long, total: Long): Double {
    val f = fraction(done, total)
    return when (phase) {
      Phase.COPY -> f / 3
      Phase.VERIFY_SOURCE -> (1 + f) / 3
      Phase.VERIFY_LOCAL -> (2 + f) / 3
      Phase.REMOVE_SOURCE -> 1.0
      else -> 0.0
    }
  }

  @JvmStatic
  fun duration(seconds: Long): String {
    if (seconds < 0) return "计算中"
    if (seconds < 60) return "$seconds 秒"
    if (seconds < 3600) return "${seconds / 60} 分 ${seconds % 60} 秒"
    return "${seconds / 3600} 时 ${seconds % 3600 / 60} 分"
  }

  @JvmStatic
  fun detect(label: String): Phase {
    if (label.startsWith("复制 ")) return Phase.COPY
    if (label.startsWith("校验 TF")) return Phase.VERIFY_SOURCE
    if (label.startsWith("校验手机")) return Phase.VERIFY_LOCAL
    if (label.startsWith("删除前")) return Phase.REMOVE_SOURCE
    if (label.startsWith("清理手机副本")) return Phase.RECYCLE
    if (label.startsWith("上传前")) return Phase.VERIFY_UPLOAD
    if (label.startsWith("上传 ·")) return Phase.UPLOAD
    if (label.startsWith("云端")) return Phase.VERIFY_CLOUD
    return Phase.IDLE
  }

  @JvmStatic
  fun label(phase: Phase): String = when (phase) {
    Phase.COPY -> "复制到手机"
    Phase.VERIFY_SOURCE -> "读取并校验 TF 卡"
    Phase.VERIFY_LOCAL -> "校验手机副本"
    Phase.REMOVE_SOURCE -> "核对并清理 TF 卡"
    Phase.VERIFY_UPLOAD -> "上传前校验"
    Phase.UPLOAD -> "上传视频"
    Phase.VERIFY_CLOUD -> "云端完整性校验"
    Phase.RECYCLE -> "删除手机副本"
    else -> "准备中"
  }
}
