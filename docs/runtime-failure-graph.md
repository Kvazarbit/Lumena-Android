# Runtime failure graph — v0.12.2

The agent is treated as a recursive control fabric: every layer receives state,
produces an observation, and must either advance with evidence or stop without
inventing success. The same invariants repeat from provider -> transport -> tool ->
controller -> model -> workflow -> UI.

## Layer graph

```text
USER GOAL
  |
  v
INTENT ROUTER
  |
  v
PREFLIGHT / MODEL DECISION
  |
  +--> TOOL EXECUTOR --> BRIDGE --> REMOTE PROVIDER
  |        ^                         |
  |        |<---- exact result ------+
  |
  +--> MODEL CLIENT --> OLLAMA / EMBEDDED LLAMA
           ^               |
           |<-- exact error-+
  |
  v
AGENT CONTROLLER
  |  evidence ledger + retry budgets + circuit breakers
  v
WORKFLOW RUNNER
  |  bounded loop, checkpoint before external effect
  v
UI / PERSISTED SESSION
```

## Repeating invariants

1. Truth preservation: lower-layer errors keep their real cause.
2. Bounded recovery: only the layer with enough evidence may retry.
3. Monotonic evidence: failed observations stay failed.
4. Monotonic failure budgets: failed tools cannot erase model failures.
5. Lexical intent routing: command words do not match arbitrary substrings.
6. Hard context ceiling: compaction never exceeds its configured budget.
7. No ambiguous replay: mutating tools are not transport-replayed after lost replies.

## Critical paths

### Failed web search

```text
web.search
 -> bridge exhausts configured providers
 -> ToolResult(ok=false, exact error)
 -> web-search circuit breaker
 -> WorkflowOutcome.Failed
 -> STOP
```

No model call occurs between failed search and STOP.

### Ollama context pressure

```text
Ollama stream error
 -> preserve exact error
 -> one compact retry inside OllamaClient
 -> success: continue
 -> second context failure: controller classifies non-retryable
 -> STOP
```

### Model failure followed by tool failure

```text
model failure count = N
 -> recovery produces a valid tool call
 -> tool fails
 -> model failure count remains N
 -> next model failure becomes N+1
 -> normal retry limit applies
```

## Regression anchors

- `WorkflowRunnerTest.failedMandatoryWebPreflightStopsBeforeAnyModelTurn`
- `AgentControllerTest.failedNonWebToolCannotEraseExistingModelFailureBudget`
- `OllamaClientHttpTest.nonContextStreamErrorIsPreservedAndNeverRetried`
- `OllamaClientHttpTest.contextPressureRetriesExactlyOnceThenSucceeds`
- `TaskIntentRouterTest.latestWebQueryDoesNotAccidentallyMatchCodeTestKeyword`
- `OllamaContextPolicyTest.compactionStrictlyHonorsTotalBudgetUnderLargeSystemAndLatestTurn`

Phone process exits remain a separate evidence domain: if a real device still closes
the APK, use ApplicationExitInfo/logcat to distinguish Java crash, native crash, ANR,
low-memory kill and user/system termination.
