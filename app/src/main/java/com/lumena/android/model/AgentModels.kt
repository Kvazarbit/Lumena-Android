package com.lumena.android.model

data class UiNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: String
)

data class ScreenSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val nodes: List<UiNode>
)

sealed interface AgentAction {
    data class ClickText(val text: String) : AgentAction
    data class InputText(val text: String) : AgentAction
    data class OpenApp(val packageName: String) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
}

data class PlannedAction(
    val action: AgentAction,
    val reason: String,
    val requiresConfirmation: Boolean = true
)
