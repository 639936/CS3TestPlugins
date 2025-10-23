package com.fanx

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor


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
        val lists = mainPageItems.amap { (name, urlPart) ->
                val url =  "$mainUrl/page/$page$urlPart"
                val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
                val videos = document.select(".videos-list > article").mapNotNull {
                    toSearchResult(it)
                }
                HomePageList(name, videos)
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

        var streamLink = document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
            // ?.takeIf { it.isNotBlank() } sẽ biến một chuỗi rỗng thành null,
            // để có thể kích hoạt toán tử elvis ?:
            ?.takeIf { it.isNotBlank() }
            ?: // Nếu link từ meta tag là null hoặc rỗng, thử lấy từ iframe đầu tiên
            document.selectFirst("iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() }
            ?: // Nếu cả hai cách trên đều không thành công, lúc này mới báo lỗi
            throw ErrorLoadingException("Không tìm thấy link stream ở cả meta tag và iframe")
        if (streamLink.contains("seekplay", ignoreCase = true)) {
            streamLink = document.selectFirst("#tracking-url")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("Stream link chứa 'seekplay' nhưng không tìm thấy #tracking-url")
        }
        Log.d(TAG, "tìm thấy stream link: $streamLink")

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
        Log.d(TAG, "loadLinks: $data")
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}