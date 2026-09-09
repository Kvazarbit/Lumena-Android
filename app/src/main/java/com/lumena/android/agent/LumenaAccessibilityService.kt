package com.lumena.android.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lumena.android.model.AgentAction
import com.lumena.android.model.ScreenSnapshot

class LumenaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: LumenaAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun snapshot(): ScreenSnapshot {
        val root = rootInActiveWindow
        return ScreenSnapshot(
            packageName = root?.packageName?.toString(),
            windowTitle = root?.paneTitle?.toString(),
            nodes = UiTreeReader.flatten(root)
        )
    }

    fun execute(action: AgentAction): Boolean = when (action) {
        is AgentAction.ClickText -> clickByText(action.text)
        is AgentAction.InputText -> inputText(action.text)
        AgentAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        AgentAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        is AgentAction.OpenApp -> false
    }

    private fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isVisibleToUser } ?: return false
        var n: AccessibilityNodeInfo? = target
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
