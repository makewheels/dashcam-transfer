package com.makewheels.dashcam;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class Api {
  static JSONObject call(String method, String path, JSONObject data) throws Exception {
    if (BuildConfig.API_URL.isEmpty() || BuildConfig.APP_TOKEN.isEmpty())
      throw new IOException("尚未配置服务端");
    HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.API_URL + path).openConnection();
    c.setRequestMethod(method);
    c.setConnectTimeout(20000);
    c.setReadTimeout(60000);
    c.setRequestProperty("Authorization", "Bearer " + BuildConfig.APP_TOKEN);
    try {
      if (data != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) {
          o.write(data.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = c.getResponseCode();
      InputStream input = code < 400 ? c.getInputStream() : c.getErrorStream();
      if (input == null) throw new IOException("服务端返回 " + code);
      String body;
      try (input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          bytes.write(buffer, 0, n);
          if (bytes.size() > 2 * 1024 * 1024) throw new IOException("响应过大");
        }
        body = bytes.toString("UTF-8");
      }
      JSONObject result = new JSONObject(body);
      if (code >= 400) {
        String reason = result.optString("error", "");
        String message =
            switch (reason) {
              case "another phone is uploading; retry later" -> "另一台手机正在上传这个视频，稍后会继续";
              case "cloud integrity verification failed; local file must be kept" ->
                  "云端校验未通过，手机视频已保留，请重试";
              case "cloud file no longer exists", "not found" -> "云端视频已不存在";
              case "upload session unavailable" -> "上传进度暂时无法恢复，请重新点击继续";
              case "parts incomplete", "part size mismatch" -> "部分内容尚未传完整，重试会从断点继续";
              case "unauthorized" -> "应用连接授权失效，请更新应用后再试";
              default -> "云端暂时无法处理，请稍后重试（" + code + "）";
            };
        throw new IOException(message);
      }
      return result;
    } finally {
      c.disconnect();
    }
  }
}
