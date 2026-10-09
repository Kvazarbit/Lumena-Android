package com.lumena.android.listing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skill must surface a rare private offer that matches the owner's
 * skills, keep constant-turnover offers quiet, and learn only from the
 * owner's explicit 👍/👎.
 */
class ListingAttentionPolicyTest {
    private val source = ListingNoticeExtractor.OLX_PL_PACKAGE
    private val t0 = 1_760_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private val personalDriver = "Szukam kierowcy do różnych zadań – Legionowo" to
        "Potrzebuję osoby z prawem jazdy kat. B do dyspozycji, elastyczne godziny."
    private val toilets = "Sprzątanie toalet – praca od zaraz" to
        "Agencja pracy zatrudni osoby do sprzątania, bez doświadczenia, praca zmianowa, także w weekendy."

    private fun ingest(
        state: ListingAttentionState,
        listing: Pair<String, String>,
        at: Long = t0
    ): ListingIngestResult =
        ListingAttentionPolicy.ingest(state, ListingNotice(source, listing.first, listing.second, at))

    private fun first(listing: Pair<String, String>): ListingRecord =
        requireNotNull(ingest(ListingAttentionState(), listing).record)

    @Test fun rarePrivateSkillMatchingRequestAlerts() {
        val record = first(personalDriver)
        assertEquals(ListingDecision.ALERT, record.decision)
        assertTrue(record.reasons.any { it.startsWith("навички:") && "водіння" in it })
        assertTrue("схоже на приватну особу" in record.reasons)
    }

    @Test fun constantTurnoverListingStaysQuietWithDeclaredReasons() {
        val record = first(toilets)
        assertEquals(ListingDecision.QUIET, record.decision)
        assertTrue(record.reasons.any { it.startsWith("масовий набір:") })
        assertTrue(record.reasons.any { it.startsWith("заявлено:") && "прибирання туалетів" in it })
    }

    @Test fun corporateCvOfferIsNotAnAlertEvenWhenTheSkillMatches() {
        val record = first(
            "Elektryk – umowa o pracę" to
                "Firma zatrudni elektryka. Wymagania: doświadczenie. Aplikuj, prześlij CV."
        )
        assertEquals(1.0, record.features.getValue("skill") * 2, 1e-9)
        assertNotEquals(ListingDecision.ALERT, record.decision)
    }

    @Test fun firstPersonWordingIsPrivateButCompanyThirdPersonIsNot() {
        val person = first("Poszukuję kierowcy" to "Kierowca kat. B, Legionowo.")
        val company = first("Firma poszukuje kierowcy" to "Kierowca kat. B, Legionowo.")
        assertEquals(1.0, person.features.getValue("private"), 1e-9)
        assertEquals(0.0, company.features.getValue("private"), 1e-9)
        assertEquals(ListingDecision.ALERT, person.decision)
        assertNotEquals(ListingDecision.ALERT, company.decision)
    }

    @Test fun ukrainianListingIsUnderstood() {
        val record = first("Шукаю водія" to "Потрібен водій з категорією B для різних завдань, Легіоново.")
        assertEquals(ListingDecision.ALERT, record.decision)
        assertTrue(record.reasons.any { "водіння" in it })
    }

    @Test fun declaredHarmfulScheduleBlocksAnOtherwiseMatchingPrivateOffer() {
        val record = first(
            "Szukam kierowcy na noce" to
                "Potrzebuję kierowcy, praca w nocy i w weekendy, 6 dni w tygodniu."
        )
        assertEquals(1.0, record.features.getValue("red_flag"), 1e-9)
        assertNotEquals(ListingDecision.ALERT, record.decision)
    }

    @Test fun sameNotificationTwiceIsOneListing() {
        val once = ingest(ListingAttentionState(), personalDriver).state
        val twice = ingest(once, personalDriver, at = t0 + hour)
        assertTrue(twice.duplicate)
        assertNull(twice.record)
        assertEquals(1, twice.state.records.size)
    }

    @Test fun stablePracujUrlIsNeverNewAgainInsideRetentionEvenAfter48Hours() {
        val first = ListingNotice(
            source = "pracuj.pl",
            title = "Technik",
            text = "Firma A · Legionowo · 6 000 zł",
            postedAt = t0 - 14 * day,
            url = "https://www.pracuj.pl/praca/technik,oferta,123",
            observedAt = t0
        )
        val initial = ListingAttentionPolicy.ingest(ListingAttentionState(), first)
        assertFalse(initial.duplicate)
        assertEquals(t0, requireNotNull(initial.record).seenAt)

        val refreshed = first.copy(
            text = "Firma A · Legionowo · 6 500 zł",
            postedAt = t0 + 4 * day,
            observedAt = t0 + 5 * day
        )
        val again = ListingAttentionPolicy.ingest(initial.state, refreshed)
        assertTrue(again.duplicate)
        assertNull(again.record)
        assertEquals(1, again.state.records.size)
        assertEquals(t0, again.state.records.single().seenAt)
    }

    @Test fun sameTextButDistinctPracujUrlsRemainDistinctOffers() {
        val shared = ListingNotice(
            source = "pracuj.pl",
            title = "Monter",
            text = "Firma A · Legionowo",
            postedAt = t0,
            url = "https://www.pracuj.pl/praca/monter,oferta,1",
            observedAt = t0
        )
        val first = ListingAttentionPolicy.ingest(ListingAttentionState(), shared)
        val second = ListingAttentionPolicy.ingest(
            first.state,
            shared.copy(url = "https://www.pracuj.pl/praca/monter,oferta,2")
        )
        assertFalse(second.duplicate)
        assertEquals(2, second.state.records.size)
    }

    @Test fun repeatedRepostsAreTreatedAsTurnover() {
        var state = ListingAttentionState()
        val decisions = (0 until 3).map { i ->
            val result = ingest(state, personalDriver, at = t0 + i * 5 * day)
            state = result.state
            requireNotNull(result.record)
        }
        assertEquals(ListingDecision.ALERT, decisions[0].decision)
        assertEquals(1.0, decisions[2].features.getValue("churn"), 1e-9)
        assertNotEquals(ListingDecision.ALERT, decisions[2].decision)
        assertTrue(decisions[2].reasons.any { it.startsWith("перепублікується:") })
    }

    @Test fun repeatedTitleIsTreatedAsSavedSearchLabelNotContent() {
        var state = ListingAttentionState()
        val texts = listOf(
            "Montaż szafek kuchennych, Legionowo",
            "Pomoc przy remoncie łazienki",
            "Sprzątanie biura wieczorami"
        )
        val records = texts.mapIndexed { i, text ->
            val result = ingest(state, "Kierowca – nowe ogłoszenia" to text, at = t0 + i * hour)
            state = result.state
            requireNotNull(result.record)
        }
        // From the third notice on, the shared title no longer counts as a driving skill.
        assertEquals(0.0, records[2].features.getValue("skill"), 1e-9)
        assertEquals(ListingDecision.QUIET, records[2].decision)
    }

    @Test fun rarityWaitsForAnArchive() {
        val cold = first(personalDriver)
        assertEquals(0.0, cold.features.getValue("novelty"), 1e-9)
        assertTrue(cold.reasons.any { it.startsWith("архів ще накопичується") })

        var state = ListingAttentionState()
        repeat(ListingAttentionPolicy.WARMUP_RECORDS) { i ->
            state = ingest(state, "Sklep $i" to "Kasjer sklep spożywczy zmiana $i", at = t0 + i * hour).state
        }
        val warm = requireNotNull(ingest(state, personalDriver, at = t0 + 60 * hour).record)
        assertTrue(warm.features.getValue("novelty") > 0.9)
        assertTrue("рідкісне: схожого не було" in warm.reasons)
    }

    @Test fun ingestingAndAlertingNeverTeachTheSkill() {
        var state = ListingAttentionState()
        repeat(200) { i ->
            state = ingest(state, "Kierowca $i" to "Szukam kierowcy, zadanie $i", at = t0 + i * hour).state
        }
        assertTrue(state.records.any { it.decision == ListingDecision.ALERT })
        assertEquals(ListingLearner(), state.learner)
    }

    @Test fun ownerFeedbackSeparatesUnwantedFromWantedOffers() {
        val moving = listOf(
            "Szukam kierowcy do przeprowadzki" to "Potrzebuję pomocy przy przeprowadzce, mam bus",
            "Przeprowadzka – szukam pomocnika z autem" to "W sobotę przewóz mebli z Legionowa do Warszawy",
            "Szukam osoby do przewozu mebli" to "Przeprowadzka mieszkania, potrzebuję kierowcy z prawem jazdy",
            "Pomoc przy przeprowadzce" to "Szukam kierowcy busa na jeden dzień, przeprowadzka"
        )
        val personal = listOf(
            "Kierowca osobisty – Jabłonna" to "Szukam kierowcy do dyspozycji rodziny, różne sprawy na mieście",
            "Szukam osobistego kierowcy" to "Potrzebuję kierowcy kilka razy w tygodniu, załatwianie spraw, Serock",
            "Kierowca i pomoc w domu" to "Szukam osoby z prawem jazdy do różnych zadań i drobnych napraw",
            "Prywatny kierowca" to "Potrzebuję kierowcy do wożenia mnie na spotkania, Legionowo"
        )
        val probeMoving = "Szukam kierowcy na przeprowadzkę" to "Przeprowadzka biura, potrzebuję pomocy, Legionowo"

        var state = ListingAttentionState()
        var at = t0
        val before = requireNotNull(ingest(state, probeMoving, at).record)
        assertEquals(ListingDecision.ALERT, before.decision)

        moving.zip(personal).forEach { (unwanted, wanted) ->
            val a = ingest(state, unwanted, at)
            state = ListingAttentionPolicy.feedback(a.state, requireNotNull(a.record).id, useful = false, now = at)
            at += 6 * hour
            val b = ingest(state, wanted, at)
            state = ListingAttentionPolicy.feedback(b.state, requireNotNull(b.record).id, useful = true, now = at)
            at += 6 * hour
        }

        val afterMoving = requireNotNull(ingest(state, probeMoving, at).record)
        val afterDriver = requireNotNull(ingest(state, personalDriver, at).record)
        assertTrue(afterMoving.score < before.score)
        assertNotEquals(ListingDecision.ALERT, afterMoving.decision)
        assertEquals(ListingDecision.ALERT, afterDriver.decision)
        assertEquals(8, state.learner.updates)
    }

    @Test fun sameFeedbackTwiceCountsOnce() {
        val result = ingest(ListingAttentionState(), personalDriver)
        val id = requireNotNull(result.record).id
        val once = ListingAttentionPolicy.feedback(result.state, id, useful = true, now = t0)
        val twice = ListingAttentionPolicy.feedback(once, id, useful = true, now = t0 + 1)
        assertSame(once, twice)
        assertEquals(1, twice.learner.updates)
    }

    @Test fun feedbackOnUnknownRecordChangesNothing() {
        val state = ingest(ListingAttentionState(), personalDriver).state
        assertSame(state, ListingAttentionPolicy.feedback(state, "missing", useful = true, now = t0))
    }

    @Test fun metricsReportPrecisionAndMissedOffers() {
        var state = ListingAttentionState()
        val alert = ingest(state, personalDriver).also { state = it.state }.record!!
        val quiet = ingest(state, toilets, at = t0 + hour).also { state = it.state }.record!!
        assertNull(ListingAttentionPolicy.metrics(state).alertPrecision())

        state = ListingAttentionPolicy.feedback(state, alert.id, useful = true, now = t0 + 2 * hour)
        state = ListingAttentionPolicy.feedback(state, quiet.id, useful = true, now = t0 + 3 * hour)
        val metrics = ListingAttentionPolicy.metrics(state)
        assertEquals(2, metrics.seen)
        assertEquals(1, metrics.alerts)
        assertEquals(1.0, requireNotNull(metrics.alertPrecision()), 1e-9)
        assertEquals(1, metrics.missed)
        assertEquals(2, metrics.learningUpdates)
    }

    @Test fun privateChatIsNeverScoredOrCaptured() {
        val chat = ListingRawCapture(source, messaging = true, title = "Jan", text = "Czy oferta aktualna?", at = t0)
        val byCategory = chat.copy(messaging = false, category = "msg")
        assertTrue(ListingNoticeExtractor.extract(chat).isEmpty())
        assertTrue(ListingNoticeExtractor.extract(byCategory).isEmpty())
        val state = ListingAttentionState()
        assertSame(state, ListingAttentionPolicy.capture(state, chat))
        assertSame(state, ListingAttentionPolicy.capture(state, byCategory))
    }

    @Test fun extractorHandlesInboxLinesSummariesAndOtherApps() {
        val inbox = ListingRawCapture(
            source,
            title = "3 nowe ogłoszenia",
            lines = listOf("Kierowca osobisty", "Pomoc przy remoncie", " "),
            at = t0
        )
        assertEquals(listOf("Kierowca osobisty", "Pomoc przy remoncie"), ListingNoticeExtractor.extract(inbox).map { it.title })
        assertTrue(ListingNoticeExtractor.extract(inbox.copy(groupSummary = true)).isEmpty())
        assertTrue(ListingNoticeExtractor.extract(inbox.copy(source = "com.whatsapp")).isEmpty())
        assertFalse(ListingNoticeExtractor.isWatchedSource("com.olx.southasia"))
        assertFalse(ListingNoticeExtractor.isWatchedSource("org.example.olx.spoof"))
        assertTrue(ListingNoticeExtractor.isWatchedSource("pl.tablica"))
        assertFalse(ListingNoticeExtractor.isWatchedSource("com.lumena.android"))

        val single = ListingNoticeExtractor.extract(ListingRawCapture(source, title = " Kierowca ", text = " Legionowo ", at = t0))
        assertEquals(ListingNotice(source, "Kierowca", "Legionowo", t0), single.single())
    }

    @Test fun rawCapturesAreBounded() {
        var state = ListingAttentionState()
        repeat(ListingAttentionPolicy.MAX_RAW_CAPTURES + 5) { i ->
            state = ListingAttentionPolicy.capture(state, ListingRawCapture(source, title = "n$i", at = t0 + i))
        }
        assertEquals(ListingAttentionPolicy.MAX_RAW_CAPTURES, state.rawCaptures.size)
        assertEquals("n${ListingAttentionPolicy.MAX_RAW_CAPTURES + 4}", state.rawCaptures.last().title)
        assertNotNull(state.rawCaptures.first())
    }
}
