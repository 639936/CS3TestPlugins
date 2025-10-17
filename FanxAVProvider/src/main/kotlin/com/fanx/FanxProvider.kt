package com.fanx // Thay đổi package này thành package của provider bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.util.Log

class FanxProvider : MainAPI() {
    override var mainUrl = "https://fanxxx.org"
    override var name = "FanXXX"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    companion object {
        private const val TAG = "FanxProvider"
        private const val SALT = "5d41402abc4b2a76b9719d911017c592"

        // --- CÁC HÀM TIỆN ÍCH CHO MÃ HÓA VÀ CHUỖI ---
        private fun hexToByteArray(hex: String): ByteArray {
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun bytesToHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun md5Binary(input: String): ByteArray {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8))
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
        }

        private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.copyOf(16), "AES"),
                IvParameterSpec(iv.copyOf(16))
            )
            return String(cipher.doFinal(data))
        }

        private fun encrypt(data: String, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.copyOf(16), "AES"),
                IvParameterSpec(iv.copyOf(16))
            )
            return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        // --- CÁC HÀM TẠO KEY/IV ĐÃ ĐƯỢC DỊCH NGƯỢC VÀ SỬA LỖI ---
        private fun generateKey(host: String): String {
            val k = 10
            val M = 110
            val z = 1
            val result = StringBuilder()
            val G = "ᵟ".first().code
            result.append(k.toString() + G.toChar())
            result.append(host[k / 10])
            result.append(result.substring(1, 3))
            result.append(M.toChar()).append((M - 1).toChar()).append((M + 7).toChar())

            val oe = "3579".toCharArray().reversed()

            result.append(oe[0]).append(oe[1])
            val val1 = (oe[3].digitToInt() * z + z + oe[0].digitToInt())
            result.append(val1.toChar()).append(val1.toChar())
            val val2 = (oe[0].digitToInt() * k + oe[0].digitToInt() * z)
            result.append(val2.toChar()).append(oe.reversed().joinToString("").substring(0, 2))

            return bytesToHex(md5Binary(result.toString()))
        }

        private fun generateIv(host: String, pathWithQuery: String): String {
            val k = host + "//"
            val z = host.length * k.length
            val dollar = 1
            val G = StringBuilder()
            for (Le in dollar until 10) {
                G.append((Le + z).toChar())
            }
            var oe = ""
            oe = dollar.toString() + oe + dollar.toString() + oe + dollar.toString()
            val be = oe.length * (pathWithQuery.getOrNull(0)?.code ?: 0)
            val it = oe.toInt() * dollar + host.length
            val P = it + 4
            val ie = host.getOrNull(dollar)?.code ?: 0
            val De = ie * dollar - 2
            G.append(z.toChar())
                .append(oe)
                .append(be.toChar())
                .append(it.toChar())
                .append(P.toChar())
                .append(ie.toChar())
                .append(De.toChar())

            return bytesToHex(md5Binary(G.toString()))
        }

        private fun createUserAgentSalt(userAgent: String): String {
            var salt = ""
            for (i in userAgent.indices) {
                salt += (userAgent[i].code + i % 5).toChar()
            }
            return md5(salt)
        }
    }

    private val mainPageItems = listOf(
        Pair("Newest", "/?filter=latest"),
        Pair("Best", "/?filter=popular"),
        Pair("Most viewed", "/?filter=most-viewed"),
        Pair("Longest", "/?filter=longest"),
        Pair("Random", "/?filter=random")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = coroutineScope {
            mainPageItems.map { (name, urlPart) ->
                async {
                    val url = if (name == "Random" && page == 1) {
                        "$mainUrl$urlPart"
                    } else {
                        "$mainUrl/page/$page$urlPart"
                    }
                    val document = app.get(url).document
                    val videos = document.select(".videos-list > article").mapNotNull {
                        toSearchResult(it)
                    }
                    if (videos.isNotEmpty()) HomePageList(name, videos) else null
                }
            }.mapNotNull { it.await() }
        }
        return newHomePageResponse(lists, true)
    }

    private fun toSearchResult(element: Element): MovieSearchResponse? {
        val aTag = element.selectFirst("a") ?: return null
        val title = aTag.attr("title")
        val href = aTag.attr("href")
        val posterUrl = element.selectFirst("img")?.attr("data-src")

        if (title.isBlank() || href.isBlank()) return null

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select(".videos-list > article").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[itemprop=name]")?.attr("content") ?: "N/A"
        val description = document.selectFirst("meta[itemprop=description]")?.attr("content")
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
        val embedUrl = document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
            ?: throw ErrorLoadingException("Embed URL not found")

        return newMovieLoadResponse(title, url, TvType.NSFW, embedUrl) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    private data class VideoConfig(val hls: String?, val ttdata: String?)
    private data class MainConfig(val video: VideoConfig?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d(TAG, "== BẮT ĐẦU QUÁ TRÌNH LẤY LINK ==")
            Log.d(TAG, "Embed URL (data): $data")

            val videoId = data.substringAfterLast("#")
            if (videoId.isBlank()) throw ErrorLoadingException("Could not get video ID from URL")
            Log.d(TAG, "-> Video ID: '$videoId'")

            val host = "123.seekplayer.vip"
            val pathWithQuery = "/api/v1/info?id=$videoId"
            val infoUrl = "https://$host$pathWithQuery"
            Log.d(TAG, "-> URL cấu hình: $infoUrl")

            val keyHex = generateKey(host)
            val ivHex = generateIv(host, pathWithQuery)
            Log.d(TAG, "-> Key được tạo: $keyHex")
            Log.d(TAG, "-> IV được tạo: $ivHex")

            val key = hexToByteArray(keyHex)
            val iv = hexToByteArray(ivHex)

            Log.d(TAG, "Đang fetch dữ liệu cấu hình...")
            val infoResponseText = app.get(infoUrl, referer = "$mainUrl/").text.trim().replace(Regex("\\s+"), "")
            if (infoResponseText.isBlank()) throw ErrorLoadingException("Received empty info response")
            Log.d(TAG, "-> Dữ liệu mã hóa nhận được (đã làm sạch, dài ${infoResponseText.length} ký tự): $infoResponseText")

            val encryptedInfoData = hexToByteArray(infoResponseText)
            val decryptedInfoJson = decrypt(encryptedInfoData, key, iv)
            Log.d(TAG, "-> GIẢI MÃ THÀNH CÔNG! JSON cấu hình: $decryptedInfoJson")

            val config = parseJson<MainConfig>(decryptedInfoJson)
            val ttdata = config.video?.ttdata ?: throw ErrorLoadingException("`ttdata` not found in config")
            val hlsPath = config.video.hls ?: throw ErrorLoadingException("`hls` path not found in config")
            Log.d(TAG, "-> ttdata: '$ttdata' | hlsPath: '$hlsPath'")

            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            val salt = createUserAgentSalt(userAgent)
            val dataToEncrypt = "$ttdata::$salt"
            Log.d(TAG, "-> Dữ liệu để mã hóa (ttdata + salt): '$dataToEncrypt'")

            val encryptedTtdata = encrypt(dataToEncrypt, key, iv)
            val urlSafeEncrypted = Base64.encodeToString(encryptedTtdata, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            val dynamicPath = "${urlSafeEncrypted.substring(0, 22)}/${urlSafeEncrypted.substring(22, 25)}/${urlSafeEncrypted.substring(25, 33)}/${urlSafeEncrypted.substring(33, 39)}"
            Log.d(TAG, "-> Đường dẫn động được tạo: '$dynamicPath'")

            val baseUrl = "https://$host/hls/$dynamicPath"
            val masterPlaylistUrl = "$baseUrl$hlsPath"

            val masterPlaylistContent = app.get(masterPlaylistUrl, referer = "$mainUrl/").text.trim()

            val detailPlaylistFileName = masterPlaylistContent.lines().firstOrNull { it.endsWith(".m3u8") }
                ?: throw ErrorLoadingException("Could not find detail playlist in master.m3u8")

            val finalUrl = masterPlaylistUrl.replace("master.m3u8", detailPlaylistFileName)
            Log.d(TAG, "--> URL CUỐI CÙNG: $finalUrl")

            loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
            Log.d(TAG, "== KẾT THÚC QUÁ TRÌNH LẤY LINK THÀNH CÔNG ==")

            return true

        } catch (e: Exception) {
            Log.e(TAG, "!!!!!! LỖI TRONG QUÁ TRÌNH loadLinks !!!!!!", e)
            throw e
        }
    }
}