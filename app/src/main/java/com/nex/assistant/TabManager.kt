package com.nex.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TabManager {
    var currentTabText by mutableStateOf("")
    var currentUrl by mutableStateOf("")

    fun updateTabContent(url: String, content: String) {
        currentUrl = url
        currentTabText = content
    }
}
