from __future__ import annotations

import re
from pathlib import Path

import serena.java_refactor_v3.refusal_catalog  # noqa: F401 - registers live V3 codes
from serena.java_refactor_v3.models import V3_REFUSAL_REGISTRY


_REPO_ROOT = Path(__file__).resolve().parents[2]
_JAVA_ROOTS = (
    _REPO_ROOT / "java-refactor/src/main/java/io/serena/javarefactor/v3",
    _REPO_ROOT / "java-refactor/src/main/java/io/serena/javarefactor/compiler/DeepInlineIndex.java",
)
_CODE_PATTERNS = (
    re.compile(r'new\s+[A-Za-z0-9_]*Refusal\(\s*"([a-z][a-z0-9_]*)"'),
    re.compile(
        r'PlannerSupport\.refusalJson\(\s*"[^"]+"\s*,\s*(?:true|false)\s*,\s*"([a-z][a-z0-9_]*)"',
        re.DOTALL,
    ),
    re.compile(r'\brefused\(\s*"([a-z][a-z0-9_]*)"'),
)


def _java_refusal_codes() -> set[str]:
    files: list[Path] = []
    for root in _JAVA_ROOTS:
        if root.is_file():
            files.append(root)
        else:
            files.extend(root.rglob("*.java"))

    codes: set[str] = set()
    for file in files:
        text = file.read_text()
        for pattern in _CODE_PATTERNS:
            codes.update(pattern.findall(text))
    return codes


def test_v3_refusal_registry_covers_live_java_sidecar_codes() -> None:
    missing = _java_refusal_codes() - set(V3_REFUSAL_REGISTRY)
    assert not missing, "Missing V3 refusal registry codes: " + ", ".join(sorted(missing))
