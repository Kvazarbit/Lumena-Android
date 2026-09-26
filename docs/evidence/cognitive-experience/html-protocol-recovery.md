# HTML creation and short follow-ups: phone regression

The owner supplied an Android diagnostic and screenshots on 2026-09-26.
The original request was to create an HTML 3D aquarium simulator. Subsequent
messages included a failure discussion, "так", and "html+js". The final task
goal was only "html+js", with zero observed tools and a protocol syntax failure.
The diagnostic's seven protocol-repair markers are session-wide, not proof of
seven retries within this final task. The raw failed model output and Ollama
stop reason were not included, so output truncation is a hypothesis for this
phone run, not an established cause.

Two reproducible defects were confirmed on ece99ed:

- The original Ukrainian HTML creation request routed to GENERAL rather than
  CODE_WORK, skipping the normal workspace preflight.
- Language-labelled code fences such as HTML and JavaScript were treated as
  malformed protocol envelopes instead of non-executable text.

The baseline regression ran four tests with two assertion failures. The change
adds web implementation terms to code routing and distinguishes labelled source
fences from JSON protocol wrappers. Incomplete JSON tool calls remain rejected;
source code in a chat response does not prove files were created.

A bounded code-goal anchor preserves an explicit implementation request across
recognised failure discussions, confirmation of an implementation offer, and
short technology selections. It is persisted and can be reconstructed from
older visible chats. A new topic clears it; a new code request replaces it.
This is a deliberately narrow resolver, not general natural-language inference.
New tasks still start without previous execution state or approvals. Editing
an old message clears later code-goal state as well as other future context.

Ollama streaming, non-streaming chat and generate fallback now check `done`
and `done_reason`. Output-limit stops and streams missing their final completion
record return MODEL_OUTPUT_INCOMPLETE rather than passing truncated content to
the controller. Transport does not repeat these calls itself. The existing
bounded protocol recovery asks for one shorter complete JSON call; it does not
concatenate fragments or execute partial code. Code-task guidance recommends
small files or patches with known unique anchors and verification afterwards.

The official API exposes `done_reason`:
https://docs.ollama.com/api/chat
No forced structured-output mode was added to cloud requests; Ollama documents
that its Cloud does not currently support structured outputs:
https://docs.ollama.com/capabilities/structured-outputs

Validation includes controller/router/normalizer regressions, goal restoration,
HTTP fixtures for output limits across all fallback paths, and a WorkflowRunner
test requiring normal write approval after truncated-output recovery. The
fixtures do not run Gemma or create an aquarium on the phone. That end-to-end
result remains pending a corrected-build device test.
