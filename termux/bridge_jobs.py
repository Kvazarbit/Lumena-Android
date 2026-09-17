"""Bounded same-process registry. Only process groups launched here may be signalled."""
from __future__ import annotations
import hashlib
import json
import os
import re
import selectors
import signal
import subprocess
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable

_ID = re.compile(r"^[A-Za-z0-9_-]{16,80}$")
_LIMIT = 128 * 1024

def failure(message: str, stdout: str = "", stderr: str = "", code=None):
    return {"ok": False, "exitCode": code, "stdout": stdout, "stderr": stderr, "error": message}

@dataclass
class Job:
    fingerprint: str = ""
    status: str = "pending"
    created: float = field(default_factory=time.monotonic)
    updated: float = field(default_factory=time.monotonic)
    cancelled: threading.Event = field(default_factory=threading.Event)
    stdout: bytearray = field(default_factory=bytearray)
    stderr: bytearray = field(default_factory=bytearray)
    result: dict[str, Any] | None = None

class JobRegistry:
    def __init__(self, capacity: int = 128, ttl: float = 1800):
        self.capacity, self.ttl = capacity, ttl
        self.lock = threading.RLock()
        self.jobs: dict[str, Job] = {}
        self.local = threading.local()

    def _id(self, request_id: str):
        if not isinstance(request_id, str) or not _ID.fullmatch(request_id):
            raise ValueError("Invalid request ID")

    def _room(self):
        now = time.monotonic()
        for key, job in list(self.jobs.items()):
            if job.status != "running" and now - job.updated > self.ttl:
                del self.jobs[key]
        if len(self.jobs) >= self.capacity:
            raise ValueError("Job registry full; wait for retained receipts to expire")

    def status(self, request_id: str):
        self._id(request_id)
        with self.lock:
            job = self.jobs.get(request_id)
            if job is None:
                return {"status": "unknown", "requestId": request_id}
            return {"requestId": request_id, "status": job.status,
                "cancelRequested": job.cancelled.is_set(),
                "stdout": bytes(job.stdout[-4096:]).decode("utf-8", "replace"),
                "stderr": bytes(job.stderr[-4096:]).decode("utf-8", "replace"), "result": job.result}

    def cancel(self, request_id: str):
        self._id(request_id)
        with self.lock:
            job = self.jobs.get(request_id)
            if job is None:
                self._room()
                job = Job(status="cancelled")
                job.result = failure("Cancelled before execution")
                self.jobs[request_id] = job
            if job.status not in {"completed", "failed"}:
                job.cancelled.set()
            job.updated = time.monotonic()
        return self.status(request_id)

    def execute(self, request_id: str, payload: dict, action: Callable[[], dict]):
        self._id(request_id)
        fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with self.lock:
            job = self.jobs.get(request_id)
            if job:
                if job.fingerprint and job.fingerprint != fingerprint:
                    return failure("Request ID was reused with different arguments")
                if job.result is not None:
                    return dict(job.result)
                return failure("Request already running; query its status, do not resubmit")
            self._room()
            job = Job(fingerprint=fingerprint, status="running")
            self.jobs[request_id] = job
        self.local.job = job
        try:
            result = failure("Cancelled before execution") if job.cancelled.is_set() else action()
        except Exception as exc:
            result = failure(f"{type(exc).__name__}: {exc}")
        finally:
            self.local.job = None
        with self.lock:
            job.result = result
            # A write may have committed before cancel arrived. Do not invent a rollback.
            job.status = "completed" if result.get("ok") else ("cancelled" if job.cancelled.is_set() else "failed")
            job.updated = time.monotonic()
        return dict(result)

    def run_process(self, argv, cwd, timeout=120, *, env=None):
        job = getattr(self.local, "job", None) or Job(status="running")
        if job.cancelled.is_set():
            return failure("Cancelled before process start")
        child_env = dict(os.environ if env is None else env)
        child_env["PYTHONUNBUFFERED"] = "1"
        deadline = time.monotonic() + max(1, min(int(timeout), 1800))
        proc = subprocess.Popen(argv, cwd=str(cwd), env=child_env,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True, shell=False, bufsize=0)
        selector = selectors.DefaultSelector()
        for stream, name in ((proc.stdout, "stdout"), (proc.stderr, "stderr")):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, name)
        stop_reason = None
        term_at = None
        killed = False
        truncated = False
        def signal_group(sig):
            try: os.killpg(proc.pid, sig)
            except ProcessLookupError: pass
        try:
            while selector.get_map() or proc.poll() is None:
                now = time.monotonic()
                if term_at is None and (job.cancelled.is_set() or now >= deadline):
                    stop_reason = "Cancelled" if job.cancelled.is_set() else "Command timed out"
                    signal_group(signal.SIGTERM)
                    term_at = now
                if term_at is not None and not killed and now - term_at >= 2:
                    signal_group(signal.SIGKILL)
                    killed = True
                if term_at is not None and now - term_at >= 4: break
                for key, _ in selector.select(0.1):
                    data = os.read(key.fileobj.fileno(), 8192)
                    if not data:
                        selector.unregister(key.fileobj); key.fileobj.close(); continue
                    with self.lock:
                        target = getattr(job, key.data)
                        target.extend(data)
                        if len(target) > _LIMIT:
                            del target[:-_LIMIT]; truncated = True
                        job.updated = now
            if proc.poll() is None or term_at is not None:
                signal_group(signal.SIGKILL)
            code = proc.wait(timeout=2)
        finally:
            selector.close()
            for stream in (proc.stdout, proc.stderr):
                if stream and not stream.closed: stream.close()
            if proc.poll() is None:
                signal_group(signal.SIGKILL); proc.wait(timeout=2)
        with self.lock:
            stdout = bytes(job.stdout).decode("utf-8", "replace")
            stderr = bytes(job.stderr).decode("utf-8", "replace")
        if truncated: stdout = "[output tail; earlier bytes discarded]\n" + stdout
        return {"ok": code == 0 and stop_reason is None, "exitCode": code,
                "stdout": stdout, "stderr": stderr, "error": stop_reason}
