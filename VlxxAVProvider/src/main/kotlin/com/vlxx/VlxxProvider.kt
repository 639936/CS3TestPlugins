package com.vlxx
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
//import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder


class VlxxProvider : MainAPI() {
    override var mainUrl = "https://vlxx.bz"
    override var name = "VlxxAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    companion object {
        private const val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    }

    private val mainPageItems = listOf(
        Pair("Phim mới", "/new"),
        Pair("DAY Phim sex hay", "/ajax.php?likes&type=day&page="),
        Pair("WEEK Phim sex hay", "/ajax.php?likes&type=week&page="),
        Pair("MONTH Phim sex hay", "/ajax.php?likes&type=month&page="),
        Pair("YEAR Phim sex hay", "/ajax.php?likes&type=year&page="),
        Pair("ALL Phim sex hay", "/ajax.php?likes&type=all&page="),
        Pair("Việt Sub", "/vietsub"),
        Pair("Không che", "/khong-che"),
        Pair("Học sinh", "/hoc-sinh"),
        Pair("Vụng trộm- Ngoại tinh", "/vung-trom"),
        Pair("Sex Mỹ- Châu Âu", "/chau-au"),
        Pair("Phim cấp 3", "/cap-3")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mainPageItems.amap { (name, urlPart) ->
                val url = if (urlPart.contains("ajax.php"))  "$mainUrl$urlPart$page"
                    else {
                    if (page == 1){
                        if (urlPart.contains("new")) mainUrl
                        else "$mainUrl$urlPart"
                        }
                    else "$mainUrl$urlPart/$page"
                    }
                val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
                val videos = document.select("#video-list .video-item").mapNotNull {
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

    override suspend fun search(query: String, page: Int): SearchResponseList? { // Sửa 1: Kiểu trả về
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$mainUrl/search/$encodedQuery/"
        else "$mainUrl/search/$encodedQuery/$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document

        val results = document.select("#video-list .video-item").mapNotNull { toSearchResult(it) }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        val title = document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }?: document.selectFirst("#page-title")?.text() ?: "N/A"
        val description = document.select("meta[property=og:description]").attr("content").takeIf { it.isNotBlank() }?: document.selectFirst(".video-description")?.text() ?: "N/A"
        val poster = document.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }?: document.selectFirst("img")?.attr("src") ?: "N/A"
        val recommendations = document.select("#video-list .video-item").mapNotNull {
            toSearchResult(it) // Tái sử dụng hàm toSearchResult bạn đã viết
        }
        val tags = document.select(".video-tags .actress-tag a").map {
            it.text() // Lấy nội dung text của mỗi thẻ tag
        }

        var streamLink = document.selectFirst("#video")?.attr("data-id")
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("No stream link found")
        streamLink = streamLink.padStart(5, '0')

        return newMovieLoadResponse(title, url, TvType.NSFW, streamLink) {
            this.plot = description
            this.posterUrl = poster
            this.recommendations = recommendations
            this.tags = tags
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        listOf("s1",
            "s2"
        ).amap { stream ->
            callback.invoke(
                newExtractorLink(
                    source = "VLXX$stream",
                    name = "VLXX$stream",
                    url = "https://rr3---sn-8pxuuxa-i5ozr.qooglevideo.com/manifest-$stream/$data.vl",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}