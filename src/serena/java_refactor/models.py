import json
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass
class JavaRefactorInitializeParams:
    """Initialization payload for the Java refactoring sidecar.

    Serializes the designed initialize contract (refactor-feature-plan.md §Initialize): a single nested ``params``
    object carrying ``projectRoot``, optional ``encoding``/``javaHome``/``ignoredPatterns``, a structured ``config``
    object (build-tool + analysis settings), and ``projectDataDir``. The legacy flat ``configuration`` JSON string is
    retained for backward compatibility with sidecars that predate the structured ``config`` object; the sidecar merges
    the structured object over the parsed legacy string, then overlays the top-level ``encoding``/``ignoredPatterns``.

    The sidecar also accepts the documented nested V2 schema under ``config["java_refactor"]["v2"]`` (or
    ``javaRefactor.v2``). Those keys are normalized into the effective configuration for session limits, operation
    defaults, access widening, hierarchy settings, extract-method/interface constraints, encapsulate-field and
    inline-method options, generated/Lombok policy, diagnostics, imports, and style.

    The new fields default to ``None``/unset so callers that only pass ``project_root`` (and optionally the legacy
    ``configuration`` string) produce exactly the prior payload shape — none of the new keys are emitted unless set.
    """

    project_root: str
    configuration: str = "default"
    # Structured analysis/build configuration object (the designed `config`). When present it is overlaid over the
    # parsed legacy `configuration` string by the sidecar; None omits the key entirely.
    config: dict[str, Any] | None = None
    # Source file encoding (Java charset name); when set, overlaid over `config.encoding` by the sidecar. None omits it.
    encoding: str | None = None
    # JDK home the sidecar should report/associate with this project model. None omits it (the launching client already
    # selects the `java` executable via java_home; this records the chosen home for status/diagnostics).
    java_home: str | None = None
    # Directory names pruned during source discovery (replaces the sidecar's hard-coded exclusion list). None omits the
    # key, so the sidecar keeps its built-in default set; an explicit (possibly empty) list overrides it.
    ignored_patterns: list[str] | None = None
    # Serena's per-project data directory; the sidecar persists its project-model cache here so a restarted sidecar can
    # reuse a prior validation. None disables persistent caching (in-process cache only).
    project_data_dir: str | None = None

    def to_protocol_dict(self) -> dict[str, Any]:
        """Returns the protocol-compatible representation as a nested ``params`` object."""
        params: dict[str, Any] = {"projectRoot": self.project_root, "configuration": self.configuration}
        if self.config is not None:
            params["config"] = dict(self.config)
        if self.encoding is not None:
            params["encoding"] = self.encoding
        if self.java_home is not None:
            params["javaHome"] = self.java_home
        if self.ignored_patterns is not None:
            params["ignoredPatterns"] = list(self.ignored_patterns)
        if self.project_data_dir is not None:
            params["projectDataDir"] = self.project_data_dir
        return {"params": params}


@dataclass
class JavaRefactorStatus:
    """Readiness snapshot returned by the Java refactoring sidecar."""

    ready: bool
    # Designed top-level readiness contract (refactor-feature-plan.md §Status). Surfaced as explicit top-level fields
    # so clients get the compact status payload; the detailed nested project_model remains available below.
    status: str | None = None
    jdk: str | None = None
    # The JDK home the sidecar was initialized with (initialize contract's javaHome); echoed back so callers can confirm
    # the sidecar is analyzing against the intended JDK. None when the contract supplied no javaHome.
    java_home: str | None = None
    build_tool: str | None = None
    source_sets: int = 0
    java_files: int = 0
    classpath_entries: int = 0
    last_model_refresh_ms: int | None = None
    semantic_errors: int = 0
    protocol_version: str | None = None
    project_root: str | None = None
    configuration: str | None = None
    started_at: str | None = None
    refreshed: bool = False
    errors: list[str] = field(default_factory=list)
    jar_path: str | None = None
    project_model: dict[str, Any] | None = None
    capabilities: dict[str, Any] = field(default_factory=dict)
    live_sessions: int = 0
    # Where the validated project model came from: "memory" (in-process), "persistent" (Serena project-data cache), or
    # "fresh" (re-validated). None when no model was discovered.
    model_cache_source: str | None = None

    @classmethod
    def from_protocol_result(cls, result: dict[str, Any], jar_path: str | None = None) -> "JavaRefactorStatus":
        """Builds a status snapshot from a sidecar protocol result.

        The designed top-level status fields are read directly from the sidecar when present, falling back to the
        nested ``projectModel`` so the contract is honored even against an older sidecar that only emits the model.
        """
        project_model = result.get("projectModel") if isinstance(result.get("projectModel"), dict) else None
        model = project_model or {}
        errors = list(model.get("errors", []))
        ready = bool(result.get("ready", False))

        def _from_model_or_top(top_key: str, model_key: str, default: Any) -> Any:
            if top_key in result and result[top_key] is not None:
                return result[top_key]
            return model.get(model_key, default)

        status = result.get("status")
        if status is None:
            status = "ready" if ready else ("unavailable" if project_model is None else "error")

        return cls(
            ready=ready,
            status=status,
            jdk=result.get("jdk"),
            java_home=result.get("javaHome"),
            build_tool=_from_model_or_top("buildTool", "discoveryKind", None),
            source_sets=int(_from_model_or_top("sourceSets", "sourceSetCount", 0) or 0),
            java_files=int(_from_model_or_top("javaFiles", "javaFileCount", 0) or 0),
            classpath_entries=int(result["classpathEntries"])
            if result.get("classpathEntries") is not None
            else len(model.get("classpath", [])),
            last_model_refresh_ms=(result["lastModelRefreshMs"] if result.get("lastModelRefreshMs") not in (None, -1) else None),
            semantic_errors=int(result["semanticErrors"]) if result.get("semanticErrors") is not None else len(errors),
            protocol_version=result.get("protocolVersion"),
            project_root=result.get("projectRoot"),
            configuration=result.get("configuration"),
            started_at=result.get("startedAt"),
            refreshed=bool(result.get("refreshed", False)),
            errors=errors,
            jar_path=jar_path,
            project_model=project_model,
            capabilities=dict(result.get("capabilities", {})) if isinstance(result.get("capabilities"), dict) else {},
            live_sessions=int(result.get("liveSessions", 0) or 0),
            model_cache_source=result.get("modelCacheSource"),
        )

    @classmethod
    def unavailable(cls, error: str, jar_path: str | None = None) -> "JavaRefactorStatus":
        """Builds an unavailable status with an explanatory error."""
        return cls(ready=False, status="unavailable", errors=[error], jar_path=jar_path)

    def to_json(self) -> str:
        """Serializes the status snapshot for tool output.

        The designed compact readiness contract (refactor-feature-plan.md §Status) is surfaced under its exact
        camelCase key names; the detailed snake_case snapshot fields are retained for existing consumers.
        """
        payload = asdict(self)
        payload.update(
            {
                "status": self.status,
                "jdk": self.jdk,
                "buildTool": self.build_tool,
                "sourceSets": self.source_sets,
                "javaFiles": self.java_files,
                "classpathEntries": self.classpath_entries,
                "lastModelRefreshMs": self.last_model_refresh_ms,
                "semanticErrors": self.semantic_errors,
            }
        )
        return json.dumps(payload, ensure_ascii=False)
