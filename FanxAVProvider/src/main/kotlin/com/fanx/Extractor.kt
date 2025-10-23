/*package com.fanx
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
//import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink

/*open class Hglink : ExtractorApi() {
    override var name = "Hglink"
    override var mainUrl = "hglink.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Biến để lưu trữ link m3u8 hợp lệ đầu tiên tìm thấy
        var m3u8Link: String? = null

        // 1. Regex để bắt bất kỳ URL nào có chứa ".m3u8"
        val m3u8Regex = Regex("""m3u8""")

        // 2. Gọi WebViewResolver, sử dụng requestCallBack để lọc phương thức GET
        WebViewResolver(
            interceptUrl = m3u8Regex
        ).resolveUsingWebView(
            url = url,
            // requestCallBack sẽ được gọi mỗi khi một request khớp với m3u8Regex
            requestCallBack = { request ->
                // Kiểm tra nếu request là GET và chúng ta chưa tìm thấy link nào
                if (request.method == "GET" && m3u8Link == null) {
                    m3u8Link = request.url.toString() // Lưu lại URL
                    return@resolveUsingWebView true // Trả về true để dừng WebView ngay lập tức
                }
                // Trả về false để WebView tiếp tục chạy nếu chưa tìm thấy link hợp lệ
                return@resolveUsingWebView false
            }
        )

        // 3. Nếu không tìm thấy link nào sau khi WebView kết thúc, thoát hàm
        val finalM3u8Link = m3u8Link ?: throw ErrorLoadingException ("No M3U8 link found")

        // 4. Dùng M3u8Helper để xử lý link đã tìm thấy
        M3u8Helper.generateM3u8(
            source = "Hglink", // Tên nguồn phát
            streamUrl = finalM3u8Link,
            referer = mainUrl,
        ).forEach { stream ->
            return listOf(
                newExtractorLink(
                    source = "Download",
                    name = name,
                    url = stream.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    return null
    }
}*/
/*open class Turbovidhls : ExtractorApi() {
    override var name = "Turbovidhls"
    override var mainUrl = "emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val finalM3u8Link = app.get(url).document.select("#video_playe").attr("data-hash")

        M3u8Helper.generateM3u8(
            source = "Turbovidhls", // Tên nguồn phát
            streamUrl = finalM3u8Link,
            referer = mainUrl,
        ).forEach { stream ->
            newExtractorLink(
                source = "Download",
                name = name,
                url = httpsify(stream.url),
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        }
        return null
    }
}*/
/*class Allplayer : ExtractorApi() {
    override var name = "Allplayer"
    override var mainUrl = ""
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val finalM3u8 = app.get(
            url,
            referer = mainUrl,
            interceptor = WebViewResolver(
                interceptUrl = Regex("""m3u8""")
            )
        )

        M3u8Helper.generateM3u8(
            source = "Download server", // Tên nguồn phát
            streamUrl = finalM3u8.url,
            referer = "",
        ).forEach { stream ->
            return listOf(
            newExtractorLink(
                source = "Download",
                name = name,
                url = httpsify(stream.url),
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
            )
        }
        return null
    }
}*/
        */