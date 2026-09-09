package com.lumena.android.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.lumena.android.model.UiNode

object UiTreeReader {
    fun flatten(root: AccessibilityNodeInfo?): List<UiNode> {
        if (root == null) return emptyList()
        val out = mutableListOf<UiNode>()
        fun walk(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += UiNode(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                bounds = rect.flattenToString()
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return out
    }
}
