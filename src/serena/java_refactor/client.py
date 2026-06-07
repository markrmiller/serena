import collections
import json
import subprocess
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from serena.java_refactor.models import JavaRefactorInitializeParams, JavaRefactorStatus


@dataclass
class JavaRefactorClientStatus:
    """Process-level status for a Java refactoring sidecar client."""

    running: bool
    jar_path: str
    process_id: int | None


class JavaRefactorClient:
    """JSON-lines client for the Java refactoring sidecar process."""

    DEFAULT_REQUEST_TIMEOUT = 120.0

    def __init__(
        self, jar_path: Path, java_command: str = "java", max_heap: str | None = None, request_timeout: float = DEFAULT_REQUEST_TIMEOUT
    ) -> None:
        """
        :param jar_path: the executable sidecar jar path
        :param java_command: the Java executable used to launch the sidecar
        :param request_timeout: per-request timeout in seconds before the sidecar is forcibly terminated
        """
        self._jar_path = jar_path
        self._java_command = java_command
        self._max_heap = max_heap
        self._request_timeout = request_timeout
        self._process: subprocess.Popen[str] | None = None
        self._lock = threading.Lock()
        self._stderr_lines: collections.deque[str] = collections.deque(maxlen=200)
        self._stderr_thread: threading.Thread | None = None

    @property
    def jar_path(self) -> Path:
        """The resolved executable sidecar jar path."""
        return self._jar_path

    def start(self) -> None:
        """Starts the sidecar process if it is not already running."""
        if self.is_running():
            return

        if not self._jar_path.exists():
            raise FileNotFoundError(f"Java refactor sidecar jar does not exist: {self._jar_path}")

        command = [self._java_command]
        if self._max_heap:
            command.append(f"-Xmx{self._max_heap}")
        command.extend(["-jar", str(self._jar_path)])
        self._process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        self._start_stderr_drain()

    def _start_stderr_drain(self) -> None:
        """Continuously drains the sidecar's stderr so a full pipe buffer cannot deadlock the process."""
        self._stderr_lines.clear()
        stream = self._process.stderr if self._process else None
        if stream is None:
            return

        def drain() -> None:
            try:
                for line in stream:
                    self._stderr_lines.append(line.rstrip("\n"))
            except (ValueError, OSError):
                # stream closed during shutdown; nothing left to drain
                pass

        thread = threading.Thread(target=drain, name="java-refactor-stderr", daemon=True)
        self._stderr_thread = thread
        thread.start()

    def _drained_stderr(self) -> str:
        """Returns the most recent buffered sidecar stderr output."""
        return "\n".join(self._stderr_lines)

    def initialize(self, params: JavaRefactorInitializeParams) -> JavaRefactorStatus:
        """Initializes the sidecar with project context."""
        result = self._request("initialize", params.to_protocol_dict())
        return JavaRefactorStatus.from_protocol_result(result, jar_path=str(self._jar_path))

    def status(self, refresh: bool = False) -> JavaRefactorStatus:
        """Fetches the current sidecar readiness state."""
        result = self._request("status", {"refresh": refresh})
        return JavaRefactorStatus.from_protocol_result(result, jar_path=str(self._jar_path))

    def preview(self, operation: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Requests a no-write refactoring preview from the sidecar."""
        return self._request("preview", {"operation": operation, "params": params or {}})

    def apply_refactor(self, operation: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Requests a refactoring apply plan from the sidecar; Python performs workspace writes."""
        return self._request("apply", {"operation": operation, "params": params or {}})

    def validate_edit(self, overlay: dict[str, Any]) -> dict[str, Any]:
        """Validates a staged (post-edit) overlay against the project model without writing to disk.

        ``overlay`` carries ``changedFiles`` (relative path -> new full content), ``deletedFiles`` (relative paths), and
        ``renamedFiles`` (``{"oldPath", "newPath"}`` pairs). The sidecar runs javac per source set with the overlay
        substituted for on-disk source and returns ``{"ready", "errors", "warnings"}``.
        """
        return self._request("validateEdit", dict(overlay))

    def resolve_target(self, relative_path: str, line: int, column: int, name_hint: str | None = None) -> dict[str, Any]:
        """Resolves the semantic Java symbol at a one-based source position."""
        params: dict[str, Any] = {"relativePath": relative_path, "line": line, "column": column}
        if name_hint is not None:
            params["nameHint"] = name_hint
        return self._request("resolveTarget", params)

    def scan_references(
        self,
        relative_path: str,
        line: int,
        column: int,
        name_hint: str | None = None,
        kind_hint: str | None = None,
        arity_hint: int | None = None,
    ) -> dict[str, Any]:
        """Resolves the target at a one-based source position and returns matching reference spans.

        The full target identity (``name_hint``/``kind_hint``/``arity_hint``) must be forwarded so the sidecar's
        target-identity gate disambiguates against the same semantic element the rename plans against (overloads,
        same-name field/parameter collisions); resolving on ``name_hint`` alone would weaken the baseline below the
        operation it guards.
        """
        params: dict[str, Any] = {"relativePath": relative_path, "line": line, "column": column}
        if name_hint is not None:
            params["nameHint"] = name_hint
        if kind_hint is not None:
            params["kindHint"] = kind_hint
        if arity_hint is not None:
            params["arityHint"] = arity_hint
        return self._request("scanReferences", params)

    def shutdown(self, timeout: float = 2.0) -> None:
        """Requests graceful sidecar shutdown, then terminates if needed."""
        process = self._process
        if process is None:
            return

        if self.is_running():
            try:
                self._request("shutdown", {}, allow_stopped=False)
                process.wait(timeout=timeout)
            except Exception:
                process.terminate()
                try:
                    process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    process.kill()
        self._process = None

    def get_client_status(self) -> JavaRefactorClientStatus:
        """Returns process-level client state."""
        return JavaRefactorClientStatus(
            running=self.is_running(), jar_path=str(self._jar_path), process_id=self._process.pid if self._process else None
        )

    def is_running(self) -> bool:
        """Whether the sidecar process is currently running."""
        return self._process is not None and self._process.poll() is None

    def _request(self, method: str, params: dict[str, Any], allow_stopped: bool = False) -> dict[str, Any]:
        """Sends a JSON-lines request and returns its result payload."""
        if self._process is None or self._process.stdin is None or self._process.stdout is None:
            if allow_stopped:
                return {}
            raise RuntimeError("Java refactor sidecar is not running")

        if self._process.poll() is not None:
            if allow_stopped:
                return {}
            raise RuntimeError(f"Java refactor sidecar exited with code {self._process.returncode}: {self._drained_stderr()}")

        with self._lock:
            request_id = uuid.uuid4().hex
            request = {"id": request_id, "method": method, **params}
            self._process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
            self._process.stdin.flush()

            line = self._read_response_line()
            if line == "":
                raise RuntimeError(f"Java refactor sidecar closed stdout: {self._drained_stderr()}")
            response = json.loads(line)

        if response.get("id") != request_id:
            raise RuntimeError(f"Java refactor sidecar returned mismatched response id: {response}")
        return self._unwrap_response(response)

    def _read_response_line(self) -> str:
        """Reads one response line, terminating the sidecar if it does not respond within the request timeout."""
        assert self._process is not None and self._process.stdout is not None
        result: dict[str, str] = {}

        def reader() -> None:
            try:
                result["line"] = self._process.stdout.readline()  # type: ignore[union-attr]
            except (ValueError, OSError):
                result["line"] = ""

        thread = threading.Thread(target=reader, name="java-refactor-read", daemon=True)
        thread.start()
        thread.join(self._request_timeout)
        if thread.is_alive():
            self._terminate_process()
            raise TimeoutError(f"Java refactor sidecar did not respond within {self._request_timeout:g}s: {self._drained_stderr()}")
        return result.get("line", "")

    def _terminate_process(self) -> None:
        """Forcibly stops the sidecar process, used when a request times out."""
        process = self._process
        if process is None:
            return
        process.terminate()
        try:
            process.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            process.kill()
        self._process = None

    @staticmethod
    def _unwrap_response(response: dict[str, Any]) -> dict[str, Any]:
        """Validates a sidecar response envelope and returns its result payload."""
        if "error" in response:
            raise RuntimeError(str(response["error"]))
        result = response.get("result")
        if not isinstance(result, dict):
            raise RuntimeError(f"Java refactor sidecar returned invalid result: {response}")
        return result
