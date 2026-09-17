# Lumena Agent Core v0.7

Core rule: the model proposes; the application validates, executes and verifies.

## Implemented in this phase
- `AgentDecision`: Reply / ToolCall / Done
- deterministic `TaskState` and lifecycle statuses
- canonical `ToolRegistry` with exact aliases and required-argument validation
- explicit external-source confirmation semantics
- robust `AgentResponseParser` for weak-model output
- `LoopDetector` for repeated identical tool calls
- JVM tests for parser, registry and loop detection
- legacy `ToolGate` now delegates to the canonical registry

## Security invariants
- unknown tools are blocked
- no fuzzy matching of tool names
- external ChatGPT-originated tool calls always require confirmation
- mutating and executable tools require confirmation
- oversized arguments are blocked before execution

## Next phase
- `AgentController` state machine
- failure budgets and retry policy
- context builder with compact verified task state
- verifier abstraction
- `file.patch` + automatic backup/rollback
