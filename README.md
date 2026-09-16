# Lumena Android

Android co-pilot / local-agent client built with Kotlin + Jetpack Compose.

## v0.4 architecture

`User -> dark workflow chat -> local Ollama model -> ToolGate -> localhost bridge -> Termux/Python/Git -> result -> local model -> reply`

The existing screen-agent path remains available:

`Screen -> Accessibility UI tree -> ScreenSnapshot -> ActionGate -> Executor -> Verification`

### Included in v0.4
- dark Material 3 interface
- chat-first workflow shell with separate **Chat** and **Tools** tabs
- loopback-only Ollama provider at `127.0.0.1:11434`
- local model discovery through `/api/tags`
- multi-step local workflow runner (up to 4 tool/model steps per turn)
- JSON tool protocol for local models
- explicit confirmation for executable actions
- AccessibilityService-based visible UI reader
- Termux bridge on `127.0.0.1:8765`
- bearer-token authentication
- workspace path confinement
- read-only tools: `health`, `file.read`, `git.status`, `git.diff`, `git.log`, `ollama.status`
- confirmed executable tools: `python.run`, `ollama.start`, `ollama.pull`
- GitHub Actions APK build

## Termux bridge setup

Clone this repository in Termux, then run:

```bash
bash termux/install_bridge.sh
python ~/.lumena/bridge.py
```

The bridge prints a token on startup. Paste it into **Chat -> Local model -> Termux tools** or into the advanced **Tools** tab.

Default workspace:

```text
~/lumena-workspace
```

All file/Git/Python paths are resolved inside this workspace. The bridge intentionally binds only to `127.0.0.1`, not `0.0.0.0`, so other devices on the same Wi-Fi cannot connect to it.

## Ollama sidecar

Lumena integrates Ollama as a local sidecar rather than exposing it over Wi-Fi. The Android client accepts only loopback URLs (`localhost`, `127.0.0.1`, `::1`).

If an `ollama` executable is already available in the Termux environment:

```bash
bash termux/ollama_sidecar.sh status
bash termux/ollama_sidecar.sh serve
bash termux/ollama_sidecar.sh pull MODEL_NAME
```

You can also let the local model request `ollama.status`, `ollama.start`, or `ollama.pull`. Starting the service or downloading a model requires user confirmation in the app.

> The APK does **not** bundle the official Ollama binary. Ollama is not a normal Android library module; keeping it as a same-phone loopback sidecar is currently the more reliable integration path. The UI and agent workflow treat it as one local system.

## Chat workflow

The local model receives a system prompt with the allow-listed tools. When it needs a tool it must emit exactly one JSON object, for example:

```json
{"tool":"git.status","args":{"cwd":"my-project"},"reason":"Check the repository before editing"}
```

Read-only requests can execute automatically. Python execution, Ollama start, and model pulls always require an explicit confirmation. Tool output is returned to the local model so it can continue the same turn.

## Safety and control

Lumena is designed as a co-pilot, not a hidden automation layer. It does not attempt to bypass Android security, banking protections, DRM, app authentication, or server-side model safeguards. Unknown tools are blocked. Ollama and Termux clients reject non-loopback hosts.

## Build

Open in Android Studio with JDK 17 and Android SDK 35, or use the `Android CI` workflow. Pull requests targeting `main` build a debug APK artifact automatically.
