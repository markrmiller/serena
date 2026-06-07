"""Deterministic source -> jar fingerprint for the Java refactoring sidecar.

The bundled sidecar jar (``src/serena/resources/java-refactor/serena-java-refactor.jar``) is committed to the repo so it
ships in the wheel without requiring a JDK/Gradle at install time. To guarantee that committed jar can never go stale
relative to the Java source, a fingerprint of the sidecar source tree is committed alongside it. The packaging build
hook and a non-skippable test both recompute the fingerprint from source and compare it to the committed value: a Java
source change that is not accompanied by a rebuilt jar + refreshed fingerprint fails the build/test, with no toolchain
required to detect the staleness.

The fingerprint covers everything that determines the jar's bytecode: every file under ``java-refactor/src`` plus the
Gradle build scripts that drive compilation. ``build/`` outputs are excluded.
"""

import hashlib
import json
from pathlib import Path

# Fingerprint file committed next to the bundled jar. Holds a JSON record `{"source": <hash>, "jar": <hash>}` binding
# both the sidecar source tree AND the exact committed jar bytes, so neither can drift from the other unnoticed.
FINGERPRINT_RESOURCE = Path("src/serena/resources/java-refactor/serena-java-refactor.jar.sha256")
# The committed sidecar jar whose bytes the fingerprint binds.
JAR_RESOURCE = Path("src/serena/resources/java-refactor/serena-java-refactor.jar")
# Build scripts (relative to the repo root) that affect the compiled jar; included alongside the source tree.
_BUILD_FILES = (
    "java-refactor/build.gradle.kts",
    "java-refactor/settings.gradle.kts",
    "java-refactor/gradle.properties",
)
_SOURCE_DIR = "java-refactor/src"


def _iter_fingerprint_files(repo_root: Path) -> list[Path]:
    """Returns the sorted absolute paths of every file that contributes to the sidecar jar's bytecode."""
    files: list[Path] = []
    source_dir = repo_root / _SOURCE_DIR
    if source_dir.is_dir():
        files.extend(path for path in source_dir.rglob("*") if path.is_file())
    for build_file in _BUILD_FILES:
        path = repo_root / build_file
        if path.is_file():
            files.append(path)
    return sorted(files, key=lambda path: path.relative_to(repo_root).as_posix())


def compute_source_fingerprint(repo_root: str | Path) -> str:
    """Computes the deterministic SHA-256 fingerprint of the sidecar source tree under ``repo_root``.

    The digest folds in each file's repo-relative POSIX path and its raw bytes, so renames, edits, additions, and
    deletions all change the fingerprint. Independent of build toolchain or filesystem ordering.
    """
    repo_root = Path(repo_root)
    digest = hashlib.sha256()
    for path in _iter_fingerprint_files(repo_root):
        relative = path.relative_to(repo_root).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def compute_jar_digest(repo_root: str | Path) -> str | None:
    """Returns the SHA-256 of the committed sidecar jar's bytes, or None when the jar is missing."""
    path = Path(repo_root) / JAR_RESOURCE
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_committed_fingerprint(repo_root: str | Path) -> dict[str, str] | None:
    """Returns the committed ``{"source", "jar"}`` fingerprint record, or None when the fingerprint file is missing."""
    path = Path(repo_root) / FINGERPRINT_RESOURCE
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_fingerprint(repo_root: str | Path) -> dict[str, str]:
    """Writes (or refreshes) the committed fingerprint record from the current source tree and committed jar bytes."""
    repo_root = Path(repo_root)
    record = {"source": compute_source_fingerprint(repo_root), "jar": compute_jar_digest(repo_root) or ""}
    path = repo_root / FINGERPRINT_RESOURCE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def _repo_root_from_here() -> Path:
    # src/serena/java_refactor/_sidecar_fingerprint.py -> repo root is three parents up from src/serena/java_refactor.
    return Path(__file__).resolve().parents[3]


if __name__ == "__main__":
    root = _repo_root_from_here()
    written = write_fingerprint(root)
    print(f"wrote sidecar fingerprint: source={written['source']} jar={written['jar']}")
