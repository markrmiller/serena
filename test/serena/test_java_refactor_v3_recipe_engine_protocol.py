"""Live-sidecar coverage for the V3 ``recipes.*`` protocol (refactor-feature-plan-V3.md §14).

These boot the real Java sidecar jar and drive the semantic API-migration recipe engine end to end via
:class:`~serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient`. They prove the engine matches against
javac-resolved symbols (not textual guesses), classifies §14.3 risk, emits report-only findings for migrations with no
semantics-preserving replacement, applies only ``safe`` matches by default behind the sidecar's before/after javac
validator (``diagnosticDeltaValidated: true``), and surfaces the §14 refusals with their canonical ``code``.

External migration targets (JUnit/Guava/Jakarta) are not on the test fixtures' classpath, so the apply happy-path uses a
project-local custom recipe and the built-in scans target JDK-only types (``java.lang.Thread``, ``java.util.Date``).

Capabilities exercised:
    §14.4 test_recipe_scan_thread_removal_builtin     — built-in scan flags removed Thread methods (report-only)
    §14.4 test_recipe_scan_date_calendar_builtin      — built-in scan flags java.util.Date as report-only candidate
    §14.1 test_recipe_apply_custom_safe_static_call   — a safe static-call recipe applies, javac-validated
    §14   test_recipe_scan_refuses_unknown_id         — unknown built-in id is refused (recipe_not_found)
    §14.1 test_recipe_apply_change_method_signature   — changeMethodSignature rewrites decl+call sites, javac-validated
    §14   test_recipe_change_signature_refusal_passthrough — the op's own refusal (public-API) surfaces truthfully
    §14   test_recipe_apply_refuses_no_matches        — a resolvable-but-unused recipe is refused (recipe_no_matches)
    §14   test_recipe_apply_refuses_unresolved_symbol — an absent target is refused (recipe_unresolved_symbol)
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.recipe_engine_client import RecipeEngineClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _recipes(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[RecipeEngineClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield RecipeEngineClient(client)
    finally:
        client.shutdown()


# ── §14.4 built-in scans (report-only, JDK-only targets resolve on every classpath) ───────────────────────────────


def test_recipe_scan_thread_removal_builtin(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Legacy.java",
        "package com.acme;\n"
        "public class Legacy {\n"
        "    void halt() {\n"
        "        Thread t = Thread.currentThread();\n"
        "        t.stop();\n"
        "    }\n"
        "}\n",
    )
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.scan_migration_opportunities(recipe_id="thread-stop-suspend-destroy-removal")

    assert result.get("accepted") is True, result
    findings = result["findings"]
    assert findings, result
    stop = next(f for f in findings if f["oldText"].endswith("stop()") or "stop" in f["detail"])
    assert stop["risk"] == "needs_review", stop
    assert stop["newText"] is None, stop  # report-only: no replacement emitted


def test_recipe_scan_date_calendar_builtin(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Clock.java",
        "package com.acme;\n"
        "public class Clock {\n"
        "    Object now() {\n"
        "        return new java.util.Date();\n"
        "    }\n"
        "}\n",
    )
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.scan_migration_opportunities(recipe_id="date-calendar-to-java-time-basic")

    assert result.get("accepted") is True, result
    findings = result["findings"]
    assert findings, result
    assert all(f["risk"] == "needs_review" for f in findings), result
    assert all(f["newText"] is None for f in findings), result  # report-only modernization candidates


# ── §14.1 custom recipe apply happy-path (project-local, safe static-call swap) ───────────────────────────────────


def test_recipe_apply_custom_safe_static_call(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Util.java",
        "package com.acme;\n"
        "public class Util {\n"
        "    public static int legacy(int x) { return x; }\n"
        "    public static int modern(int x) { return x; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Caller.java",
        "package com.acme;\n"
        "public class Caller {\n"
        "    int run() {\n"
        "        return Util.legacy(5);\n"
        "    }\n"
        "}\n",
    )
    recipe = {
        "id": "acme-legacy-to-modern",
        "description": "Swap the project-local Util.legacy(int) call for Util.modern(int).",
        "rules": [
            {
                "kind": "replaceStaticMethodCall",
                "owner": "com.acme.Util",
                "name": "legacy",
                "replacement": "com.acme.Util.modern(${arg0})",
                "risk": "safe",
            }
        ],
    }
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    changes = result["workspaceEdit"]["changes"]
    assert any(c["path"].endswith("Caller.java") for c in changes), result


# ── §14.3 apply_needs_review gate: a REVIEW_REQUIRED match is applied only when explicitly opted in ────────────────


def _needs_review_recipe_fixture(tmp_path: Path) -> dict:
    """Writes a project whose only recipe match is a REVIEW_REQUIRED static-call swap that DOES carry a replacement.

    The rule declares ``risk: needs_review`` explicitly, so the match is classified REVIEW_REQUIRED even though it has a
    concrete ``replacement`` — the precise case the apply_needs_review gate governs (report-only findings have no
    replacement and are never applied regardless).
    """
    _write(
        tmp_path,
        "src/main/java/com/acme/Util.java",
        "package com.acme;\n"
        "public class Util {\n"
        "    public static int legacy(int x) { return x; }\n"
        "    public static int modern(int x) { return x; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Caller.java",
        "package com.acme;\n"
        "public class Caller {\n"
        "    int run() {\n"
        "        return Util.legacy(5);\n"
        "    }\n"
        "}\n",
    )
    return {
        "id": "acme-legacy-to-modern-review",
        "description": "Swap Util.legacy(int) for Util.modern(int); flagged needs_review.",
        "rules": [
            {
                "kind": "replaceStaticMethodCall",
                "owner": "com.acme.Util",
                "name": "legacy",
                "replacement": "com.acme.Util.modern(${arg0})",
                "risk": "needs_review",
            }
        ],
    }


def test_recipe_apply_blocks_needs_review_by_default(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The sole match is REVIEW_REQUIRED with a replacement. Without apply_needs_review (the default) the gate SKIPS it:
    # the recipe is accepted (it matched) but emits ZERO edits — the needs_review match is counted as skipped and the
    # target file is left untouched. This proves the gate withholds the edit by default (the same match IS applied once
    # apply_needs_review=True, see the companion test) rather than the param being silently accepted.
    recipe = _needs_review_recipe_fixture(tmp_path)
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is True, result
    changes = result["workspaceEdit"]["changes"]
    assert not any(c["path"].endswith("Caller.java") for c in changes), result
    assert result["stats"]["applied"] == 0, result
    assert result["stats"]["skipped"] >= 1, result


def test_recipe_apply_applies_needs_review_when_allowed(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # The SAME REVIEW_REQUIRED match IS applied once apply_needs_review=True is set, yielding a javac-validated edit.
    # Refuse-by-default + apply-when-allowed proves the param drives behavior rather than being silently accepted.
    recipe = _needs_review_recipe_fixture(tmp_path)
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe, apply_needs_review=True)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    changes = result["workspaceEdit"]["changes"]
    assert any(c["path"].endswith("Caller.java") for c in changes), result


# ── §14 refusals (exact canonical codes) ──────────────────────────────────────────────────────────────────────────


def test_recipe_scan_refuses_unknown_id(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/A.java", "package com.acme; public class A {}\n")
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.scan_migration_opportunities(recipe_id="no-such-recipe")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recipe_not_found", result


# ── §14.1 changeMethodSignature: real compiler-backed signature change (F13) ──────────────────────────────────────


def test_recipe_apply_change_method_signature(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A package-private method (no public-API confirmation needed) reordered AND renamed: the recipe engine resolves the
    # declaration via javac, drives the compiler-backed change-signature operation, and the merged decl+call-site edits
    # are javac-validated by the sidecar's before/after backstop (diagnosticDeltaValidated).
    _write(
        tmp_path,
        "src/main/java/com/acme/Calc.java",
        "package com.acme;\n"
        "class Calc {\n"
        "    int add(int a, int b) { return a - b; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/User.java",
        "package com.acme;\n"
        "class User {\n"
        "    int use() { return new Calc().add(1, 2); }\n"
        "}\n",
    )
    recipe = {
        "id": "acme-reorder-and-rename-add",
        "description": "Reorder and rename Calc.add(int,int) to Calc.sub(int,int).",
        "rules": [
            {
                "kind": "changeMethodSignature",
                "owner": "com.acme.Calc",
                "name": "add",
                "paramTypes": ["int", "int"],
                "newName": "sub",
                "parameters": [
                    {"type": "int", "name": "b", "oldIndex": 1},
                    {"type": "int", "name": "a", "oldIndex": 0},
                ],
            }
        ],
    }
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    changes = result["workspaceEdit"]["changes"]
    assert any(c["path"].endswith("Calc.java") for c in changes), result  # declaration rewritten
    assert any(c["path"].endswith("User.java") for c in changes), result  # call site rewritten


def test_recipe_change_signature_refusal_passthrough(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A changeMethodSignature rule on a public method without confirmation must surface the change-signature operation's
    # own refusal code verbatim — not a generic recipe refusal — so the capability never silently no-ops.
    _write(
        tmp_path,
        "src/main/java/com/acme/Api.java",
        "package com.acme;\n"
        "public class Api {\n"
        "    public int handle(int a, int b) { return a + b; }\n"
        "}\n",
    )
    recipe = {
        "id": "acme-rename-public-handle",
        "rules": [
            {
                "kind": "changeMethodSignature",
                "owner": "com.acme.Api",
                "name": "handle",
                "paramTypes": ["int", "int"],
                "newName": "process",
            }
        ],
    }
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "PUBLIC_API_CONFIRMATION_REQUIRED", result


def test_recipe_apply_refuses_no_matches(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # java.util.ArrayList resolves on the classpath but is never referenced by the fixture → no matches.
    _write(tmp_path, "src/main/java/com/acme/A.java", "package com.acme; public class A {}\n")
    recipe = {
        "id": "resolvable-but-unused",
        "rules": [{"kind": "replaceType", "oldType": "java.util.ArrayList", "newType": "java.util.LinkedList",
                   "risk": "safe"}],
    }
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recipe_no_matches", result


def test_recipe_apply_refuses_unresolved_symbol(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # com.nonexistent.Foo does not resolve on the classpath → every referenced type is unresolved.
    _write(tmp_path, "src/main/java/com/acme/A.java", "package com.acme; public class A {}\n")
    recipe = {
        "id": "absent-target",
        "rules": [{"kind": "replaceType", "oldType": "com.nonexistent.Foo", "newType": "com.acme.A",
                   "risk": "safe"}],
    }
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.apply_recipe(recipe=recipe)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recipe_unresolved_symbol", result


# ── §14 scope: package-subtree restriction is real, not a silent no-op ────────────────────────────────────────────


def test_recipe_scan_scope_restricts_to_package_subtree(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The same JDK-only match (java.util.Date) exists in two disjoint packages.
    _write(
        tmp_path,
        "src/main/java/com/acme/web/WebClock.java",
        "package com.acme.web;\npublic class WebClock {\n    Object now() { return new java.util.Date(); }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/data/DataClock.java",
        "package com.acme.data;\npublic class DataClock {\n    Object now() { return new java.util.Date(); }\n}\n",
    )
    with _recipes(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        unscoped = client.scan_migration_opportunities(recipe_id="date-calendar-to-java-time-basic")
        scoped = client.scan_migration_opportunities(recipe_id="date-calendar-to-java-time-basic", scope="com.acme.web")

    assert unscoped.get("accepted") is True, unscoped
    unscoped_paths = {f["path"] for f in unscoped["findings"]}
    # Baseline: without a scope BOTH packages match, so the scoped run proves filtering rather than absence of matches.
    assert any(p.endswith("WebClock.java") for p in unscoped_paths), unscoped
    assert any(p.endswith("DataClock.java") for p in unscoped_paths), unscoped

    assert scoped.get("accepted") is True, scoped
    scoped_paths = {f["path"] for f in scoped["findings"]}
    # The scope is honoured in the sidecar: only the in-subtree file's findings remain.
    assert any(p.endswith("WebClock.java") for p in scoped_paths), scoped
    assert not any(p.endswith("DataClock.java") for p in scoped_paths), scoped
