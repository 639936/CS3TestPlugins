package com.pikpak

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText

class PikPakSettingsFragment(private val plugin: PikPakWebDAVPlugin) : BottomSheetDialogFragment() {

    // Hàm generic để tìm view theo tên ID
    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findView(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", plugin.packageName)
        return id?.let { findViewById(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate layout từ resources của plugin
        val layoutId = plugin.resources?.getIdentifier("settings_pikpak", "layout", plugin.packageName)
            ?: return null
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val usernameEditText = view.findView<TextInputEditText>("pikpak_username")
        val passwordEditText = view.findView<TextInputEditText>("pikpak_password")
        val saveButton = view.findView<Button>("save_button")

        // Tải dữ liệu đã lưu và hiển thị lên UI
        val (savedUsername, savedPassword) = PikPakSettingsManager.getData(view.context)
        usernameEditText?.setText(savedUsername)
        passwordEditText?.setText(savedPassword)

        // Xử lý sự kiện khi nhấn nút Lưu
        saveButton?.setOnClickListener {
            val username = usernameEditText?.text?.toString()
            val password = passwordEditText?.text?.toString()

            PikPakSettingsManager.saveData(view.context, username, password)

            Toast.makeText(context, "Đã lưu cài đặt!", Toast.LENGTH_SHORT).show()
            dismiss() // Đóng màn hình cài đặt
        }
    }
}