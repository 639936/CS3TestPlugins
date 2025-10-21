package com.fanx

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Formatter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class Seekplayer : ExtractorApi() {
    override var name = "SeekPlayer"
    override var mainUrl = "https://123.seekplayer.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "FanxProvider"
        private const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

        private const val KEY_SECRET = "25742532592138962829065199652030"
        private const val IV_SECRET = "54674138327930864535348328456486"
    }

    private data class VideoSource(val hls: String?)

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("#")
        val host = java.net.URL(url).host
        try {
            Log.d(TAG, "start extractor Links: $url")
            val apiUrl = "https://$host/api/v1/info?id=$videoId"
            Log.d(TAG, "Requesting API URL: $apiUrl")

            val raw = app.get(apiUrl, headers = mapOf("User-Agent" to PC_USER_AGENT)).text.trim()

            // thử decode base64, nếu lỗi fallback về hex
            val encryptedData = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                hexStringToByteArray(raw)
            }

            val key = md5(KEY_SECRET + host.substringBefore("."))
            val iv = md5(videoId + IV_SECRET)

            Log.d(TAG, "key (MD5): ${toHexString(key)}")
            Log.d(TAG, "iv  (MD5): ${toHexString(iv)}")

            val decrypted = decrypt(encryptedData, key, iv)
            Log.d(TAG, "decrypted (preview): ${decrypted.take(200)}")

            val m3u8Url = tryParseJson<VideoSource>(decrypted)?.hls
            Log.d(TAG, "m3u8 link: $m3u8Url")

            if (m3u8Url != null) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getUrl", e)
        }
        return null
    }


    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String {
        return try {
            // AES CTR
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val aesOut = cipher.doFinal(data)

            // XOR 0x6E
            val xored = aesOut.map { (it.toInt() xor 0x6E).toByte() }.toByteArray()

            // Giải nén zlib (có header)
            val inflater = java.util.zip.Inflater(true)  // <= fix chính ở đây
            inflater.setInput(xored)
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inflater.end()

            val text = out.toString(Charsets.UTF_8.name())
            Log.d(TAG, "✅ final decrypted JSON (preview): ${text.take(200)}")
            text
        } catch (e: Exception) {
            Log.e(TAG, "decrypt fail (Ask Gemini)", e)
            ""
        }
    }

    // helper sẵn có (inflateWithFlag đã có trước) — nếu chưa, thêm:
    private fun inflateWithFlag(input: ByteArray, zlibWrapped: Boolean): String? {
        return try {
            val inflater = java.util.zip.Inflater(zlibWrapped)
            inflater.setInput(input)
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inflater.finished() || inflater.needsInput()) break
            }
            inflater.end()
            out.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            null
        }
    }



    private fun md5(input: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4)
                    + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun toHexString(bytes: ByteArray): String {
        val f = Formatter()
        for (b in bytes) f.format("%02x", b)
        val s = f.toString()
        f.close()
        return s
    }
}
