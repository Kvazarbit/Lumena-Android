# Cloud output budget follow-up (2026-09-26)

The owner installed c5ed4ba and supplied task 6b849bf7-2194-48a9-a77d-c1ac1359cab1:
`MODEL_OUTPUT_INCOMPLETE done_reason=length generated_tokens=640`, repeated;
zero tool results, zero kernel observations, FAILED step 0/4.
Unlike the earlier syntax-only report, this confirms output truncation.

The UI passed the phone hardware profile to OllamaClient even for `gemma4:31b-cloud`.
The >=8 GB local profile caps output at 640, and protocol retries reused that budget.
Cloud aliases now use bounded application budgets (16384 context, 4096 output),
independent of phone RAM. One length-triggered regeneration expands output to 8192.
The broken assistant fragment is not included in the retry. A second length stop
is terminal, including in WorkflowRunner; it cannot restart identical protocol retries.
Missing final completion, authentication and transport failures do not trigger expansion.
Local-model hardware limits remain unchanged. Input-context recovery reduces input
without undoing the cloud output budget. Expanded output persists for that client/model.

A leading creation imperative with one substituted letter and an explicit code subject
now routes as code work; the reported `стаори ... html` no longer loses the code preflight.
Routing does not bypass existing write approvals.

HTTP fixtures check outbound budgets, fragment exclusion, persistent expansion,
terminal exhaustion, missing completion and streaming/chat/generate fallback paths.
Policy fixtures check low-memory cloud independence, input reserves and local limits.
Workflow fixture checks exhausted transport recovery makes one model call and no tools.
Full Android CI and phone aquarium creation/rendering are separate verification gates.

Build base version is 0.12.6 (code 30); CI adds its run number to versionName and APK
artifact names so the installed diagnostic identifies the build unambiguously.
