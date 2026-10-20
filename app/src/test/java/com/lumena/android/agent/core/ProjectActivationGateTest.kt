package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class ProjectActivationGateTest {
    @Test fun inferredIdDoesNotAuthorizeProjectFiles() {
        assertNotNull(ProjectActivationGate.problem("p", null))
        assertNotNull(ProjectActivationGate.problem("p", "other"))
        assertNull(ProjectActivationGate.problem("p", "p"))
        assertNull(ProjectActivationGate.problem(null, null))
    }
    @Test fun toolsCannotFallBackToGlobalWorkspace() {
        for (tool in listOf("project.inspect", "file.read", "file.write",
            "python.tests", "inspect.batch", "workspace.list", "git.status", "context.snapshot")) {
            assertTrue(tool, ProjectActivationGate.requiresRoot(tool))
        }
        assertFalse(ProjectActivationGate.requiresRoot("web.search"))
        assertFalse(ProjectActivationGate.requiresRoot("health"))
    }
}
