# Lumena Android

Android co-pilot / local-agent client built with Kotlin + Jetpack Compose.

## Current architecture

`Official ChatGPT Companion -> approved local tools -> Termux/Python/Git`

`Local Ollama model -> guarded agent workflow -> Termux/Python/Git`

`Accessibility UI tree -> controlled Android actions`

## v0.7.2

- persistent app-wide Bridge URL/token, Ollama URL and selected model
- automatic Ollama model discovery whenever Local opens
- persistent Local chat across tab switches and app restarts
- persistent model history used to continue the same conversation
- persistent current TaskState
- pending tool approval survives tab switching/recreation
- bounded Local session storage so weak local models are not fed unbounded history
- `New` button clears only the Local conversation/task state, not connection settings
- deterministic Agent Core: parser, ToolRegistry, LoopDetector, FailureBudget, ContextBuilder
- bridge v0.7 tools: `file.patch`, `python.syntax_check`, `python.tests`
- backups before `file.write` and `file.patch`
- unit tests run before APK build in CI

## Termux bridge setup

```bash
bash termux/install_bridge.sh
python ~/.lumena/bridge.py
```

Default bridge:

```text
http://127.0.0.1:8765
```

Default workspace:

```text
~/lumena-workspace
```

The bridge binds only to loopback and uses a bearer token. Paths are confined to the workspace.

## Safety model

The model proposes; the application validates, executes and verifies.

Unknown tools are blocked. External ChatGPT-originated tool requests require explicit user approval. Mutating/executable local tools require confirmation. There is no unrestricted shell tool.

## Build

Open with JDK 17 and Android SDK 35 or use GitHub Actions. CI runs agent-core unit tests before `assembleDebug`.
