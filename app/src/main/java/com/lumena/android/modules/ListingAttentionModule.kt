package com.lumena.android.modules

import com.lumena.android.agent.core.LumenaModule
import com.lumena.android.agent.core.ModuleManifest

/**
 * Listing attention: scores OLX push notifications and alerts on rare
 * offers. It adds no agent tools; while disabled the notification listener
 * ignores everything and stores nothing.
 */
object ListingAttentionModule : LumenaModule {
    const val ID = "listing-attention"

    override val manifest = ModuleManifest(
        id = ID,
        version = "1",
        title = "Відбір оголошень (push OLX)",
        description = "Читає push збереженого пошуку OLX, гучно сповіщає про рідкісні оголошення, вчиться з ваших 👍/👎.",
        stateFiles = listOf("lumena_listing_attention_v1.json")
    )
}
