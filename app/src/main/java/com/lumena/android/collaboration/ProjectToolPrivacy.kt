package com.lumena.android.collaboration

/** Delegated inference is an egress channel too; ToolGate approval does not relabel private history. */
object ProjectToolPrivacy {
    const val BRIDGE_OLLAMA = "http://127.0.0.1:11434"
    fun inferenceAllowed(bridgeUrl: String, model: String, ownerAttestsModel: Boolean, ownerAllowsRemote: Boolean): Boolean =
        ownerAllowsRemote || (CollaborationReview.localRoute("ollama", bridgeUrl, "bridge-local", true) &&
            CollaborationReview.localRoute("ollama", BRIDGE_OLLAMA, model, ownerAttestsModel))
}
