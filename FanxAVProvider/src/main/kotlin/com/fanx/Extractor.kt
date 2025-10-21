package com.fanx
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.toByteArray
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class Seekplayer : ExtractorApi() {
    override var name = "SeekPlayer"
    override var mainUrl = "https://123.seekplayer.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "FanxProvider"
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    }

    // Lớp data để parse JSON sau khi giải mã
    private data class VideoSource(val hls: String?)

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfterLast("#")
        val parsedUrl = java.net.URL(url)
        val host = parsedUrl.host

        try {
            Log.d(TAG, "loadLinks: $url")
            // 1. Gọi API để lấy dữ liệu đã mã hóa dưới dạng chuỗi hex
            val encryptedResponseHex = app.get("https://$host/api/v1/video?id=$videoId",headers = mapOf("User-Agent" to PC_USER_AGENT), mainUrl).text
            val encryptedData = hexStringToByteArray(encryptedResponseHex)

            // 2. Tạo Khóa (Key) và IV (Initialization Vector)
            val key = generateKey(host)
            val iv = generateIv(host, url.substringAfter("#"))

            // 3. Giải mã dữ liệu
            val decryptedJson = decrypt(encryptedData, key, iv)

            // 4. Parse JSON để lấy link M3U8
            val m3u8Url = tryParseJson<VideoSource>(decryptedJson)?.hls

            if (m3u8Url != null) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}

        return null
    }

    // --- CÁC HÀM TIỆN ÍCH VÀ LOGIC GIẢI MÃ ---

    private fun decrypt(encryptedData: ByteArray, key: ByteArray, iv: ByteArray): String {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun sha1(input: String): ByteArray {
        return MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
    }

    private fun generateKey(host: String): ByteArray {
        val const1 = 10
        val const2 = 110
        val const3 = 1
        var result = ""

        val specialChar = "áµŸ"
        val charCodeStr = (specialChar[0].code).toString()
        val reversed = charCodeStr.reversed()

        for (char in reversed) {
            result += (const1 + char.toString().toInt()).toChar()
        }

        result += host[const1 / 10]
        result += result.slice(1..2)
        result += "${const2.toChar()}${(const2 - 1).toChar()}${(const2 + 7).toChar()}"

        val nums = "3579".map { it.toString() }
        result += (nums[3] + nums[2]).toInt().toChar()
        result += (nums[1] + nums[2]).toInt().toChar()
        result += (nums[0].toInt() * const3 + const3 + nums[3].toInt()).toChar()
        result += (nums[0].toInt() * const3 + const3 + nums[3].toInt()).toChar()

        val reversedNums = nums.reversed().joinToString("").slice(0..1)
        result += (nums[3].toInt() * const1 + nums[3].toInt() * const3).toChar()
        result += reversedNums.toInt().toChar()

        return result.toByteArray(StandardCharsets.UTF_8)
    }

    private fun generateIv(host: String, hash: String): ByteArray {
        val hostWithSlash = "$host//"
        val someLength = host.length * hostWithSlash.length
        val const1 = 1
        var result1 = ""
        for (i in const1 until 10) {
            result1 += (i + someLength).toChar()
        }

        var someStr = ""
        someStr = const1.toString() + someStr + const1.toString() + someStr + const1.toString()

        val lengthTimesHash = someStr.length * (if (hash.isNotEmpty()) hash[0].code else 0)
        val anotherVal = someStr.toInt() * const1 + host.length
        val finalVal1 = anotherVal + 4
        val hostCharCode = if (host.length > const1) host[const1].code else 0
        val finalVal2 = hostCharCode * const1 - 2

        result1 += "${someLength.toChar()}${someStr.toInt().toChar()}${lengthTimesHash.toChar()}${anotherVal.toChar()}${finalVal1.toChar()}${hostCharCode.toChar()}${finalVal2.toChar()}"

        return sha1(result1).copyOfRange(0, 16)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Must have an even length" }
        return hex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}