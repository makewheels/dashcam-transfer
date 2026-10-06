package com.makewheels.dashcam

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object Api {
  @JvmStatic
  @Throws(Exception::class)
  fun call(method: String, path: String, data: JSONObject?, timeoutMs: Int = 60000): JSONObject {
    if (BuildConfig.API_URL.isEmpty() || BuildConfig.APP_TOKEN.isEmpty())
      throw IOException("尚未配置服务端")
    val c = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = minOf(20000, timeoutMs)
    c.readTimeout = timeoutMs
    c.setRequestProperty("Authorization", "Bearer " + BuildConfig.APP_TOKEN)
    try {
      if (data != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
      }
      val code = c.responseCode
      val input = if (code < 400) c.inputStream else c.errorStream ?: throw IOException("服务端返回 $code")
      val body = input.use {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val n = it.read(buffer)
          if (n == -1) break
          bytes.write(buffer, 0, n)
          if (bytes.size() > 2 * 1024 * 1024) throw IOException("响应过大")
        }
        bytes.toString("UTF-8")
      }
      val result = JSONObject(body)
      if (code >= 400) {
        val message = when (result.optString("error", "")) {
          "another phone is uploading; retry later" -> "另一台手机正在上传这个视频，稍后会继续"
          "cloud integrity verification failed; local file must be kept" -> "云端校验未通过，手机视频已保留，请重试"
          "cloud file no longer exists", "not found" -> "云端视频已不存在"
          "upload session unavailable" -> "上传进度暂时无法恢复，请重新点击继续"
          "parts incomplete", "part size mismatch" -> "部分内容尚未传完整，重试会从断点继续"
          "unauthorized" -> "应用连接授权失效，请更新应用后再试"
          else -> "云端暂时无法处理，请稍后重试（$code）"
        }
        throw IOException(message)
      }
      return result
    } finally {
      c.disconnect()
    }
  }
}
