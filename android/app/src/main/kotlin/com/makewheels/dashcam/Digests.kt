package com.makewheels.dashcam

import java.io.InputStream
import java.security.MessageDigest

internal object Digests {
  private val TABLE = LongArray(256)

  init {
    for (i in 0 until 256) {
      var n = i.toLong()
      for (k in 0 until 8) n = (n ushr 1) xor (if (n and 1L != 0L) 0xC96C5795D7870F42UL.toLong() else 0L)
      TABLE[i] = n
    }
  }

  fun interface Tick {
    @Throws(Exception::class)
    fun onBytes(bytes: Long)
  }

  @Throws(Exception::class)
  fun hash(stream: InputStream, tick: Tick): Array<String> {
    val sha = MessageDigest.getInstance("SHA-256")
    var crc = -1L
    var done = 0L
    val b = ByteArray(1024 * 1024)
    while (true) {
      val n = stream.read(b)
      if (n == -1) break
      sha.update(b, 0, n)
      for (i in 0 until n) crc = TABLE[(((crc xor b[i].toLong()) and 255L).toInt())] xor (crc ushr 8)
      done += n
      tick.onBytes(done)
    }
    return arrayOf(hex(sha.digest()), java.lang.Long.toUnsignedString(crc xor -1L), done.toString())
  }

  /** SHA-only preflight avoids the per-byte CRC loop while identifying duplicate content. */
  fun sha256(stream: InputStream, tick: Tick): Array<String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var done = 0L
    val buffer = ByteArray(1024 * 1024)
    while (true) {
      val n = stream.read(buffer)
      if (n == -1) break
      digest.update(buffer, 0, n)
      done += n
      tick.onBytes(done)
    }
    return arrayOf(hex(digest.digest()), done.toString())
  }

  fun hex(b: ByteArray): String = buildString {
    for (v in b) append(String.format("%02x", v))
  }
}
