package com.fanx
import com.lagradost.cloudstream3.app

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
//import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
//import com.lagradost.cloudstream3.utils.loadExtractor
//import okhttp3.Interceptor


class FanxProvider : MainAPI() {
    override var mainUrl = "https://fanxxx.org"
    override var name = "FanXXX"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "vi"
    override val hasMainPage = true

    companion object {
  //      private const val TAG = "FanxProvider"
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

        var streamLink = document.selectFirst("iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() }
            ?:
            throw ErrorLoadingException("Không tìm thấy link stream ở cả meta tag và iframe")
        if (!streamLink.contains("emturbovid") && !streamLink.contains("dhcplay") && !streamLink.contains("hglink")) {
            streamLink = document.selectFirst("#tracking-url")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("Stream link chứa 'seekplay' nhưng không tìm thấy #tracking-url")
        }
        //Log.d(TAG, "tìm thấy stream link: $streamLink")

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
        //Log.d(TAG, "loadLinks: $data")
        if (data.contains("hglink")) {
            val finalM3u8 = app.get(
                data,
                referer = "",
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""index-v1-a1.m3u8|master.html""")
                )
            )
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } else if (data.contains("emturbovid")) {
            val linkturbo = data.replace("emturbovid", "turbovidhls", ignoreCase = true)
            val finalM3u8 = app.get(linkturbo).document.select("#video_player").attr("data-hash")
            M3u8Helper.generateM3u8(
                "Turbovid",
                finalM3u8,
                ""
            ).amap { stream ->
                callback.invoke(
                    newExtractorLink(
                        "Turbovid",
                        stream.name,
                        stream.url,
                        ExtractorLinkType.M3U8,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } else if (data.contains("dhcplay")) {
            val linkturbo = data.replace("dhcplay", "kravaxxa", ignoreCase = true)
            val finalM3u8 = app.get(
                data,
                referer = "https://dhcplay.com/",
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""index-v1-a1.m3u8""")
                )
            )
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = linkturbo
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            val finalM3u8 = app.get(
                data,
                referer = mainUrl,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""index-v1-a1.m3u8|master.html|master.m3u8""")
                )
            )
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalM3u8.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}