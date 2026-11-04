package com.bol.app.ai

import java.io.File
import java.security.MessageDigest

/** Streams the file through SHA-256, returns lowercase hex (no separators). */
internal fun File.sha256Hex(): String {
    val md = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
