package com.lumena.android.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lumena.android.model.AgentAction
import com.lumena.android.model.ScreenSnapshot
import java.util.ArrayDeque

class LumenaAccessibilityService : AccessibilityService() {
    companion object {
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"

        @Volatile var instance: LumenaAccessibilityService? = null
            private set

        @Volatile var lastChatGptSnapshot: ScreenSnapshot? = null
            private set

        @Volatile var lastChatGptUpdatedAt: Long = 0L
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        captureChatGptIfVisible()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName == CHATGPT_PACKAGE) captureChatGptIfVisible()
    }

    override fun onInterrupt() = Unit

    fun snapshot(): ScreenSnapshot {
        val root = rootInActiveWindow
        return snapshotOf(root)
    }

    fun isChatGptActive(): Boolean =
        rootInActiveWindow?.packageName?.toString() == CHATGPT_PACKAGE

    fun scheduleChatGptInsert(
        text: String,
        send: Boolean = false,
        attempts: Int = 14,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        fun trySend(remaining: Int) {
            if (!send) {
                onFinished?.invoke(true)
                return
            }
            if (clickChatGptSend()) {
                onFinished?.invoke(true)
                return
            }
            if (remaining > 0) {
                mainHandler.postDelayed({ trySend(remaining - 1) }, 350)
            } else {
                onFinished?.invoke(false)
            }
        }

        fun tryInsert(remaining: Int) {
            if (fillChatGptComposer(text)) {
                mainHandler.postDelayed({ trySend(attempts) }, 350)
                return
            }
            if (remaining > 0) {
                mainHandler.postDelayed({ tryInsert(remaining - 1) }, 350)
            } else {
                onFinished?.invoke(false)
            }
        }

        mainHandler.post { tryInsert(attempts) }
    }

    fun fillChatGptComposer(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != CHATGPT_PACKAGE) return false

        val editable = walk(root)
            .filter { it.isVisibleToUser && it.isEditable && it.isEnabled }
            .lastOrNull() ?: return false

        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) captureChatGptIfVisible()
        return ok
    }

    fun clickChatGptSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != CHATGPT_PACKAGE) return false

        val tokens = listOf(
            "send", "send message",
            "wyślij", "wyslij",
            "надісл", "відправ",
            "отправ", "submit"
        )

        val labeled = walk(root)
            .filter { it.isVisibleToUser && it.isEnabled }
            .firstOrNull { node ->
                val label = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ")
                    .lowercase()
                tokens.any { label.contains(it) }
            } ?: return false

        var candidate: AccessibilityNodeInfo? = labeled
        var hops = 0
        while (candidate != null && hops < 5) {
            if (candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            candidate = candidate.parent
            hops++
        }

        return false
    }

    fun execute(action: AgentAction): Boolean = when (action) {
        is AgentAction.ClickText -> clickByText(action.text)
        is AgentAction.InputText -> inputText(action.text)
        AgentAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        AgentAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        is AgentAction.OpenApp -> false
    }

    private fun captureChatGptIfVisible() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CHATGPT_PACKAGE) return
        lastChatGptSnapshot = snapshotOf(root)
        lastChatGptUpdatedAt = System.currentTimeMillis()
    }

    private fun snapshotOf(root: AccessibilityNodeInfo?): ScreenSnapshot = ScreenSnapshot(
        packageName = root?.packageName?.toString(),
        windowTitle = root?.paneTitle?.toString(),
        nodes = UiTreeReader.flatten(root)
    )

    private fun walk(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return out
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
