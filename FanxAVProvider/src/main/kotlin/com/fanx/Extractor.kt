package com.fanx // Đã cập nhật package name

import android.annotation.SuppressLint
import android.content.Context // Đã thêm import Context
import android.os.Handler
import android.os.Looper
import android.util.Log // Thêm import Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
// import com.lagradost.cloudstream3.USER_AGENT // Không cần import USER_AGENT mặc định nữa
import com.lagradost.cloudstream3.app // Cần app để gọi get() trong M3u8Helper
import com.lagradost.cloudstream3.mvvm.logError // Giữ lại logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.URI

open class Seekplayer : ExtractorApi() {
    override var name = "Seekplayer"
    override var mainUrl = "https://123.seekplayer.vip" // Giữ URL gốc làm tham chiếu nếu cần
    override val requiresReferer = true

    // Tag cho Logcat
    private val TAG = "FanxAVProvider"

    // Định nghĩa User Agent PC
    private val PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"


    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override suspend fun getUrl(
        url: String, // URL có dạng https://<domain>.seekplayer.vip/#<id-video>
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "Starting getUrl for: $url")
        val deferredResult = CompletableDeferred<String?>() // Dùng để chờ kết quả từ WebView

        // Đoạn mã JavaScript để inject vào WebView (Giữ nguyên)
        val jsScript = """
            (function() {
                // Ghi đè hàm fetch để bắt request
                const originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    const requestUrl = typeof url === 'string' ? url : url.url;
                    if (requestUrl.includes('master.m3u8')) {
                        console.log('Intercepted m3u8 request via fetch:', requestUrl);
                        SeekplayerInterface.processM3u8Url(requestUrl);
                    }
                    return originalFetch.apply(this, arguments);
                };

                // Ghi đè XMLHttpRequest để bắt request
                const originalXhrOpen = XMLHttpRequest.prototype.open;
                const originalXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                    this._requestUrl = url; // Lưu URL vào context của XHR
                    return originalXhrOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(data) {
                    if (this._requestUrl && this._requestUrl.includes('master.m3u8')) {
                        console.log('Intercepted m3u8 request via XHR:', this._requestUrl);
                        SeekplayerInterface.processM3u8Url(this._requestUrl);
                    }
                    return originalXhrSend.apply(this, arguments);
                };

                // Hàm chờ element xuất hiện và click
                function clickWhenReady(selector) {
                    let attempts = 0;
                    const maxAttempts = 40; // ~20 giây
                    const interval = setInterval(function() {
                        attempts++;
                        const element = document.querySelector(selector);
                        if (element) {
                            clearInterval(interval);
                            console.log('Clicking element:', selector);
                            Log.d(TAG, 'JS: Clicking element: ' + selector);
                            element.click();
                        } else {
                            console.log('Waiting for element:', selector, 'Attempt:', attempts);
                            Log.d(TAG, 'JS: Waiting for element: ' + selector + ' Attempt:' + attempts);
                        }
                        if(attempts >= maxAttempts) {
                             clearInterval(interval);
                             console.log('Timeout waiting for element:', selector);
                             Log.d(TAG, 'JS: Timeout waiting for element: ' + selector);
                             SeekplayerInterface.processM3u8Url(null); // Gửi null nếu timeout
                        }
                    }, 500); // Kiểm tra mỗi 500ms
                }

                // Chờ và click vào nút player
                clickWhenReady('#player-button-container');
            })();
        """.trimIndent()

        // Handler để chạy các tác vụ liên quan đến WebView trên Main Thread
        val mainHandler = Handler(Looper.getMainLooper())
        var webView: WebView? = null // Biến để giữ tham chiếu đến WebView

        try {
            Log.d(TAG, "Initializing WebView on Main thread...")
            withContext(Dispatchers.Main) { // Chuyển sang Main Thread để thao tác với WebView
                val context: Context = AcraApplication.context ?: throw RuntimeException("AcraApplication context is null")
                Log.d(TAG, "Context obtained. Creating WebView instance.")
                webView = WebView(context).apply {
                    Log.d(TAG, "Configuring WebView settings...")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Thay đổi USER_AGENT thành PC_USER_AGENT
                    settings.userAgentString = PC_USER_AGENT
                    Log.d(TAG, "User Agent set to PC: $PC_USER_AGENT")


                    // Tạo JavascriptInterface để nhận kết quả từ JS
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun processM3u8Url(m3u8Url: String?) {
                            Log.d(TAG, "Received m3u8 URL from JS: $m3u8Url")
                            // Hoàn thành Deferred với kết quả (chạy trên thread của JS Interface)
                            mainHandler.post {
                                if (!deferredResult.isCompleted) {
                                    deferredResult.complete(m3u8Url)
                                    Log.d(TAG, "Deferred result completed with URL: $m3u8Url")
                                } else {
                                    Log.d(TAG, "Deferred result already completed. Ignoring duplicate URL: $m3u8Url")
                                }
                            }
                        }
                    }, "SeekplayerInterface")
                    Log.d(TAG, "JavascriptInterface 'SeekplayerInterface' added.")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, urlFinished: String?) {
                            super.onPageFinished(view, urlFinished)
                            Log.d(TAG, "WebViewClient - onPageFinished for URL: $urlFinished")
                            // Inject script sau khi trang tải xong
                            view?.evaluateJavascript(jsScript) {
                                Log.d(TAG, "Executed JS injection script. Result: $it")
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            // Chặn quảng cáo hoặc tài nguyên không cần thiết
                            if (reqUrl.contains("ads.") || reqUrl.contains("tracker.") || reqUrl.endsWith(".png") || reqUrl.endsWith(".jpg")) {
                                Log.d(TAG, "WebViewClient - Blocking non-essential request: $reqUrl")
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            // Log.d(TAG, "WebViewClient - Allowing request: ${reqUrl.take(100)}")
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    Log.d(TAG, "WebViewClient configured.")

                    Log.d(TAG, "Loading WebView with URL: $url")
                    loadUrl(url) // Tải URL gốc
                }
            }
            Log.d(TAG, "WebView initialized. Waiting for m3u8 URL...")
            // Chờ kết quả từ WebView với timeout
            val masterM3u8Url = withTimeoutOrNull(30000) { // Timeout 30 giây
                deferredResult.await()
            }

            if (masterM3u8Url.isNullOrBlank()) {
                Log.w(TAG, "Failed to get master m3u8 URL (Timeout or null received from JS)")
                throw RuntimeException("Không thể lấy master.m3u8 URL")
            }

            Log.d(TAG, "Processing master m3u8 URL: $masterM3u8Url")

            // Sử dụng M3u8Helper để phân tích và tạo links
            Log.d(TAG, "Calling M3u8Helper.generateM3u8 for: $masterM3u8Url")
            M3u8Helper.generateM3u8(
                source = this.name, // "Seekplayer"
                streamUrl = masterM3u8Url,
                referer = url // Referer là URL gốc của trang seekplayer
            ).forEach { link ->
                Log.d(TAG, "M3u8Helper generated link: Quality=${link.quality}, URL=${link.url}")
                callback(link) // Gọi callback với ExtractorLink đã được tạo bởi helper
            }
            Log.d(TAG, "Finished processing M3u8Helper results.")

        } catch (e: Exception) {
            logError(e) // Ghi log lỗi chi tiết
            Log.e(TAG, "Error in Seekplayer getUrl: ${e.message}")
        } finally {
            Log.d(TAG, "Entering finally block, ensuring WebView cleanup.")
            // Đảm bảo WebView được hủy trên Main Thread
            mainHandler.post {
                try {
                    Log.d(TAG, "Attempting to stop and destroy WebView.")
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null // Giúp GC
                    Log.d(TAG, "WebView destroyed successfully.")
                } catch (e: Exception) {
                    logError(e) // Ghi log lỗi chi tiết khi hủy
                    Log.e(TAG, "Error destroying WebView: ${e.message}")
                }
            }
        }
    }
}

