package com.pikpak

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class PikpakProvider(private val context: Context) : MainAPI() {
    override var name = "PikPak WebDAV"
    override var mainUrl = "https://dav.mypikpak.com"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)
    override val hasMainPage = true

    /**
     * Lấy header xác thực Basic Auth từ thông tin đã lưu.
     * Trả về null nếu thông tin chưa được cấu hình.
     */
    private fun getAuthHeader(): String? {
        val (username, password) = PikPakSettingsManager.getData(context)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return null
        }
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /**
     * Hàm chung để gửi yêu cầu PROPFIND và lấy danh sách tệp/thư mục.
     * @param url Đường dẫn WebDAV để duyệt.
     * @return Một danh sách các SearchResponse (MovieSearchResponse hoặc TvSeriesSearchResponse).
     */
    private suspend fun getWebDAVDirectory(url: String): List<SearchResponse> {
        val authHeader = getAuthHeader() ?: throw ErrorLoadingException("Vui lòng nhập thông tin đăng nhập trong cài đặt plugin.")

        val list = mutableListOf<SearchResponse>()
        val requestBody = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml".toMediaType())

        val headers = mapOf(
            "Depth" to "1",
            "Authorization" to authHeader
        )

        val responseXml = app.custom(
            "PROPFIND",
            url,
            headers = headers,
            requestBody = requestBody
        ).text

        val document = Jsoup.parse(responseXml, "", Parser.xmlParser())

        document.select("d|response").forEach { response ->
            val href = response.selectFirst("d|href")?.text() ?: return@forEach
            // Giải mã các ký tự đặc biệt trong URL (ví dụ: %20 -> " ")
            val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
            val fullPath = if (decodedHref.startsWith("http")) decodedHref else mainUrl + decodedHref

            // Bỏ qua mục cha để tránh lặp vô hạn
            if (fullPath.trimEnd('/') == url.trimEnd('/')) return@forEach

            val isCollection = response.selectFirst("d|resourcetype d|collection") != null
            val displayName = response.selectFirst("d|displayname")?.text() ?: decodedHref

            if (isCollection) {
                list.add(newTvSeriesSearchResponse(displayName, fullPath, TvType.TvSeries))
            } else {
                // Chỉ thêm các tệp có đuôi video phổ biến
                if (displayName.endsWith(".mp4", true) || displayName.endsWith(".mkv", true) || displayName.endsWith(".avi", true) || displayName.endsWith(".mov", true)) {
                    list.add(newMovieSearchResponse(displayName, fullPath, TvType.Movie))
                }
            }
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Trang chủ sẽ luôn hiển thị nội dung của thư mục gốc
        val items = getWebDAVDirectory(mainUrl)
        return newHomePageResponse(
            list = HomePageList(
                name = "Thư mục gốc",
                list = items
            ),
            hasNext = false // Không có phân trang cho WebDAV
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val isFile = url.endsWith(".mp4", true) || url.endsWith(".mkv", true) || url.endsWith(".avi", true) || url.endsWith(".mov", true)

        if (isFile) {
            val fileName = url.substringAfterLast('/')
            // Đối với tệp video, tạo một MovieLoadResponse đơn giản
            return newMovieLoadResponse(fileName, url, TvType.Movie, url) {
                // Không có poster cho tệp đơn lẻ
            }
        } else {
            // Đối với thư mục, liệt kê nội dung bên trong
            val items = getWebDAVDirectory(url)

            // Lọc ra các tệp video để làm "tập phim"
            val episodes = items.filterIsInstance<MovieSearchResponse>().map {
                newEpisode(it.url) { // Dữ liệu cho newEpisode là chính URL của tệp
                    name = it.name
                }
            }

            // Lọc ra các thư mục con để hiển thị ở mục "Đề xuất"
            val subFolders = items.filterIsInstance<TvSeriesSearchResponse>()

            val folderName = url.trimEnd('/').substringAfterLast('/')
            return newTvSeriesLoadResponse(folderName, url, TvType.TvSeries, episodes) {
                this.recommendations = subFolders
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val authHeader = getAuthHeader() ?: throw ErrorLoadingException("Vui lòng nhập thông tin đăng nhập.")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = INFER_TYPE
            ) {
                this.referer = mainUrl
                this.headers = mapOf("Authorization" to authHeader)
            }
        )
        return true
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return null
    }
}