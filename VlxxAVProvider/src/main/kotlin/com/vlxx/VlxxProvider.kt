package com.vlxx
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder


class VlxxProvider : MainAPI() {
    override var mainUrl = "https://vlxx.bz"
    override var name = "VLXX"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    companion object {
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    }

    private val mainPageItems = listOf(
        Pair("Phim mới", "/new"),
        Pair("DAY Phim sex hay", "/phim-sex-hay/#day"),
        Pair("WEEK Phim sex hay", "/phim-sex-hay/#week"),
        Pair("MONTH Phim sex hay", "/phim-sex-hay/#month"),
        Pair("YEAR Phim sex hay", "/phim-sex-hay/#year"),
        Pair("ALL Phim sex hay", "/phim-sex-hay/#all"),
        Pair("Việt Sub", "/vietsub/"),
        Pair("Không che", "/khong-che/"),
        Pair("Học sinh", "/hoc-sinh/"),
        Pair("Vụng trộm- Ngoại tinh", "/vung-trom/"),
        Pair("Sex Mỹ- Châu Âu", "/chau-au/"),
        Pair("Phim cấp 3", "/cap-3/")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mainPageItems.amap { (name, urlPart) ->
                val url = if (page == 1) {
                    if (urlPart.contains("new")) {
                        "$mainUrl/"
                    } else {
                        "$mainUrl$urlPart"
                    }
                } else {
                    "$mainUrl$urlPart/$page/"
                }
                val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
                val videos = document.select("#container .video-item").mapNotNull {
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
        val posterUrl = element.selectFirst("img")?.attr("data-original")

        if (title.isBlank() || href.isBlank()) return null

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/$encodedQuery"
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        return document.select("#container .video-item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        val title = document.selectFirst("#page-title")?.text() ?: "N/A"
        val description = document.selectFirst(".video-description")?.text()
        val poster = document.selectFirst("img")?.attr("src")

        val streamLink = document.selectFirst("iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() }
            ?:
            throw ErrorLoadingException("Không tìm thấy link stream")

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
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}