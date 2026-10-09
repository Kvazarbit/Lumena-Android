package com.lumena.android.modules

import com.lumena.android.agent.core.LumenaModule

/** Modules shipped in the APK, in routing order: explicit MCP before marketplace. */
object BuiltInModules {
    val all: List<LumenaModule> = listOf(
        McpModule,
        MarketplaceModule,
        ListingAttentionModule,
        PracujModule
    )
}
