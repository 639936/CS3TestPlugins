package com.vlxx
//import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
//import kotlin.text.padStart
//import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class VlxxProvider : MainAPI() {
    override var mainUrl = "https://vlxx.bz"
    override var name = "VlxxAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = false

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
        val posterUrl = element.selectFirst("img")?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?:element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?:element.selectFirst("img")?.attr("src")

        if (title.isBlank() || href.isBlank()) return null

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? { // Sửa 1: Kiểu trả về
        //val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val key = query.trim().replace(Regex("\\s+"), "-").lowercase()
        val url = if (page == 1) "$mainUrl/search/$key/"
        else "$mainUrl/search/$key/$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document

        val results = document.select("#video-list .video-item").mapNotNull { toSearchResult(it) }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to PC_USER_AGENT)).document
        val title = document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }?: document.selectFirst("#page-title")?.text() ?: "N/A"
        val description = document.select("meta[property=og:description]").attr("content").takeIf { it.isNotBlank() }?: document.selectFirst(".video-description")?.text() ?: "N/A"

        val poster = document.select("meta[property=og:image]").attr("content").takeIf { it.isNotBlank() }
            ?: document.selectFirst("img")?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?:document.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?:document.selectFirst("img")?.attr("src")
        val recommendations = document.select("#video-list .video-item").mapNotNull {
            toSearchResult(it) // Tái sử dụng hàm toSearchResult bạn đã viết
        }
        val tags = document.select(".video-tags .actress-tag a").map {
            it.text() // Lấy nội dung text của mỗi thẻ tag
        }
//        val idphim = document.selectFirst("#video")?.attr("data-id")
        val linkphim = document.select("meta[property=og:url]").attr("content")

        // Danh sách để chứa tất cả các tập phim từ tất cả các phần
        val allEpisodes = mutableListOf<Episode>()
        allEpisodes.add(
            newEpisode(linkphim) {
                this.name = title
                this.season = 1
                this.episode = 1
                this.posterUrl = poster
                this.description = "Phim hiện tại"
            }
        )

        val seasonElements = document.select(".video-tags .actress-tag a")

        if (seasonElements.isNotEmpty()) {
            seasonElements.forEachIndexed { index, seasonElement ->
                val seasonNumber = index + 2
                val linkSS = seasonElement.attr("href")
                val nameSS = seasonElement.text()
                val seasonUrl = "$mainUrl$linkSS"
                val seasonDocument = app.get(seasonUrl, headers = mapOf("User-Agent" to PC_USER_AGENT)).document

                val episodesInSeason = seasonDocument.select("#video-list .video-item").mapIndexedNotNull {idex, element ->

                    val episodeName = element.select(".video-name a").attr("title").ifBlank { element.text() }
                    val episodeUrl = element.select(".video-name a").attr("href") //.removeSuffix("/").substringAfterLast("/")
                    if (episodeUrl.isBlank()) return@mapIndexedNotNull null
                    val episodePosterUrl = element.selectFirst("img")?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?:element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?:element.selectFirst("img")?.attr("src")
                    val episodeNumber = idex + 1
                    newEpisode(episodeUrl) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = episodePosterUrl
                        this.description = nameSS
                    }
                }
                allEpisodes.addAll(episodesInSeason)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.plot = description
            this.posterUrl = poster
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    // Data class để parse phản hồi JSON từ ajax.php
    data class PlayerResponse(val player: String)

    // Data class để parse đối tượng sources từ javascript của player
//    data class SourceFile(val file: String)
//    data class JwplayerSources(val sources: List<SourceFile>)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy ID video từ URL đầu vào (data)
        val idstream = data.removeSuffix("/").substringAfterLast("/")
        //Log.d("VlxxProvider", "ID Video: $idstream $data")


        // Lặp qua các server (1 và 2) bằng coroutine để xử lý song song
        listOf("1", "2").amap { stream ->
                // Bước 1: Lấy URL của iframe từ API
                val ajaxUrl = "https://vlxx.bz/ajax.php"
                val responseJson = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "vlxx_server" to stream,
                        "id" to idstream,
                        "server" to stream
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<PlayerResponse>()

                // Phân tích HTML trong JSON để lấy src của iframe
                val iframeSrc = responseJson?.player?.let { Jsoup.parse(it).selectFirst("iframe")?.attr("src") }
//Log.d("VlxxProvider", "Iframe Src: $iframeSrc")

                if (iframeSrc != null) {
                    // Bước 2: Truy cập iframe để lấy link video cuối cùng
                    val iframeContent = app.get(iframeSrc, referer = data).text
//Log.d("VlxxProvider", "Iframe Content: $iframeContent")

                    // Trích xuất chuỗi JSON từ `window.$$ops` một cách an toàn
                    val opsJsonString = iframeContent.substringAfter("window.$\$ops =").substringBefore("window.")
//Log.d("VlxxProvider", "Ops Json String: $opsJsonString")

                    // Phân tích JSON để lấy `file` URL
                    val finalUrl = opsJsonString.substringAfter("file\":\"").substringBefore("\"")
//Log.d("VlxxProvider", "Final Url: $finalUrl")

                        // Bước 3: Gọi callback với link cuối cùng
                        callback.invoke(
                            newExtractorLink(
                                source = "VLXX Server $stream",
                                name = "VLXX Server $stream",
                                url = finalUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                }
        }
        return true
    }
}