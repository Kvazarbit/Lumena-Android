package com.lumena.android.collaboration

import com.lumena.android.agent.core.ProjectWorkspacePolicy
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.*
import org.junit.Test

/** The owner's verification boundary can be suggested from the bridge inventory, but only the owner saves it. */
class VerificationBoundaryProposalTest {
    private fun inspect(files: List<String>, cwd: String = "demo", truncated: Boolean = false, count: Int = files.size) =
        ToolResult(true, "project.inspect", 0,
            """{"schema":"lumena.project.v1","cwd":"$cwd","exists":true,"source_digest":"${"a".repeat(64)}",""" +
                """"files_count":$count,"truncated":$truncated,"files":[${files.joinToString(",") { "\"$it\"" }}]}""")

    @Test fun testDirectoryAndLooseTestFilesAreProposedAndSavableAsOwnerBoundary() {
        val files = listOf("src/app.py", "tests/test_a.py", "tests/helpers.py", "conftest.py", "pkg/test_b.py",
            "pkg/b_test.py", "test_root.py", "README.md")
        val proposal = VerificationBoundaryProposal.propose(files)
        assertEquals(listOf("tests", "conftest.py", "pkg/b_test.py", "pkg/test_b.py", "test_root.py"), proposal)
        proposal.forEach { assertEquals(it, ProjectWorkspacePolicy.root(it)) }
    }

    @Test fun nothingTestLikeMeansNoProposalAndUnsavablePathsAreDropped() {
        assertTrue(VerificationBoundaryProposal.propose(listOf("src/app.py", "README.md")).isEmpty())
        assertEquals(listOf("test_ok.py"), VerificationBoundaryProposal.propose(
            listOf("../test_up.py", "@shared/test_x.py", "test_ok.py", "a//test_y.py", "my tests/test_z.py", "c:/test_w.py")))
        assertEquals(VerificationBoundaryProposal.MAX_PATHS,
            VerificationBoundaryProposal.propose((1..40).map { "m$it/test_$it.py" }).size)
    }

    @Test fun onlyAMatchingProjectInventoryIsRead() {
        val files = listOf("tests/test_a.py")
        assertEquals(VerificationBoundaryProposal.Inventory(files, false), VerificationBoundaryProposal.inventory(inspect(files), "demo"))
        assertNull(VerificationBoundaryProposal.inventory(inspect(files, cwd = "other"), "demo"))
        assertNull(VerificationBoundaryProposal.inventory(ToolResult(false, error = "offline"), "demo"))
        assertNull(VerificationBoundaryProposal.inventory(ToolResult(true, stdout = "not json"), "demo"))
        assertNull(VerificationBoundaryProposal.inventory(null, "demo"))
        assertTrue(VerificationBoundaryProposal.inventory(inspect(files, truncated = true), "demo")!!.partial)
        assertTrue(VerificationBoundaryProposal.inventory(inspect(files, count = 200), "demo")!!.partial)
    }
}
