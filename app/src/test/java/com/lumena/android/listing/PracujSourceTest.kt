package com.lumena.android.listing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pracuj.pl search pages carry offers in __NEXT_DATA__. The fixture mirrors
 * the field names seen on the live page on 2026-10-09.
 */
class PracujSourceTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()

    private fun page(nextData: String) =
        "<html><body><div>x</div><script id=\"__NEXT_DATA__\" type=\"application/json\">$nextData</script></body></html>"

    private val forklift = """
        {"jobTitle":"Magazynier – kierowca wózka widłowego (k/m)","companyName":"Randstad Polska Sp. z o.o.",
         "lastPublicated":"2026-10-09T07:47:00Z","initialPublicated":"2026-09-09T07:47:00Z",
         "salaryDisplayText":"6 309 zł brutto / mies.","typesOfContract":["Umowa o pracę tymczasową"],
         "workSchedules":["Pełny etat"],"positionLevels":["Pracownik fizyczny / Pracowniczka fizyczna"],
         "primaryAttributes":[{"code":"many-vacancies","label":{"text":"Szukamy wielu kandydatów"}},
                              {"code":"immediate-employment","label":{"text":"Praca od zaraz"}}],
         "jobDescription":"Obsługa wózka widłowego, rozładunek dostaw.",
         "offers":[{"partitionId":1,"offerAbsoluteUri":"https://www.pracuj.pl/praca/magazynier,oferta,1","displayWorkplace":"Legionowo"}]}
    """.trimIndent()

    private val driver = """
        {"jobTitle":"Kierowca","companyName":"Tomo Tomasz Szybiński",
         "lastPublicated":"2026-10-09T06:53:38.313Z","initialPublicated":"2026-10-09T06:53:38.313Z",
         "salaryDisplayText":"9 000–12 000 zł netto (+ VAT) / mies.","typesOfContract":["Kontrakt B2B"],
         "jobDescription":"Przewóz towarów samochodem dostawczym, prawo jazdy kat. B.",
         "offers":[{"partitionId":2,"offerAbsoluteUri":"https://www.pracuj.pl/praca/kierowca,oferta,2","displayWorkplace":"Łomianki"}]}
    """.trimIndent()

    private val untitled = """{"companyName":"Bez tytułu","offers":[]}"""
    private val foreignLink = """
        {"jobTitle":"Pomocnik","lastPublicated":"2026-10-09T05:00:00Z",
         "offers":[{"offerAbsoluteUri":"https://evil.example/phish","displayWorkplace":"Legionowo"}]}
    """.trimIndent()

    private val fixture = page(
        """{"props":{"pageProps":{"dehydratedState":{"queries":[
            {"state":{"data":{"groupedOffers":[$forklift,$driver,$untitled,$foreignLink]}}},
            {"state":{"data":{"groupedOffers":[$driver]}}}
        ]}}}}"""
    )

    private fun offers(): List<ListingNotice> =
        (PracujSource.parse(fixture, now) as PracujPage.Offers).notices

    @Test fun parsesOffersFromTheLargestListAndSkipsUntitledOnes() {
        val notices = offers()
        assertEquals(listOf("Magazynier – kierowca wózka widłowego (k/m)", "Kierowca", "Pomocnik"), notices.map { it.title })
        notices.forEach { assertEquals(PracujSource.SOURCE, it.source) }
        val first = notices.first()
        assertEquals("https://www.pracuj.pl/praca/magazynier,oferta,1", first.url)
        assertEquals(Instant.parse("2026-10-09T07:47:00Z").toEpochMilli(), first.postedAt)
        listOf("Randstad Polska", "Legionowo", "6 309 zł", "Szukamy wielu kandydatów", "Praca od zaraz").forEach {
            assertTrue(it, first.text.contains(it))
        }
    }

    @Test fun republishedOfferIsMarkedFromItsOwnDates() {
        val (forkliftNotice, driverNotice) = offers()
        assertTrue(forkliftNotice.text.contains("odnawiane ogłoszenie (pierwsza publikacja 09.09.2026)"))
        assertFalse(driverNotice.text.contains("odnawiane"))
    }

    @Test fun onlyPracujLinksAreKept() {
        assertNull(offers().last().url)
    }

    @Test fun turnoverSignalsReachTheAttentionSkill() {
        val (forkliftNotice, driverNotice) = offers()
        val forklift = requireNotNull(ListingAttentionPolicy.ingest(ListingAttentionState(), forkliftNotice).record)
        assertEquals(1.0, forklift.features.getValue("mass_hiring"), 1e-9)
        assertNotEquals(ListingDecision.ALERT, forklift.decision)
        assertEquals(forkliftNotice.url, forklift.url)

        val driver = requireNotNull(ListingAttentionPolicy.ingest(ListingAttentionState(), driverNotice).record)
        assertTrue(driver.reasons.any { it.startsWith("навички:") && "водіння" in it })
        assertEquals(0.0, driver.features.getValue("mass_hiring"), 1e-9)
    }

    @Test fun samePageTwiceAddsNothing() {
        var state = ListingAttentionState()
        offers().forEach { state = ListingAttentionPolicy.ingest(state, it).state }
        val again = offers().map { ListingAttentionPolicy.ingest(state, it) }
        assertTrue(again.all { it.duplicate })
    }

    @Test fun pageWithoutOfferDataIsReportedAsBlockedNotEmpty() {
        assertTrue(PracujSource.parse("<html>Just a moment...</html>", now) is PracujPage.Blocked)
        assertTrue(PracujSource.parse(page("""{"props":{"pageProps":{}}}"""), now) is PracujPage.Blocked)
        assertTrue(PracujSource.parse(page("not json"), now) is PracujPage.Blocked)
        val empty = PracujSource.parse(page("""{"a":{"groupedOffers":[]}}"""), now)
        assertEquals(PracujPage.Offers(emptyList()), empty)
    }

    @Test fun searchUrlFoldsPolishLettersAndClampsRadius() {
        assertEquals(
            "https://www.pracuj.pl/praca/legionowo;wp?rd=10",
            PracujSource.searchUrl(PracujSearch("Legionowo", 10, ""))
        )
        assertEquals(
            "https://www.pracuj.pl/praca/kierowca-kat-b;kw/lomianki;wp?rd=25",
            PracujSource.searchUrl(PracujSearch("Łomianki", 25, "Kierowca kat. B"))
        )
        assertEquals(
            "https://www.pracuj.pl/praca/legionowo;wp?rd=100",
            PracujSource.searchUrl(PracujSearch("  ", 500, ""))
        )
    }
}
