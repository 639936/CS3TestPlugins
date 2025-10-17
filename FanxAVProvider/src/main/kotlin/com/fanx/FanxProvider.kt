package com.fanx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
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
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
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
                    val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
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
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        return document.select(".videos-list > article").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        val title = document.selectFirst("meta[itemprop=name]")?.attr("content") ?: "N/A"
        val description = document.selectFirst("meta[itemprop=description]")?.attr("content")
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")

        val streamLink = document.selectFirst("a.button#tracking-url")?.attr("href")
            ?: throw ErrorLoadingException("Không tìm thấy link stream")

        // Log.d(TAG, "Đã tìm thấy stream link: $streamLink")

        return newMovieLoadResponse(title, url, TvType.NSFW, streamLink) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Log.d(TAG, "Bắt đầu loadLinks với streamLink: $data")

            val response = app.get(
                url = data,
                referer = mainUrl,
                headers = mapOf("User-Agent" to PC_USER_AGENT),
                interceptor = WebViewResolver(
                    interceptUrl = Regex("/index-v1-a1\\.m3u8"),
                )
            )

            val finalM3u8Url = response.url
            // Log.d(TAG, "WebView đã tìm thấy URL M3U8 cuối cùng: $finalM3u8Url")

            if (!finalM3u8Url.contains(".m3u8")) {
                throw ErrorLoadingException("URL WebView trả về không phải là file M3U8: $finalM3u8Url")
            }

            val m3u8Content = app.get(finalM3u8Url, referer = data).text
            val keyLine = m3u8Content.lines().find { it.contains("#EXT-X-KEY") }

            if (keyLine != null) {
                // Log.d(TAG, "Phát hiện HLS được mã hóa: $keyLine")
                val keyUrl = keyLine.substringAfter("URI=\"").substringBefore("\"")
                // Log.d(TAG, "URL của key: $keyUrl")

                val keyData = app.get(keyUrl, referer = finalM3u8Url).okhttpResponse.body.bytes()
                val keyBase64 = Base64.encodeToString(keyData, Base64.NO_WRAP)

                val hlsHeaders = mapOf(
                    "hls-key" to "data:text/plain;base64,$keyBase64",
                    "Referer" to mainUrl
                )

                val extractorLink = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8Url
                )
                extractorLink.headers = hlsHeaders
                callback.invoke(extractorLink)

            } else {
                // Log.d(TAG, "Không tìm thấy key, xử lý như M3U8 thông thường.")
                val extractorLink = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8Url
                )
                extractorLink.headers = mapOf("Referer" to mainUrl)
                callback.invoke(extractorLink)
            }

            return true
        } catch (e: Exception) {
            // Log.e(TAG, "Lỗi trong hàm loadLinks", e)
            throw e
        }
    }
}