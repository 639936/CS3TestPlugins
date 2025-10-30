package com.pikpak

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

// Sử dụng một object để dễ dàng truy cập từ mọi nơi
object PikPakSettingsManager {
    private const val PREFS_FILE = "pikpak_settings"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    // Hàm lưu trữ thông tin
    fun saveData(context: Context, username: String?, password: String?) {
        val sharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            apply()
        }
    }

    // Hàm lấy thông tin
    fun getData(context: Context): Pair<String?, String?> {
        val sharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val username = sharedPreferences.getString(KEY_USERNAME, null)
        val password = sharedPreferences.getString(KEY_PASSWORD, null)
        return Pair(username, password)
    }
}