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
import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.Interceptor
import okhttp3.Request
import com.lagradost.cloudstream3.ui.WebviewFragment


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
        playlist: Boolean,
        m3u8Url: String,
        qualityName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Content = if (playlist) {
            app.get(m3u8Url).text
        } else m3u8Url
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
                    // Không cần gán kết quả cho 'res123' nữa vì link sẽ được lấy qua callback
                    app.get(
                        url = data,
                        referer = mainUrl,
                        headers = mapOf("User-Agent" to PC_USER_AGENT),
                        interceptor = WebViewResolver(
                            // 1. Vô hiệu hóa interceptUrl, vì nó không bắt được fetch/XHR.
                            //    Chúng ta sẽ dựa hoàn toàn vào script và callback.
                            interceptUrl = Regex("z-z-z-never-match-z-z-z"),

                            // 2. Thêm scriptCallback để nhận URL mà JavaScript gửi về.
                            scriptCallback = { urlvideo ->
                                    // Bây giờ chúng ta biết chắc url là một chuỗi hợp lệ
                                        Log.d(TAG, "scriptCallback ĐÃ XÁC THỰC được URL m3u8: $urlvideo")
                                        finalM3u8Url = urlvideo
                            },

                            // 3. Nâng cấp script để thực hiện cả hai nhiệm vụ: click và "nghe lén".
                            script = """
    var isM3u8Found = false;

    // PHẦN 1: CLICK NÚT
    function tryToClick() {
        var playerButton = document.querySelector('#player-button-container');
        if (playerButton) {
            playerButton.click();
        } else {
            setTimeout(tryToClick, 1000);
        }
    }
    setTimeout(tryToClick, 500);

    // PHẦN 2: PHÁT HIỆN MẠNG "MỘT LẦN DUY NHẤT"
    var originalFetch = window.fetch;
    var originalOpen = XMLHttpRequest.prototype.open;

    function interceptAndSend(url) {
        if (isM3u8Found) return;
        // Chỉ gửi đi nếu url là chuỗi hợp lệ VÀ chứa m3u8
        if (typeof url === 'string' && /m3u8/.test(url)) {
            isM3u8Found = true;
setTimeout(function() {
    // Chuyển đổi 'url' thành một chuỗi ký tự một cách an toàn
    var urlAsText = String(url); 

    // Gửi chuỗi đã được đảm bảo đi
    window.CS3_WEBVIEW_SCRIPT_CALLBACK(urlAsText);
}, 500);
            window.fetch = originalFetch;
            XMLHttpRequest.prototype.open = originalOpen;
        }
    }

    window.fetch = function() {
        var url = arguments[0] instanceof Request ? arguments[0].url : arguments[0];
        interceptAndSend(url);
        return originalFetch.apply(this, arguments);
    };

    XMLHttpRequest.prototype.open = function() {
        var url = arguments[1];
        interceptAndSend(url);
        originalOpen.apply(this, arguments);
    };
""",
                        ),
                        // Tăng thời gian chờ để có đủ thời gian cho các hành động
                        timeout = 20000L, // 20 giây
                    )

                    // 4. Kiểm tra xem link đã được gán thành công chưa
                    if (!finalM3u8Url.contains(".m3u8")) {
                        throw ErrorLoadingException("Không thể bắt được link M3U8 trong thời gian chờ.")
                    }

                    // Tại đây, biến 'finalM3u8Url' đã chứa link bạn cần
                    Log.d(TAG, "Đã lấy được link thành công: $finalM3u8Url")
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
            Log.d(TAG, "Phát hiện Link m3u8: $finalM3u8Url")
            finalM3u8 = app.get(finalM3u8Url).text

            if (finalM3u8.contains("#EXT-X-STREAM-INF")) {
                Log.d(TAG, "Phát hiện Playlist, đang parse các chất lượng...")
                M3u8Helper.generateM3u8(
                    name,
                    finalM3u8Url,
                    data,
                    headers = mapOf("User-Agent" to PC_USER_AGENT)
                ).amap { stream ->
                    Log.d(TAG, "Đã parse được link chất lượng: ${stream.name} - ${stream.url}")
                    try {
                            handleHls(true,stream.url, stream.name, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Lỗi khi xử lý link chất lượng ${stream.name}: ${stream.url}", e)
                    }
                }
            } else {
                Log.d(TAG, "Phát hiện Media, xử lý trực tiếp.")
                handleHls(false,finalM3u8Url, this.name, callback)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình loadLinks", e)
            throw e
        }
    }
}