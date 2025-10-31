package com.pikpak

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PikPakWebDAVPlugin : Plugin() {
    var activity: AppCompatActivity? = null

    // Không cần khai báo lại 'resources' hay 'packageName' ở đây.
    // Chúng đã được kế thừa từ lớp Plugin.

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // Truyền context vào Provider để nó có thể đọc SharedPreferences
        registerMainAPI(PikpakProvider(context))

        // Mở màn hình cài đặt khi người dùng nhấn nút
        openSettings = {
            activity?.let {
                // 'this' ở đây là chính instance của PikPakWebDAVPlugin.
                // Nó chứa thuộc tính 'resources' và 'packageName' cần thiết.
                PikPakSettingsFragment(this).show(it.supportFragmentManager, "PikPakSettings")
            }
        }
    }
}