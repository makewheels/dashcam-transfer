package com.makewheels.dashcam;

/** Progress rules shared by the task card and file rows. */
final class TransferProgress {
  enum Phase {
    IDLE,
    COPY,
    VERIFY_SOURCE,
    VERIFY_LOCAL,
    REMOVE_SOURCE,
    VERIFY_UPLOAD,
    UPLOAD,
    VERIFY_CLOUD,
    RECYCLE
  }

  static double fraction(long done, long total) {
    return total <= 0 ? 0 : Math.max(0, Math.min(1, (double) done / total));
  }

  static double imported(Phase phase, long done, long total) {
    double f = fraction(done, total);
    return switch (phase) {
      case COPY -> f / 3;
      case VERIFY_SOURCE -> (1 + f) / 3;
      case VERIFY_LOCAL -> (2 + f) / 3;
      case REMOVE_SOURCE -> 1;
      default -> 0;
    };
  }

  static String duration(long seconds) {
    if (seconds < 0) return "计算中";
    if (seconds < 60) return seconds + " 秒";
    if (seconds < 3600) return seconds / 60 + " 分 " + seconds % 60 + " 秒";
    return seconds / 3600 + " 时 " + seconds % 3600 / 60 + " 分";
  }

  static Phase detect(String label) {
    if (label.startsWith("复制 ")) return Phase.COPY;
    if (label.startsWith("校验 TF")) return Phase.VERIFY_SOURCE;
    if (label.startsWith("校验手机")) return Phase.VERIFY_LOCAL;
    if (label.startsWith("删除前")) return Phase.REMOVE_SOURCE;
    if (label.startsWith("上传前")) return Phase.VERIFY_UPLOAD;
    if (label.startsWith("上传 ·")) return Phase.UPLOAD;
    if (label.startsWith("云端")) return Phase.VERIFY_CLOUD;
    if (label.startsWith("回收前") || label.startsWith("移入回收站")) return Phase.RECYCLE;
    return Phase.IDLE;
  }

  static String label(Phase phase) {
    return switch (phase) {
      case COPY -> "复制到手机";
      case VERIFY_SOURCE -> "读取并校验 TF 卡";
      case VERIFY_LOCAL -> "校验手机副本";
      case REMOVE_SOURCE -> "核对并清理 TF 卡";
      case VERIFY_UPLOAD -> "上传前校验";
      case UPLOAD -> "上传视频";
      case VERIFY_CLOUD -> "云端完整性校验";
      case RECYCLE -> "移入手机回收站";
      default -> "准备中";
    };
  }
}
