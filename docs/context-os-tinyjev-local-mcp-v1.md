# Lumena Context OS v1 — TinyJev + Local MCP

## Goal

Embed a local System-1 decision layer and a local MCP data layer directly in the Lumena APK without giving either one execution authority.

This is intentionally an incremental step on top of the verified Step 8.7 evidence/constitution stack.

Base functional head:

- \`763b04af7ec2012f21a0f0db5d081f921ae617f0\`
- PR #86: aggregate EvidenceGraph outcome remains monotonic while independent bindings continue PENDING -> APPLIED -> VERIFIED.

## What is implemented in this step

### 1. TinyJev v1

\`TinyJev\` is Lumena's APK-local System-1-style scorer.

It is **not** the proprietary TypeSafe Jev model.

It is a deliberately small auditable linear/softmax scorer:

- no network
- no API key
- no background service
- no tool executor
- no permission API
- no direct mutation path
- model weights are shipped inside the APK in \`assets/tinyjev-v1.properties\`
- fallback weights are compiled into code so a corrupt/missing asset fails safe to a known local model

Current use:

- TinyJev only re-ranks recovery options that:
  1. are already admitted by \`ConstitutionKernel -> ReflexKernel\`
  2. already have verified local support from \`ReflexExperienceRanker\`

It therefore cannot invent a new action family and cannot expand authority.

Raw softmax confidence is explicitly marked \`calibrated=false\`.

### 2. Local MCP runtime

\`LocalMcpRuntime\` implements the current MCP 2026-07-28 data-layer shape for the APK-local host:

- \`server/discover\` semantics through \`discover()\`
- \`tools/list\` semantics through \`listTools()\`
- \`tools/call\` semantics through \`callTool()\`
- protocol version: \`2026-07-28\`
- stateless
- in-process
- no network listener
- no new port
- projects the existing \`ToolRegistry\`
- forwards read-only execution to the existing \`ToolExecutor\`

Mutating or executable calls do **not** run directly. They return \`input_required\` so a confirmation-capable host flow can remain authoritative.

This keeps the current safety boundary:

\`\`\`
TinyJev / MCP
      |
      v
bounded proposal / tool request
      |
      v
ToolRegistry -> ToolGate -> Constitution / confirmation
      |
      v
executor
\`\`\`

## Why the model is intentionally small

The first goal is not general language understanding. The goal is low-cost bounded decisions such as:

- retry vs alternate provider
- retry vs planner escalation
- stop on unknown effect
- stop on policy denial
- switch route after provider challenge
- escalate under context/resource pressure

A large 4B-9B model is wasteful for this class of decision.

TinyJev v1 uses a compact feature vector and softmax ranking. Its parameter file is tiny enough to live directly in the APK.

## Rollout plan

### Stage A — current PR

- [x] APK-local TinyJev core
- [x] embedded model weights
- [x] Android asset loader
- [x] safe re-ranking integration into the existing reflex pipeline
- [x] APK-local MCP 2026-07-28 core
- [x] read-only MCP execution
- [x] side-effect calls fail closed to \`input_required\`
- [x] unit tests

### Stage B — calibration

Add a persistent \`TinyJevCalibrationStore\` fed only from verified outcomes.

Track by decision family:

- Brier score
- ECE
- selective accuracy
- coverage
- wrong-action rate
- fallback rate
- p50/p95 latency

Only after enough verified samples should a score be exposed as calibrated.

### Stage C — System-1 fast path

Allow a calibrated TinyJev decision to bypass the large planner only when all of the following are true:

- candidate set is bounded by Constitution
- risk class allows automation
- empirical calibration passes threshold for that decision family
- freshness/precondition check passes
- no user confirmation is required
- verifier exists for the expected effect

High-risk actions remain confirmation-gated regardless of confidence.

### Stage D — learned small model

Replace or augment the linear scorer with a genuinely trained small local model using replay data:

\`\`\`
(state, bounded candidates, selected candidate, verified outcome)
\`\`\`

Possible deployment targets:

- tiny MLP / linear model
- small encoder
- sub-1B GGUF classifier if the measured gain justifies memory/latency

The provider interface must remain stable so model replacement does not affect policy authority.

### Stage E — MCP transport

Current Local MCP remains in-process by design.

A bounded external **MCP search broker** is now implemented in the Termux bridge as `mcp.search`:

- Streamable HTTP / JSON-RPC over public HTTPS only;
- provider configuration is local in `~/.lumena/mcp_search_providers.json`;
- credentials are referenced by environment-variable name (`auth_env`), not embedded in the config or returned in evidence;
- the broker performs `initialize -> notifications/initialized -> tools/list -> tools/call`;
- automatic tool selection requires MCP `annotations.readOnlyHint=true`;
- compatible query arguments are mapped from declared `inputSchema`;
- unknown required arguments fail closed rather than being guessed;
- external MCP output is treated as untrusted evidence, never as permission or completion proof;
- explicit user requests such as “знайди через MCP …” route to `mcp.search`; ordinary web and marketplace search keep their existing fallbacks.

Example local configuration:

```json
{
  "providers": [
    {
      "id": "jobs-provider",
      "url": "https://example-mcp-provider.example/mcp",
      "search_tool": "search_jobs",
      "auth_env": "JOBS_MCP_TOKEN"
    }
  ]
}
```

This does **not** reuse credentials from ChatGPT plugins/connectors. Lumena needs its own MCP endpoint and credentials on the phone.

Still later / separate work:

- local stdio-compatible client/provider transport;
- loopback-only Streamable HTTP exposure of Lumena's own Local MCP;
- general remote MCP execution beyond the read-only search broker;
- OAuth/account connection UX.

Any transport layer must remain outside the authority path and preserve existing confirmation/policy checks.

### Stage E.1 — robustness gate before phone acceptance

**Handoff snapshot (2026-10-09):** external `mcp.search` is implemented on
`feature/olx-pl-watch-v1`; exact-head `f4377a3e70011dcf0509beaeb0be3523b906769e`
passed Android CI **#2042 SUCCESS**. App target is `0.12.23` / versionCode `49`;
Termux Bridge target is `0.29`. This is code/CI evidence only; real external MCP phone
e2e is still pending.

Do not create another parallel MCP client. Continue this implementation and close these gates:

1. **Bounded typo normalizer for routing cues only.** Support common user mistakes such as
   `черз mcp`, `через мср`, `чирез MCP`, `мсп пошук`, `ваквнсії`, `роботі`, and
   mixed-script `МCP` / `mсp`. Do not globally autocorrect arbitrary query text, names or URLs.
2. **Authority invariant.** Fuzzy/typo recognition may select only a read-only discovery route.
   It never grants permission, changes ToolRisk, bypasses ToolGate, or turns ambiguous text into
   contact/apply/buy/send/write execution.
3. **Negation cases.** `не використовуй MCP` / `не шукай через MCP` must not produce an
   MCP preflight.
4. **MCP selection safety.** Automatic selection still requires
   `annotations.readOnlyHint=true`; absent/false is rejected even when the tool name says search.
5. **Schema safety.** Unknown required inputSchema args fail closed; the model must not invent
   tenant/account/permission values.
6. **Evidence semantics.** MCP result text remains untrusted evidence; provider/tool/protocol
   provenance must survive fallback and no failed call may become `ok=true` or completion proof.

Required mutation suite must explicitly mutate the MCP path, not only the existing recovery
kernels. At minimum kill mutants for:

- removing/permissivizing the `readOnlyHint=true` gate;
- accepting unknown required schema args;
- rerouting an explicit MCP search to ordinary web/general;
- removing MCP-routing-word cleanup from the provider query;
- dropping/changing provider provenance;
- converting MCP failure into success;
- allowing GoalContract to pass without a successful MCP tool result;
- widening `inspect.batch` to a non-read-only MCP/action path.

A mutant counts as killed only by an actual assertion failure. Compile, timeout or infrastructure
failure is not mutation proof. Prefer a dedicated `scripts/mutation_mcp_probe.py` (one mutant at
a time, restore exact original bytes) or extend the existing mutation harness with equivalent
isolation.

Phone acceptance requires: current exact-head CI green, owner-signed app + Bridge 0.29+, a real
configured MCP endpoint, one clean MCP search, at least five typo/mixed-script equivalents, one
negated case, one server tool with `readOnlyHint=false`, honest MCP-unavailable fallback/partial,
and diagnostics showing provider/tool/protocol provenance with no authority regression.

## Go / no-go gates

TinyJev should gain more autonomy only when replay/phone tests show:

- lower median latency than the large planner
- materially lower token cost
- no increase in wrong-action rate
- calibrated confidence over the intended coverage range
- no authority bypass
- no regression in Step 8.7 verification continuity

Until then, TinyJev remains an advisory bounded scorer.

## Security invariants

1. Model confidence is not permission.
2. Model prose is not evidence.
3. MCP tool discovery does not imply authorization.
4. Mutating/executable MCP calls cannot execute without the existing approval path.
5. Unknown effect never counts as success.
6. TinyJev cannot add candidates that Constitution did not admit.
7. TinyJev cannot use unverified model output as training proof.
8. Hard Constitution authority remains code-owned.
