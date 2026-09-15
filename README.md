# Lumena Android

Experimental Android co-pilot / computer-use client built with Kotlin + Jetpack Compose.

## v0.3 architecture

`User -> Planner -> ToolGate -> localhost bridge -> Termux/Python/Git -> result -> UI`

The existing screen-agent path remains available:

`Screen -> Accessibility UI tree -> ScreenSnapshot -> ActionGate -> Executor -> Verification`

### Included
- Jetpack Compose shell
- AccessibilityService-based visible UI reader
- structured screen snapshot
- basic click / text-input executor
- explicit action gate
- local agent panel
- local-only Termux bridge on `127.0.0.1:8765`
- bearer-token authentication
- workspace path confinement
- read-only tools: `health`, `file.read`, `git.status`, `git.diff`, `git.log`
- confirmed executable tool: `python.run`
- GitHub Actions APK build

## Termux setup

Clone this repository in Termux, then run:

```bash
bash termux/install_bridge.sh
python ~/.lumena/bridge.py
```

The bridge prints a token on startup. Paste it into the **Local Agent** section in Lumena Android.

Default workspace:

```text
~/lumena-workspace
```

Examples accepted by the v0.3 rule planner:

```text
health
git status my-project
git diff my-project
git log my-project
read notes.txt
python scripts/test.py --fast
```

All paths are resolved inside the configured workspace. The bridge intentionally binds only to `127.0.0.1`, not `0.0.0.0`, so other devices on the same Wi-Fi cannot connect to it.

### Safety and control
Lumena is designed as a co-pilot, not a hidden automation layer. The app does not attempt to bypass Android security, banking protections, DRM, app authentication, or server-side model safeguards. Unknown tools are blocked. Read-only local tools can run directly; code execution requires an explicit confirmation in the app.

## Next agent layer

v0.3 deliberately uses a deterministic rule planner so the execution path can be tested independently from a language model. The next layer is a `PlannerEngine` backed by a small local GGUF model through llama.cpp/JNI; it will emit the same `ToolRequest` schema and therefore reuse the existing ToolGate and Termux bridge.

## Build
Open in Android Studio with JDK 17 and Android SDK 35, or use the `Android CI` workflow. Pull requests build a debug APK artifact automatically.
