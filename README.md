# Lumena Android

Android companion / local-agent client built with Kotlin + Jetpack Compose.

## Build 22: evidence-based web research

Bridge 0.18 adds `web.search` and `web.read`: source discovery, bounded readable
text, provider fallback, safe redirects, and one read-only local transport retry.
Core DNA v3 distinguishes sourced facts from hypotheses. Update both APK and Bridge.

* [Search setup and limitations](docs/web-search.md)
* [Детальний план контекстної ОС та конституції досвіду](docs/cognitive-exoskeleton-roadmap.uk.md)
* [Current context kernel](docs/context-kernel.md)
* [Current learned landscape](docs/experience-landscape.md)

## v0.7.3 local-agent architecture

`User -> Local chat -> bounded Ollama context -> one-step agent decision -> ToolGate -> localhost Termux bridge -> tool result -> local model -> next step`

The official ChatGPT companion path remains available:

`Official ChatGPT -> Accessibility snapshot -> LUMENA_TOOL -> explicit approval -> Termux bridge -> LUMENA_RESULT -> official ChatGPT`

### Local agent hardening
- shared persistent Bridge URL/token, Ollama URL and selected model
- persistent local chat and task state
- automatic Ollama model discovery
- model context is bounded before every call so long persisted chats do not make small local models progressively slower
- Ollama read timeout increased for phone inference, with one smaller-context retry after a socket timeout
- multi-step tasks can announce a short `plan[]`
- one tool call per model turn
- up to 8 workflow steps
- visible `Thinking`, plan, tool step and result trace
- repeated identical tool calls are stopped by a loop detector
- tool failures are bounded by a failure budget
- local work uses an app-level coroutine scope, so switching tabs does not cancel an active task
- current system/tool prompt is never persisted; restored history always uses the newest rules

### Tools
Read-only tools include workspace/file/Git/Ollama inspection. Mutating or executable tools such as project creation, file writes/patches, Git mutations, Python execution/tests and Ollama mutations require approval.

`file.write` and `file.patch` create backups inside the workspace before changing existing files.

## Termux bridge

```bash
bash termux/install_bridge.sh
python ~/.lumena/bridge.py
```

Default bridge: `http://127.0.0.1:8765`

Default workspace: `~/lumena-workspace`

The bridge binds only to loopback and requires a bearer token.


## Optional ChatGPT remote relay

A separate outbound-only relay can let an authenticated ChatGPT/GitHub client
invoke Lumena's public-web read tools through a dedicated private GitHub issue.
The phone keeps the Termux bridge bound to 127.0.0.1; no inbound Internet port
is opened. Remote v1 is intentionally limited to web.search, web.read,
http.get, http.json and image.search.

See [Remote relay setup and security](docs/remote-relay.md).

## Build

Pull requests run agent-core unit tests before `assembleDebug`. A debug APK artifact is uploaded only after tests and Android build succeed.


## v0.12 embedded llama.cpp

Lumena can run the local agent through either Ollama or an in-process llama.cpp backend.

Before building the embedded backend:

```bash
bash scripts/sync_llama_cpp.sh
./gradlew assembleDebug
```

The native runtime is packaged for arm64-v8a. GGUF model files are intentionally external to the APK; select a local GGUF path from the Model panel. Ollama remains an optional fallback.
