package com.lumena.android.modules

import com.lumena.android.agent.core.IntentPreflight
import com.lumena.android.agent.core.LumenaModule
import com.lumena.android.agent.core.ModuleManifest
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.agent.core.ModuleTool
import com.lumena.android.agent.core.TaskIntent
import com.lumena.android.agent.core.TaskIntentProfile
import com.lumena.android.agent.core.TaskIntentRouter
import com.lumena.android.agent.core.ToolCapability
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.core.ToolSpec

/**
 * Polish marketplace (OLX) search and Termux-side watches. v1 discovers
 * listings through a public search index because direct OLX.pl access is
 * blocked; results are discovery evidence, not verified listing detail.
 */
object MarketplaceModule : LumenaModule {
    const val ID = "marketplace"

    override val manifest = ModuleManifest(
        id = ID,
        version = "1",
        title = "Маркетплейс (OLX)",
        description = "Пошук оголошень OLX.pl через пошуковий індекс і фонові вартові в Termux bridge.",
        tools = listOf(
            ModuleTool(
                ToolSpec("marketplace.search", ToolRisk.READ_ONLY, setOf("query"), "Search Polish marketplace listings through a provider adapter. v1 supports provider=olx-pl via indexed public search because direct OLX.pl fetches may be blocked. Optional location/category/limit/time_range."),
                setOf(ToolCapability.READ_STATE, ToolCapability.NETWORK),
                setOf("marketplace_search")
            ),
            ModuleTool(
                ToolSpec("marketplace.watch.list", ToolRisk.READ_ONLY, description = "List persistent marketplace watches and their most recently discovered new listings."),
                setOf(ToolCapability.READ_STATE),
                setOf("marketplace_watch_list")
            ),
            ModuleTool(
                ToolSpec("marketplace.watch.create", ToolRisk.MUTATING, setOf("query"), "Create a persistent marketplace watch. Optional provider/location/category/interval_minutes/notify; background polling runs while the Termux bridge is alive."),
                setOf(ToolCapability.READ_STATE, ToolCapability.NETWORK),
                setOf("marketplace_watch_create")
            ),
            ModuleTool(
                ToolSpec("marketplace.watch.poll", ToolRisk.MUTATING, setOf("watch_id"), "Poll one existing marketplace watch now, advance its seen cursor, and optionally send the configured local notification."),
                setOf(ToolCapability.READ_STATE, ToolCapability.NETWORK),
                setOf("marketplace_watch_poll")
            ),
            ModuleTool(
                ToolSpec("marketplace.watch.remove", ToolRisk.MUTATING, setOf("watch_id"), "Remove a persistent marketplace watch."),
                setOf(ToolCapability.READ_STATE),
                setOf("marketplace_watch_remove")
            )
        ),
        // marketplace.* arrived in Termux bridge 0.28.
        requiresBridge = "0.28"
    )

    override fun route(normalized: String, lower: String): TaskIntentProfile? {
        if (!isMarketplaceSearch(lower)) return null
        val mcpLead = if (ModuleRegistry.isEnabled(McpModule.ID)) {
            "If a configured MCP provider matches the marketplace/domain, mcp.search is preferred as a read-only discovery path; otherwise use marketplace.search for Polish listing discovery."
        } else {
            "Use marketplace.search for Polish listing discovery."
        }
        return TaskIntentProfile(
            intent = TaskIntent.PUBLIC_WEB,
            confidence = 92,
            recommendedTools = listOf(
                "mcp.search",
                "marketplace.search",
                "marketplace.watch.list",
                "marketplace.watch.create",
                "marketplace.watch.poll",
                "marketplace.watch.remove",
                "web.search"
            ),
            guidance = "$mcpLead For ongoing monitoring, create a marketplace.watch only after explicit approval; watches poll in the Termux bridge while it is running. OLX.pl direct HTML/API access may be blocked, so v1 can return indexed discovery evidence rather than verified listing detail.",
            preflight = IntentPreflight(
                tool = "marketplace.search",
                args = mapOf(
                    "query" to marketplaceSearchQuery(normalized),
                    "category" to if (isJobMarketplaceSearch(lower)) "jobs" else "all"
                ),
                reason = "Search the configured Polish marketplace provider before making current listing claims.",
                mandatory = true
            ),
            minimumToolSteps = 4
        )
    }

    override fun sourceEvidenceTools(goal: String): List<String>? =
        if (isMarketplaceGoal(goal)) listOf("marketplace.search", "mcp.search") else null

    private fun marketplaceSearchQuery(goal: String): String {
        var query = goal
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        val leading = Regex(
            "(?iu)^(?:знайди|знайти|пошукай|шукай|подивись|глянь|найди|find|search|znajdź|wyszukaj|sprawdź)\\s+"
        )
        query = query.replace(leading, "")
        return query
            .trim()
            .trim(' ', '.', ',', ':', ';', '-', '—')
            .take(240)
            .ifBlank { goal.trim().take(240) }
    }

    private fun isJobMarketplaceSearch(lower: String): Boolean =
        listOf(
            "ваканс", "робот", "праця", "praca", "ofert pracy",
            "job", "jobs", "zatrud", "stanowisk"
        ).any { TaskIntentRouter.containsTerm(lower, it) }

    private fun isMarketplaceSearch(lower: String): Boolean {
        val marketplaceSubject = listOf(
            "olx", "олх", "оголош", "огалаш", "ogłosz", "oglosz",
            "marketplace", "classified"
        ).any { TaskIntentRouter.containsTerm(lower, it) }

        val listingAction = listOf(
            "знайд", "пошук", "шукай", "подив", "падив", "глянь", "перевір",
            "find", "search", "watch", "monitor",
            "znajd", "wyszuk", "sprawd", "śled", "sled",
            "нов", "nowe", "ofert", "ваканс", "робот", "praca", "job"
        ).any { TaskIntentRouter.containsTerm(lower, it) }

        val watchCue = listOf(
            "автомат", "слідку", "стеж", "монітор", "monitor", "watch",
            "powiad", "śled", "sled", "нові ваканс", "nowe ofert"
        ).any { TaskIntentRouter.containsTerm(lower, it) }

        return (marketplaceSubject && listingAction) ||
            (watchCue && isJobMarketplaceSearch(lower))
    }

    /** Goal-contract matcher: plain substring, as before the module split. */
    private fun isMarketplaceGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        val marketplace = listOf(
            "olx", "оголош", "ogłosz", "marketplace", "classified"
        ).any { lower.contains(it) }
        val watchJob =
            listOf(
                "ваканс", "робот", "praca", "job", "ofert pracy"
            ).any { lower.contains(it) } &&
                listOf(
                    "слідку", "стеж", "монітор", "monitor", "watch",
                    "powiad", "нові ваканс", "nowe ofert"
                ).any { lower.contains(it) }
        return marketplace || watchJob
    }
}
