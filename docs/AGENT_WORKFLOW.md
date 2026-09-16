# Lumena v0.5 agent workflow

Lumena has two interchangeable model providers:

- Embedded `llama.cpp` for imported GGUF files.
- Loopback Ollama for models already managed by Ollama.

Both providers use the same agent loop:

`user -> model -> ToolGate -> Termux bridge -> result -> model -> answer`

Read-only tools can run without an approval dialog. Any operation that writes files, creates a project, changes Git state, runs Python, starts Ollama, or downloads a model is confirmed by the user first.

## Typical project turn

1. `workspace.list` to inspect existing projects.
2. `project.create` when a new workspace is needed.
3. `file.read` before changing an existing file.
4. `file.write` with a complete coherent replacement/new file.
5. `python.run` to test or analyze.
6. `git.diff` to inspect the change.
7. `git.add` and `git.commit` after approval.

There is intentionally no unrestricted `shell.run` tool.
