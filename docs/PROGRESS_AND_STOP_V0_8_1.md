# Lumena 0.8.1 — observable progress and stop

This change addresses Local workflow visibility and cancellation. The history drawer and native tool_calls provider are separate work, not implemented by this patch.

## UI
An Activity-owned AndroidViewModel is the single owner of chat/controller state and one Job. Tab switching no longer creates competing session writers. The always-visible card shows phase, elapsed time, time since a received signal, received model fragment count, and actual tool output in Details. Counts are not tokens or a percent-complete estimate. No private model reasoning is rendered or persisted. A plan is shown only after the model has actually supplied one.

## Stop semantics
- After step: waits for the in-flight model response or tool result, records it, and does not start a subsequent action.
- Immediate: cancels the coroutine AND the corresponding HTTP call; cancellation is never retried as a model failure. For an active bridge job it calls authenticated /jobs/cancel and queries the outcome before reporting confirmation.
- Completed writes/commits are not rolled back. A tool that finishes during the stop race is reported as completed, not as undone.
- Old/unreachable bridge: local orchestration is stopped but the tool's outcome remains UNKNOWN. New work is blocked until status is reconciled or the user explicitly confirms a manual check.
- Process death/reboot: stored unfinished state is marked interrupted; no mutation is automatically replayed. A ViewModel is not an Android foreground service and does not guarantee execution while Android kills/suspends the app.

## Bridge installation
Run `bash termux/install_bridge.sh` from this branch. Stop the previous bridge in its own terminal, then run `python ~/.lumena/bridge.py`. It prints v0.8.1. Token and workspace are retained. Old clients can still POST /tool.

The managed bridge reuses the existing tool implementation from bridge.py, installed as bridge_base.py. managed_bridge.py is installed as ~/.lumena/bridge.py alongside bridge_jobs.py. Jobs receive APK-generated request IDs in X-Lumena-Request-Id. Status/cancel endpoints require the bearer token. Only child process groups launched by this registry receive SIGTERM, followed by SIGKILL when needed. No kill-all/pkill of Python, Termux or Ollama is used.

Live stdout/stderr is bounded; Python output is unbuffered. IDs and cancellation tombstones are retained in memory for 30 minutes (128 jobs maximum). This is not cross-restart exactly-once execution. Deliberately detached processes/independent services are outside the registry's cancellation guarantee. Python still has the Termux application's privileges; workspace path checks are not an OS sandbox.

## Validation
CI exercises streaming assembly, missing done, output-budget exhaustion, socket cancellation, pinned context, job isolation, duplicate request protection, pre-arrival cancellation, timeouts, live stdout, SIGTERM escalation and retention capacity. Physical-phone tests are still required: tab switch during generation, Stop during model load, Stop while a Python script prints/sleeps, rotation, and reopening after process death.
