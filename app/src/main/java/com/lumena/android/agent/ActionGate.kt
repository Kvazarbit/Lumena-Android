package com.lumena.android.agent

import com.lumena.android.model.AgentAction
import com.lumena.android.model.PlannedAction

object ActionGate {
    fun plan(action: AgentAction, reason: String): PlannedAction {
        val risky = when (action) {
            is AgentAction.InputText,
            is AgentAction.OpenApp,
            is AgentAction.ClickText -> true
            AgentAction.Back,
            AgentAction.Home -> false
        }
        return PlannedAction(action, reason, requiresConfirmation = risky)
    }
}
