package com.nex.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NexAccessibilityService : AccessibilityService() {

    companion object {
        var lastScreenContent: String = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val content = StringBuilder()
        extractText(rootNode, content)
        lastScreenContent = content.toString()
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (node.text != null) {
            sb.append(node.text).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            extractText(child, sb)
            child?.recycle()
        }
    }

    override fun onInterrupt() {}
}
