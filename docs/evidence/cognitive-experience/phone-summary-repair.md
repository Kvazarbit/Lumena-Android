# Phone finding: execution evidence contradicted by the final report

The owner supplied a v0.12.5 diagnostic from Android 14 on 2026-09-26.
The task kernel recorded successful `system.time`, `workspace.list`, and
`web.read` calls. The final answer nevertheless denied the first two calls,
used the web fetch timestamp as the time result, and was accepted as DONE.
One protocol repair had occurred. This demonstrates a reporting/controller
failure; it does not demonstrate a bridge execution failure.

## Change

- Add a bounded current-task receipt to each model request, after historical
  messages, so context compaction retains evidence for earlier calls.
- Include those receipts in protocol repair and explicitly retain earlier
  execution facts when the latest malformed model output was not executed.
- Detect explicit named-tool invocation denials in Done, Reply, and Partial.
  Allow one report correction; repeated contradiction returns PARTIAL with
  application-owned evidence. The correction does not reset tool or protocol
  budgets and does not itself execute tools.
- Keep this reporting failure separate from failed-tool recovery memory:
  the recorded tools succeeded, so this is not a negative tool outcome.

The detector covers bounded explicit denial patterns in Ukrainian, Russian,
English, and Polish. It is not a general semantic verifier. Receipts contain
bounded excerpts rather than complete outputs; larger tasks retain the full
task evidence separately, and only up to eight tool names enter this recap.

## Validation

- Three new tests failed on the previous implementation, reproducing accepted
  false Done, accepted public-web Reply, and missing prior repair evidence.
- Updated controller subset: 84 JUnit tests passed using Kotlin 1.9.23/JDK 17.
  This local run uses API 35 compilation stubs; it does not test Android UI.
- A WorkflowRunner regression applies the real Ollama context compactor at a
  2,000-character request budget. It requires all three receipts and result
  excerpts to survive and a corrected report to finish without tool replay.
  This integration test is included in the full Android CI suite.
- A corrected APK still needs the same read-only test on the phone. No remote
  device execution or device-level fix verification is claimed here.
