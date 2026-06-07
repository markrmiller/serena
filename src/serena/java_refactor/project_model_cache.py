"""
Java project-model caching.

The authoritative project-model cache lives inside the sidecar (``ProjectModelCache`` in the Java
``io.serena.javarefactor`` package), keyed by the configuration plus the path/mtime/size of every invalidation file
and discovered Java source file. That is where discovery and the (expensive) javac validation pass run, so caching
there avoids re-validating on every ``status``/``preview``/``apply`` call while remaining invalidation-safe.

This Python-side structure remains only as a lightweight, optional holder for compiler options that other Serena
components may attach to a project; it does not cache semantic analysis.
"""

from dataclasses import dataclass, field


@dataclass
class JavaProjectModelCache:
    """Optional Python-side holder for per-source-root compiler options (not a semantic-analysis cache)."""

    compiler_options_by_source_root: dict[str, list[str]] = field(default_factory=dict)

    def clear(self) -> None:
        """Clears cached compiler-option metadata."""
        self.compiler_options_by_source_root.clear()
