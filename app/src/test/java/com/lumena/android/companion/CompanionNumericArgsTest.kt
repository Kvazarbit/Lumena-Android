package com.lumena.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompanionNumericArgsTest {
    private fun parse(argsJson: String) = requireNotNull(
        CompanionProtocol.parseVisibleText(
            "LUMENA_TOOL\n" +
                "{\"session_id\":\"project-7f3a\",\"task_id\":\"transport-01\"," +
                "\"model_id\":\"chatgpt\",\"tool\":\"python.run\"," +
                "\"args\":$argsJson,\"reason\":\"regression\"}"
        )
    )

    @Test
    fun integerJsonTimeoutDoesNotBecomeFractionalString() {
        val command = parse("""{"script":"scripts/job.py","timeout":30}""")
        assertEquals("30", command.decision.request.args["timeout"])
        assertEquals("scripts/job.py", command.decision.request.args["script"])
    }

    @Test
    fun genuineFractionalValueIsNotSilentlyRounded() {
        val command = parse("""{"script":"scripts/job.py","timeout":30.5}""")
        assertEquals("30.5", command.decision.request.args["timeout"])
    }

    @Test
    fun quotedTimeoutIsNotSilentlyRewritten() {
        val command = parse("""{"script":"scripts/job.py","timeout":"30.0"}""")
        assertEquals("30.0", command.decision.request.args["timeout"])
    }

    @Test
    fun normalizedNumericAndQuotedTextHaveDifferentFingerprints() {
        val integral = parse("""{"script":"scripts/job.py","timeout":30}""")
        val literal = parse("""{"script":"scripts/job.py","timeout":"30.0"}""")
        assertNotEquals(integral.fingerprint, literal.fingerprint)
    }
}
