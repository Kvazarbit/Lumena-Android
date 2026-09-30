package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkThreadMemoryTest {
    @Test
    fun namedAquariumThreadSurvivesUnrelatedCodeTask() {
        val aquarium =
            "Створи в HTML+JS 3D акваріум з рибками і реалістичною поведінкою."
        var state =
            WorkThreadMemory.resolve(
                text = aquarium,
                state = WorkThreadState()
            ).state

        state =
            WorkThreadMemory.resolve(
                text = "Write Python script for a separate cause probe.",
                state = state
            ).state

        val resumed =
            WorkThreadMemory.resolve(
                text = "продовж роботу над акваріумом",
                state = state
            )

        assertTrue(resumed.continued)
        assertTrue(
            resumed.goal.contains(
                "3D акваріум"
            )
        )
        assertTrue(
            resumed.goal.contains(
                "продовж роботу над акваріумом"
            )
        )
        assertFalse(
            resumed.goal.contains(
                "separate cause probe"
            )
        )
    }

    @Test
    fun aquariumFileInspectionKeepsOriginalGoal() {
        val root =
            "Онови aquarium.html: зроби реалістичний 3D акваріум з рибками."
        val started =
            WorkThreadMemory.resolve(
                text = root,
                state = WorkThreadState()
            )

        val inspection =
            WorkThreadMemory.resolve(
                text = "знайди файл aquarium.html і проаналізуй його",
                state = started.state
            )

        assertTrue(inspection.continued)
        assertTrue(
            inspection.goal.contains(root)
        )
        assertTrue(
            inspection.goal.contains(
                "знайди файл aquarium.html"
            )
        )
        assertTrue(
            inspection.contextMessage
                .orEmpty()
                .contains(
                    "ACTIVE WORK THREAD"
                )
        )
    }

    @Test
    fun analysisTurnDoesNotEraseOriginalAquariumGoalBeforeBareContinue() {
        val root =
            "Онови aquarium.html: зроби реалістичний 3D акваріум з рибками."
        val started =
            WorkThreadMemory.resolve(
                text = root,
                state = WorkThreadState()
            )
        val analysis =
            WorkThreadMemory.resolve(
                text = "знайди файл aquarium.html і проаналізуй його",
                state = started.state
            )
        val continued =
            WorkThreadMemory.resolve(
                text = "продовж",
                state = analysis.state
            )

        assertTrue(analysis.continued)
        assertTrue(continued.continued)
        assertTrue(
            continued.goal.contains(root)
        )
        assertTrue(
            continued.goal.contains(
                "знайди файл aquarium.html"
            )
        )
    }

    @Test
    fun unrelatedGeneralConversationDoesNotEraseWorkAnchor() {
        val root =
            "Create HTML aquarium simulator with fish"
        val started =
            WorkThreadMemory.resolve(
                text = root,
                state = WorkThreadState()
            )
        val unrelated =
            WorkThreadMemory.resolve(
                text = "поясни різницю між RAM і SSD",
                state = started.state
            )

        assertEquals(
            1,
            unrelated.state.anchors.size
        )

        val resumed =
            WorkThreadMemory.resolve(
                text = "resume aquarium work",
                state = unrelated.state
            )
        assertTrue(resumed.continued)
        assertTrue(
            resumed.goal.contains(root)
        )
    }

    @Test
    fun oldChatRestoresNamedWorkGoalWithoutToolAuthority() {
        val state =
            WorkThreadMemory.restore(
                listOf(
                    "user" to
                        "Create HTML aquarium simulator with fish",
                    "assistant" to
                        "I will inspect it.",
                    "user" to
                        "Write Python calculator",
                    "assistant" to
                        "Done"
                )
            )

        assertEquals(
            2,
            state.anchors.size
        )

        val resumed =
            WorkThreadMemory.resolve(
                text = "continue aquarium",
                state = state
            )
        assertTrue(resumed.continued)
        assertTrue(
            resumed.goal.contains(
                "HTML aquarium simulator"
            )
        )
        assertTrue(
            resumed.contextMessage
                .orEmpty()
                .contains(
                    "not permission"
                )
        )
    }

    @Test
    fun bareContinueResumesCurrentlyActiveWorkThread() {
        val root =
            "Create HTML aquarium simulator"
        val started =
            WorkThreadMemory.resolve(
                text = root,
                state = WorkThreadState()
            )

        val bare =
            WorkThreadMemory.resolve(
                text = "продовж",
                state = started.state
            )

        assertTrue(bare.continued)
        assertTrue(
            bare.goal.contains(root)
        )
    }

    @Test
    fun unrelatedResearchClearsActiveWorkThreadButKeepsAnchor() {
        val started =
            WorkThreadMemory.resolve(
                text = "Create HTML aquarium simulator",
                state = WorkThreadState()
            )
        val research =
            WorkThreadMemory.resolve(
                text = "Знайди в інтернеті останні новини Python сьогодні",
                state = started.state
            )

        assertEquals(
            1,
            research.state.anchors.size
        )
        assertEquals(
            null,
            research.state.activeKey
        )

        val bare =
            WorkThreadMemory.resolve(
                text = "продовж",
                state = research.state
            )
        assertFalse(bare.continued)

        val named =
            WorkThreadMemory.resolve(
                text = "продовж акваріум",
                state = research.state
            )
        assertTrue(named.continued)
    }
}
