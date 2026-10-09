package com.lumena.android.modules

import com.lumena.android.agent.core.LumenaModule
import com.lumena.android.agent.core.ModuleManifest

/**
 * Pracuj.pl jobs as a source for the listing-attention skill. It adds no
 * agent tools and needs no Termux: WorkManager reads the public search page
 * at the owner's interval and the attention skill scores each offer.
 */
object PracujModule : LumenaModule {
    const val ID = "pracuj-jobs"

    override val manifest = ModuleManifest(
        id = ID,
        version = "1",
        title = "Вакансії Pracuj.pl",
        description = "Періодично читає публічний пошук Pracuj.pl (місто, радіус, ключові слова) і передає вакансії навику відбору. Без Termux, без обходу захисту сайту."
    )
}
