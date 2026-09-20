package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualGoalRouterTest {
    @Test
    fun routesExplicitUkrainianFindAndShowPhotoGoal() {
        val route = VisualGoalRouter.route(
            "знайди фото жінки в інтернеті і покажи"
        )

        assertNotNull(route)
        val query = route!!.query.lowercase()
        assertTrue(query.contains("фото"))
        assertTrue(query.contains("жінки"))
        assertFalse(query.contains("знайди"))
        assertFalse(query.contains("покажи"))
        assertFalse(query.contains("інтернет"))
    }

    @Test
    fun routesEnglishAndPolishVisualGoals() {
        val english = VisualGoalRouter.route(
            "please find a photo of a red fox online and show me"
        )
        val polish = VisualGoalRouter.route(
            "znajdź zdjęcie starego samochodu w internecie i pokaż"
        )

        assertNotNull(english)
        assertTrue(english!!.query.contains("photo", ignoreCase = true))
        assertTrue(english.query.contains("red fox", ignoreCase = true))

        assertNotNull(polish)
        assertTrue(polish!!.query.contains("zdjęcie", ignoreCase = true))
        assertTrue(polish.query.contains("samochodu", ignoreCase = true))
    }

    @Test
    fun doesNotRouteNonSearchDiscussionAboutImages() {
        assertNull(
            VisualGoalRouter.route(
                "поясни, як працює image compression у нейромережах"
            )
        )
    }

    @Test
    fun queryIsBounded() {
        val route = VisualGoalRouter.route(
            "знайди фото " + "кота ".repeat(100) + "і покажи"
        )

        assertNotNull(route)
        assertTrue(route!!.query.length <= 180)
    }
}
