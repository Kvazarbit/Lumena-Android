# Lumena Android

Android co-pilot / local-agent client built with Kotlin + Jetpack Compose.

## v0.5 architecture

`User -> dark workflow chat -> Embedded llama.cpp GGUF OR Ollama -> ToolGate -> localhost bridge -> Termux/Python/Git -> result -> model -> reply`

The screen-agent path remains available:

`Screen -> Accessibility UI tree -> ScreenSnapshot -> ActionGate -> Executor -> Verification`

### Included in v0.5
- dark Material 3 chat-first interface
- separate **Chat** and **Tools** tabs
- embedded `llama.cpp` Android/JNI runtime from the pinned upstream submodule
- app-private GGUF model store and automatic scan on launch
- Android document picker to import `.gguf` models into Lumena
- Ollama fallback on `127.0.0.1:11434` with automatic `/api/tags` discovery
- a model-agnostic multi-step workflow runner
- visible workflow trace (`Running tool…`, success/failure)
- collaborative project tools for files, Git and Python
- confirmation gate for every workspace mutation/execution
- Termux bridge on `127.0.0.1:8765`
- bearer-token authentication and workspace confinement
- AccessibilityService-based visible UI reader
- GitHub Actions APK build with NDK/CMake

## Model engines

### Embedded llama.cpp

Lumena bundles the native inference engine, **not a model**. Import a GGUF from the model setup card in Chat. Imported models are copied into Lumena's private `files/models` directory and are scanned automatically on every launch.

This path does not require an Ollama server once a GGUF has been imported.

### Ollama fallback

Existing Ollama models are detected automatically from the loopback-only API. Lumena accepts only `localhost`, `127.0.0.1`, or `::1` for this provider.

If Ollama is already installed in Termux:

```bash
bash termux/ollama_sidecar.sh status
bash termux/ollama_sidecar.sh serve
```

## Termux workspace setup

On the phone:

```bash
cd ~/Lumena-Android
git fetch origin
git checkout feature/embedded-llama-workspace-v0.5
bash termux/install_bridge.sh
python ~/.lumena/bridge.py
```

The bridge prints a token. Paste it into **Chat -> Workspace tools -> Bridge token**.

Default workspace:

```text
~/lumena-workspace
```

All file/Git/Python operations are confined to that directory. The bridge binds only to `127.0.0.1`, never `0.0.0.0`.

## Collaborative project tools

Read-only tools may execute automatically:

```text
health
workspace.list
file.read
git.status
git.diff
git.log
ollama.status
```

Mutating/executable tools always require explicit user approval in the app:

```text
project.create
dir.create
file.write
git.add
git.commit
python.run
ollama.start
ollama.pull
```

Typical project workflow:

`discuss idea -> inspect workspace -> create project -> write files -> run Python/tests -> inspect diff -> git add -> git commit`

The model never receives unrestricted shell access. The bridge exposes only the allow-listed operations above.

## Embedded llama.cpp source

`vendor/llama.cpp` is pinned as a Git submodule to a specific upstream commit. To build locally:

```bash
git clone --recurse-submodules https://github.com/Kvazarbit/Lumena-Android.git
cd Lumena-Android
git checkout feature/embedded-llama-workspace-v0.5
git submodule update --init --recursive
```

The build uses the upstream `examples/llama.android/lib` JNI module.

## Build

Requirements used by CI:

- JDK 17
- Gradle 8.14.3
- Android SDK 36
- Android NDK 29.0.13113456
- CMake 3.31.6

Pull requests targeting `main` build a debug APK artifact automatically.

## Safety and control

Lumena is designed as a visible co-pilot, not a hidden automation layer. Unknown tools are blocked. Workspace-changing actions require approval. The local bridge and Ollama integration are loopback-only, so other devices on the same Wi-Fi cannot call them directly.
