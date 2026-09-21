# Runtime failure graph — v0.12.2

This note treats the agent as a recursive control fabric: every layer receives an
input state, produces an observation, and must either advance with evidence or stop
without inventing success. The same invariants repeat from provider -> transport ->
tool -> controller -> model -> workflow -> UI.

## Layer graph

```text
USER GOAL
  |
  v
INTENT ROUTER
  |  lexical classification; no substring collisions
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

1. **Truth preservation.** A lower-layer error is never rewritten as a different
   success/failure cause. Ollama top-level `error` text survives streaming parse;
   bridge provider errors survive in `ToolResult.error`.
2. **Bounded recovery.** Recovery belongs to the layer that has enough information.
   Ollama may make one compact retry for timeout/context pressure. `web.search`
   already exhausts configured providers, so a failed search trips the controller
   circuit breaker and consumes zero additional model turns.
3. **Monotonic evidence.** A failed observation stays failed in the context kernel.
   Model prose cannot convert it into completion evidence.
4. **Monotonic failure budgets.** A failed tool call cannot erase an existing model
   failure count. Only successful tool evidence resets the model-failure budget.
5. **Lexical intent routing.** ASCII command words are token-aware. For example,
   `latest` cannot satisfy the code keyword `test`; explicit `test Python code`
   still routes to code work.
6. **Hard context ceiling.** Prompt compaction reserves generation/tokenizer headroom,
   preserves the newest user turn, and never exceeds its configured character budget.
7. **No ambiguous replay.** Mutating bridge actions are not transport-retried after
   a lost response. Read-only transport retries are bounded.

## Failure paths

### Search provider unavailable

```text
web.search
 -> bridge tries configured providers
 -> no usable evidence / HTTP 202 challenge
 -> ToolResult(ok=false, exact error)
 -> AgentController web-search circuit breaker
 -> WorkflowOutcome.Failed
 -> STOP
```

There is no model call between the failed search and STOP.

### Ollama context pressure

```text
Ollama stream error
 -> preserve exact `error`
 -> one smaller-context retry inside OllamaClient
 -> success: continue
 -> second context failure: AgentController marks non-retryable
 -> STOP
```

### Model failure followed by tool failure

```text
model failure count = N
 -> recovery model turn produces valid tool call
 -> tool fails
 -> model failure count remains N
 -> another model failure increments N+1
 -> normal retry limit applies
```

A failed tool therefore cannot reopen the model retry budget.

## Regression matrix

| Boundary | Required property | Regression |
|---|---|---|
| intent -> workflow | `latest` routes to PUBLIC_WEB, not CODE_WORK | `TaskIntentRouterTest.latestWebQueryDoesNotAccidentallyMatchCodeTestKeyword` |
| bridge -> controller | failed `web.search` ends current task | `AgentControllerTest.failedWebSearchTripsCircuitBreakerBeforeAnotherModelTurn` |
| workflow -> model | failed mandatory search uses 0 model turns | `WorkflowRunnerTest.failedMandatoryWebPreflightStopsBeforeAnyModelTurn` |
| Ollama HTTP -> parser | provider stream error text is preserved | `OllamaClientHttpTest.nonContextStreamErrorIsPreservedAndNeverRetried` |
| Ollama retry | context pressure retries exactly once | `OllamaClientHttpTest.contextPressureRetriesExactlyOnceThenSucceeds` |
| controller budget | failed tool cannot reset model failure count | `AgentControllerTest.failedNonWebToolCannotEraseExistingModelFailureBudget` |
| context policy | prompt never exceeds hard character ceiling | `OllamaContextPolicyTest.compactionStrictlyHonorsTotalBudgetUnderLargeSystemAndLatestTurn` |
| tiny context edge | omission marker cannot overflow the budget | `OllamaContextPolicyTest.tinySystemBudgetStillCannotOverflowFromOmissionMarker` |

## Phone-only evidence still required

JVM/CI tests can prove state-machine, parser, retry and build invariants. They cannot
prove the cause of a real Android process death. If the app process still exits on a
phone after these loop fixes, use Android `ApplicationExitInfo` / logcat evidence to
separate Java crash, native crash, ANR, low-memory kill and user/system termination.
