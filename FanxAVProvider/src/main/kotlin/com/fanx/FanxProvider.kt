package com.fanx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
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

        val streamLink = document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
            // ?.takeIf { it.isNotBlank() } sẽ biến một chuỗi rỗng thành null,
            // để có thể kích hoạt toán tử elvis ?:
            ?.takeIf { it.isNotBlank() }
            ?: // Nếu link từ meta tag là null hoặc rỗng, thử lấy từ iframe đầu tiên
            document.selectFirst("iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() }
            ?: // Nếu cả hai cách trên đều không thành công, lúc này mới báo lỗi
            throw ErrorLoadingException("Không tìm thấy link stream ở cả meta tag và iframe")


        Log.d(TAG, "tìm thấy stream link: $streamLink")

        return newMovieLoadResponse(title, url, TvType.NSFW, streamLink) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    // Hàm tiện ích để lấy chất lượng từ tên
    private fun getQualityFromName(name: String): Int {
        return Regex("(\\d{3,4})[pP]").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    // Hàm riêng để xử lý HLS (cả mã hóa và không mã hóa)
    private suspend fun handleHls(
        m3u8Url: String,
        qualityName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Content = app.get(m3u8Url).text
        val keyLine = m3u8Content.lines().find { it.contains("#EXT-X-KEY") }

        val extractorLink = newExtractorLink(
            source = this.name,
            name = qualityName,
            url = m3u8Url
        ) {
            this.quality = getQualityFromName(qualityName)
        }

        if (keyLine != null) {
            Log.d(TAG, "Luồng HLS được mã hóa cho chất lượng '$qualityName'")
            val keyUrl = keyLine.substringAfter("URI=\"").substringBefore("\"")
            val absoluteKeyUrl = if (keyUrl.startsWith("http")) keyUrl else java.net.URI(m3u8Url).resolve(keyUrl).toString()

            val keyData = app.get(absoluteKeyUrl).okhttpResponse.body.bytes()
            val keyBase64 = Base64.encodeToString(keyData, Base64.NO_WRAP)

            extractorLink.headers = mapOf(
                "hls-key" to "data:text/plain;base64,$keyBase64",
            )
        } else {
            Log.d(TAG, "Luồng HLS không được mã hóa cho chất lượng '$qualityName'")
            extractorLink
        }
        callback.invoke(extractorLink)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            lateinit var finalM3u8: String
            var finalM3u8Url = ""
            if (data.contains("123.seekplayer")) {
                Log.d(TAG, "load server 123.seekplayer")
                    val res123 = app.get(
                        url = data,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to PC_USER_AGENT),
                        interceptor = WebViewResolver(
                            interceptUrl = Regex(""".m3u8"""),
                            script = """
                    // Tạo một vòng lặp để kiểm tra sự tồn tại của nút player
                    // Vòng lặp này sẽ chạy 10 lần mỗi giây (mỗi 100ms)
                    const intervalId = setInterval(function() {
                        // Tìm phần tử có id là 'player-button'
                        const playerButton = document.querySelector('#player-button');
                        
                        // Nếu tìm thấy phần tử này
                        if (playerButton) {
                            // Thực hiện hành động click vào nó
                            playerButton.click();
                            
                            // Rất quan trọng: Dừng vòng lặp lại sau khi đã click
                            // để tránh click nhiều lần không cần thiết.
                            clearInterval(intervalId);
                        }
                    }, 100); 
                """,
                        ),
                        timeout = 12000L,
                    )
                finalM3u8Url = res123.url
            } else {
                // WebViewResolver giờ đây chủ yếu dùng để chạy script và đợi callback
                app.get(
                    url = data,
                    headers = mapOf("User-Agent" to PC_USER_AGENT),
                    interceptor = WebViewResolver(
                        // Đặt một regex không bao giờ khớp để không dùng tính năng này
                        interceptUrl = Regex("z-z-z-this-will-never-match-z-z-z"),
                        // Nhận URL từ JavaScript
                        scriptCallback = { url ->
                            Log.d(TAG, "JavaScript callback đã bắt được URL GET: $url")
                            finalM3u8Url = url
                        },
                        // Script ghi đè hàm fetch để kiểm tra method
                        script = """
    const originalFetch = window.fetch;
    window.fetch = function(...args) {
        try {
            const url = args[0] instanceof Request ? args[0].url : args[0];

            // ================== GUARD CLAUSE ==================
            // Nếu url không phải là một chuỗi string, hoặc là chuỗi rỗng,
            // thì dừng lại và gọi fetch gốc ngay lập tức.
            if (typeof url !== 'string' || url.length === 0) {
                return originalFetch.apply(this, args);
            }
            // ==================================================

            const method = (args[0] instanceof Request ? args[0].method : (args[1]?.method || 'GET')).toUpperCase();

            // Bây giờ chúng ta biết chắc url là một chuỗi hợp lệ
            if (/m3u8/.test(url) && method === 'GET') {
                window.CS3_WEBVIEW_SCRIPT_CALLBACK(url);
            }
        } catch (e) {
            // Thêm try-catch để đảm bảo script không bao giờ chết
        }
        
        return originalFetch.apply(this, args);
    };
    // Ghi đè XMLHttpRequest một cách an toàn
    const originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
        try {
            if (typeof url === 'string' && /m3u8/.test(url) && method.toUpperCase() === 'GET') {
                window.CS3_WEBVIEW_SCRIPT_CALLBACK(url);
            }
        } catch (e) {
            // Bỏ qua lỗi
        }
        originalOpen.apply(this, arguments);
    };
""",
                        timeout = 12000L,
                    )
                )

                // Kiểm tra xem callback có thực sự tìm được URL không
                if (!finalM3u8Url.contains(".m3u8")) {
                    throw ErrorLoadingException("Không thể tìm thấy link M3U8 (GET request) trong thời gian chờ")
                }
            }
            finalM3u8 = app.get(finalM3u8Url).text

            if (finalM3u8.contains("#EXT-X-STREAM-INF")) {
                Log.d(TAG, "Phát hiện Master Playlist, đang parse các chất lượng...")
                M3u8Helper.generateM3u8(
                    name,
                    finalM3u8Url,
                    data,
                    headers = mapOf("User-Agent" to PC_USER_AGENT)
                ).amap { stream ->
                    Log.d(TAG, "Đã parse được link chất lượng: ${stream.name} - ${stream.url}")
                    try {
                            handleHls(stream.url, stream.name, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Lỗi khi xử lý link chất lượng ${stream.name}: ${stream.url}", e)
                    }
                }
            } else {
                Log.d(TAG, "Phát hiện Media Playlist, xử lý trực tiếp.")
                handleHls(finalM3u8Url, this.name, callback)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình loadLinks", e)
            throw e
        }
    }
}