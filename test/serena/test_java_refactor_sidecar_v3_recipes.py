"""Sidecar-backed tests for the V3 migration recipe engine (G010).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the manager's ``scan_migration_opportunities`` (READ-ONLY grouped preview) and ``apply_refactor_recipe`` (javac-validated
transactional edit). Unlike the pure-Python unit tests in ``test_java_refactor_v3_recipes.py`` (which exercise the parser
and engine in isolation), these prove the two manager contracts:

* ``scan_migration_opportunities`` resolves a built-in or inline recipe, returns the grouped matches, and writes NOTHING
  (no before/after javac diagnostic delta — there is no edit to validate; analysis still uses javac facts), mirroring ``find_dead_code``;
* ``apply_refactor_recipe`` composes a transactional edit and routes it through the JAVAC VALIDATION BRIDGE so an accepted
  result carries a REAL before/after diagnostic delta (``diagnosticDeltaValidated`` true) plus the grouped ``matches`` /
  ``summary``, while an edit that would break compilation is REFUSED with newly-introduced compiler errors.

Each project is generated in a tmp dir with an EXPLICIT single-source-root configuration (no build tool) so the model is
hermetic, and ``allowIncompleteAnalysis`` keeps the harness tolerant of a classpath-less conventional layout while still
diffing newly-introduced compiler errors against the pre-edit baseline.
"""

from pathlib import Path

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import sidecar_jar  # noqa: F401

JDIR = "src/main/java/com/acme/app"


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _manager(project_root: Path) -> JavaRefactorManager:
    return JavaRefactorManager(
        str(project_root),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            enabled=True,
            build_tool_mode="explicit",
            source_roots=["src/main/java"],
            allow_incomplete_analysis=True,
        ),
    )


# An in-project Greeter whose `greet()` method a recipe can rename, plus a caller. Both compile cleanly so the bridge
# baseline is empty and a newly-introduced compiler error is unambiguously the recipe's fault.
_GREETER = (
    "package com.acme.app;\n"
    "public class Greeter {\n"
    "    public String greet() { return \"hi\"; }\n"
    "}\n"
)


def _caller(call: str) -> str:
    return (
        "package com.acme.app;\n"
        "public class Caller {\n"
        f"    public String use(Greeter g) {{ return {call}; }}\n"
        "}\n"
    )


# --- G010 apply (javac-validated edit) --------------------------------------------------------------------------------


def test_sidecar_apply_recipe_preview_validates_and_surfaces_matches(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # A replaceConstructor recipe that swaps an in-project type's constructor for another in-project type passes the real
    # javac bridge (diagnosticDeltaValidated true), surfaces the grouped matches, and writes NOTHING on preview.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_preview"
    _write(project, f"{JDIR}/Foo.java", "package com.acme.app;\npublic class Foo {}\n")
    _write(project, f"{JDIR}/Bar.java", "package com.acme.app;\npublic class Bar {}\n")
    _write(
        project,
        f"{JDIR}/Main.java",
        "package com.acme.app;\npublic class Main {\n    Object make() { return new Foo(); }\n}\n",
    )

    document = (
        '{"id": "foo-to-bar", "rules": [{"kind": "replaceConstructor", '
        '"owner": "com.acme.app.Foo", "replacement": "new Bar()", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        result = manager.apply_refactor_recipe(recipe_document=document, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "applyRefactorRecipe", result
    assert result["recipe"] == "foo-to-bar", result
    assert result["matchCount"] >= 1, result
    assert result["matches"], result
    assert f"{JDIR}/Main.java" in result["groups"]["byFile"], result["groups"]
    # Preview must not touch disk.
    assert "new Foo()" in (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")


def test_sidecar_apply_recipe_apply_rewrites(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_apply"
    _write(project, f"{JDIR}/Foo.java", "package com.acme.app;\npublic class Foo {}\n")
    _write(project, f"{JDIR}/Bar.java", "package com.acme.app;\npublic class Bar {}\n")
    _write(
        project,
        f"{JDIR}/Main.java",
        "package com.acme.app;\npublic class Main {\n    Object make() { return new Foo(); }\n}\n",
    )

    document = (
        '{"id": "foo-to-bar", "rules": [{"kind": "replaceConstructor", '
        '"owner": "com.acme.app.Foo", "replacement": "new Bar()", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        result = manager.apply_refactor_recipe(recipe_document=document, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    main = (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")
    assert "new Bar()" in main
    assert "new Foo()" not in main


def test_sidecar_apply_recipe_removes_stale_import_and_retains_used_one(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # §14.2 "remove stale imports": a replaceStaticMethodCall that drops the only reference to an imported helper type
    # leaves its single-type import dangling, so the engine removes it (symmetric to RECIPE_addImport). The SAME run must
    # KEEP a second import that is still referenced after the edit — proving "do NOT remove imports still referenced".
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_stale_import"
    # Two helper types in a DIFFERENT package, so each is brought in by a real single-type import.
    _write(
        project,
        "src/main/java/com/acme/util/Wrap.java",
        "package com.acme.util;\npublic class Wrap {\n    public static String of(String s) { return s; }\n}\n",
    )
    _write(
        project,
        "src/main/java/com/acme/util/Keep.java",
        "package com.acme.util;\npublic class Keep {\n    public static String of(String s) { return s; }\n}\n",
    )
    # Caller imports BOTH. The recipe replaces Wrap.of(...) with its argument (removing the only Wrap reference), while
    # Keep.of(...) stays — so import Wrap is stale and import Keep must survive.
    _write(
        project,
        f"{JDIR}/Caller.java",
        "package com.acme.app;\n"
        "import com.acme.util.Wrap;\n"
        "import com.acme.util.Keep;\n"
        "public class Caller {\n"
        "    public String use(String x) { return Wrap.of(x) + Keep.of(x); }\n"
        "}\n",
    )

    document = (
        '{"id": "drop-wrap", "rules": [{"kind": "replaceStaticMethodCall", '
        '"owner": "com.acme.util.Wrap", "name": "of", "replacement": "${arg0}", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        result = manager.apply_refactor_recipe(recipe_document=document, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    caller = (project / f"{JDIR}/Caller.java").read_text(encoding="utf-8")
    # The Wrap.of(x) call was replaced by its argument.
    assert "Wrap.of(x)" not in caller, caller
    assert "Keep.of(x)" in caller, caller
    # The now-unreferenced import was removed; the still-referenced one was retained.
    assert "import com.acme.util.Wrap;" not in caller, caller
    assert "import com.acme.util.Keep;" in caller, caller


def test_sidecar_apply_recipe_add_annotation_inserts_and_is_idempotent(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # addAnnotation (§14.1): a type-level rule inserts the annotation before the declaration. ``@Deprecated`` lives in
    # java.lang, so no import is required and the javac bridge validates cleanly (diagnosticDeltaValidated true). A
    # second apply over the already-annotated source matches nothing — the element already carries the annotation —
    # proving the getAnnotationMirrors idempotency gate.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_add_anno"
    _write(project, f"{JDIR}/Greeter.java", _GREETER)

    document = (
        '{"id": "deprecate-greeter", "rules": [{"kind": "addAnnotation", '
        '"owner": "com.acme.app.Greeter", "newType": "java.lang.Deprecated", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        applied = manager.apply_refactor_recipe(recipe_document=document, apply=True)
        # Re-run against the now-annotated source: the idempotency gate yields no matches.
        rerun = manager.apply_refactor_recipe(recipe_document=document, apply=False)
    finally:
        manager.shutdown()

    assert applied.get("accepted") is True, applied
    assert applied.get("applied") is True, applied
    assert applied.get("diagnosticDeltaValidated") is True, applied
    greeter = (project / f"{JDIR}/Greeter.java").read_text(encoding="utf-8")
    assert "@Deprecated" in greeter
    assert greeter.index("@Deprecated") < greeter.index("public class Greeter")

    assert rerun.get("accepted") is False, rerun
    assert rerun["refusal"]["code"] == "recipe_no_matches", rerun


def test_sidecar_apply_recipe_refuses_new_compiler_errors(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Bridge-level refusal: a replaceMethodCall that renames an in-project method to one that does not exist composes a
    # syntactically-valid edit the engine accepts, but the javac bridge catches the newly-introduced compiler error,
    # refuses with new_compiler_errors, and writes nothing.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_breaks"
    _write(project, f"{JDIR}/Greeter.java", _GREETER)
    _write(project, f"{JDIR}/Caller.java", _caller("g.greet()"))

    document = (
        '{"id": "break-greet", "rules": [{"kind": "replaceMethodCall", '
        '"owner": "com.acme.app.Greeter", "name": "greet", "replacement": "${receiver}.salute()", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        preview = manager.apply_refactor_recipe(recipe_document=document, apply=False)
        applied = manager.apply_refactor_recipe(recipe_document=document, apply=True)
    finally:
        manager.shutdown()

    assert preview.get("accepted") is False, preview
    assert preview.get("applied") is False, preview
    assert preview.get("diagnosticDeltaValidated") is False, preview
    assert preview["refusal"]["code"] == "new_compiler_errors", preview
    # Apply: the apply-time gate also fails closed; nothing is written.
    assert applied.get("accepted") is False, applied
    assert applied.get("applied") is False, applied
    assert applied.get("editsAlreadyApplied") is False, applied
    assert applied["refusal"]["code"] in ("pre_apply_validation_failed", "post_validation_failed"), applied
    assert "g.greet()" in (project / f"{JDIR}/Caller.java").read_text(encoding="utf-8")


def test_sidecar_apply_recipe_refuses_no_matches(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Planner-level refusal: a recipe that matches nothing is refused before the javac bridge, writing nothing.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_no_matches"
    # Greeter resolves on the classpath, but greet() is never CALLED, so the recipe matches nothing to apply. This is the
    # resolved-but-no-match case (recipe_no_matches), distinct from an all-unresolved recipe (recipe_unresolved_symbol).
    _write(project, f"{JDIR}/Greeter.java", _GREETER)

    document = (
        '{"id": "no-call", "rules": [{"kind": "replaceMethodCall", '
        '"owner": "com.acme.app.Greeter", "name": "greet", "replacement": "${receiver}.salute()", "risk": "safe"}]}'
    )

    manager = _manager(project)
    try:
        result = manager.apply_refactor_recipe(recipe_document=document, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recipe_no_matches", result


def test_sidecar_apply_recipe_refuses_overlapping_edits(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # R07: two rules that produce OVERLAPPING edit ranges in one file must refuse the WHOLE apply (structured
    # recipe_overlapping_edits), not silently drop one and apply the other (no partial subset). The overlap is produced
    # by the REAL compiler matcher, not a mock: a `replaceConstructor` on com.acme.app.Foo matches the whole `new Foo()`
    # expression, while a `replaceType` Foo -> Baz matches the simple-name `Foo` reference NESTED inside that same span.
    # Both are `safe` with a concrete replacement, so both are editable and their ranges overlap.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_overlap"
    _write(project, f"{JDIR}/Foo.java", "package com.acme.app;\npublic class Foo {}\n")
    _write(project, f"{JDIR}/Bar.java", "package com.acme.app;\npublic class Bar {}\n")
    _write(project, f"{JDIR}/Baz.java", "package com.acme.app;\npublic class Baz {}\n")
    main_src = "package com.acme.app;\npublic class Main {\n    Object make() { return new Foo(); }\n}\n"
    _write(project, f"{JDIR}/Main.java", main_src)

    document = (
        '{"id": "overlap", "rules": ['
        '{"kind": "replaceConstructor", "owner": "com.acme.app.Foo", "replacement": "new Bar()", "risk": "safe"},'
        '{"kind": "replaceType", "oldType": "com.acme.app.Foo", "newType": "com.acme.app.Baz", "risk": "safe"}'
        "]}"
    )

    manager = _manager(project)
    try:
        preview = manager.apply_refactor_recipe(recipe_document=document, apply=False)
        applied = manager.apply_refactor_recipe(recipe_document=document, apply=True)
    finally:
        manager.shutdown()

    # The whole apply is refused with the structured conflict code; no partial subset of edits is returned (any
    # workspaceEdit envelope present on a refusal carries zero changes — no rule was silently applied).
    assert preview.get("accepted") is False, preview
    assert preview["refusal"]["code"] == "recipe_overlapping_edits", preview
    assert not (preview.get("workspaceEdit") or {}).get("changes"), preview
    assert not preview.get("changedFiles"), preview
    assert applied.get("accepted") is False, applied
    assert applied.get("applied") is False, applied
    assert applied["refusal"]["code"] == "recipe_overlapping_edits", applied
    assert not (applied.get("workspaceEdit") or {}).get("changes"), applied
    assert not applied.get("changedFiles"), applied
    # Nothing was written: the original `new Foo()` is untouched (no silent partial apply of either rule).
    assert (project / f"{JDIR}/Main.java").read_text(encoding="utf-8") == main_src


def test_sidecar_apply_recipe_refuses_unknown_builtin(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Recipe-resolution refusal: an unknown built-in name is a structured refusal, not a crash.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_unknown"
    _write(project, f"{JDIR}/Main.java", "package com.acme.app;\npublic class Main {}\n")

    manager = _manager(project)
    try:
        result = manager.apply_refactor_recipe(recipe_name="does-not-exist", apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recipe_not_found", result


# --- G010 scan (read-only grouped preview, no javac) ------------------------------------------------------------------


def test_sidecar_scan_migration_opportunities_groups_and_writes_nothing(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # The read-only scan resolves an inline in-project recipe, returns the grouped matches, and writes nothing (no
    # javac edit to validate). An inline recipe over project types keeps the model hermetic (a built-in junit/javax
    # recipe would need those libraries on the classpath to resolve a single symbol).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_scan"
    _write(project, f"{JDIR}/Greeter.java", _GREETER)
    caller_src = _caller("g.greet()")
    _write(project, f"{JDIR}/Caller.java", caller_src)
    original = caller_src

    document = (
        '{"id": "greet-to-salute", "rules": [{"kind": "replaceMethodCall", '
        '"owner": "com.acme.app.Greeter", "name": "greet", "replacement": "${receiver}.salute()"}]}'
    )

    manager = _manager(project)
    try:
        result = manager.scan_migration_opportunities(recipe_document=document)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result["operation"] == "scanMigrationOpportunities", result
    assert result["mode"] == "scan", result
    assert result["recipe"] == "greet-to-salute", result
    assert result["matchCount"] >= 1, result
    assert result["matches"], result
    assert f"{JDIR}/Caller.java" in result["groups"]["byFile"], result["groups"]
    # The scan has no edit and no validation report.
    assert "diagnosticDeltaValidated" not in result, result
    assert "preview" not in result, result
    # Nothing was written.
    assert (project / f"{JDIR}/Caller.java").read_text(encoding="utf-8") == original


def test_sidecar_scan_migration_opportunities_refuses_bad_document(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # The scan surfaces a recipe parse error as a structured refusal (no crash) and writes nothing.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "recipe_scan_bad"
    _write(project, f"{JDIR}/Main.java", "package com.acme.app;\npublic class Main {}\n")

    manager = _manager(project)
    try:
        # Empty selection (neither name nor document) is ambiguous.
        ambiguous = manager.scan_migration_opportunities()
        # A document with an unknown rule kind is a parse error.
        bad_kind = manager.scan_migration_opportunities(
            recipe_document='{"id": "x", "rules": [{"kind": "teleport", "from": "A", "to": "B"}]}'
        )
    finally:
        manager.shutdown()

    assert ambiguous.get("accepted") is False, ambiguous
    assert ambiguous["refusal"]["code"] == "recipe_selection_ambiguous", ambiguous
    assert bad_kind.get("accepted") is False, bad_kind
    assert bad_kind["refusal"]["code"] == "recipe_unknown_rule_kind", bad_kind


def test_recipe_apply_refuses_review_required_matches_before_writing(monkeypatch) -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    payload = {
        "accepted": True,
        "recipeId": "demo",
        "stats": {"matches": 2, "applied": 1, "skipped": 1, "refused": 0},
        "matches": [{"risk": "SAFE"}, {"risk": "REVIEW_REQUIRED"}],
        "summary": {"filesChanged": 1},
        "edit": {"format": "serena-workspace-edit-v1", "changes": []},
    }

    manager._v3_disabled_refusal = lambda operation, apply: None  # type: ignore[attr-defined]
    manager._validate_supported_project = lambda: None  # type: ignore[attr-defined]
    manager._select_recipe = lambda operation, apply, recipe_name, recipe_document: ("demo", {})  # type: ignore[attr-defined]
    manager._get_or_start_client = lambda refresh=False: object()  # type: ignore[attr-defined]

    def _must_not_route(*args, **kwargs):
        raise AssertionError("review-required recipe apply must be refused before routing edits")

    manager._route_sidecar_v3_edit = _must_not_route  # type: ignore[attr-defined]
    monkeypatch.setattr(
        "serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient.apply_recipe",
        lambda self, **kwargs: payload,
    )

    result = manager.apply_refactor_recipe(recipe_document='{"rules": []}', apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "recipe_review_required"
    assert result["stats"]["skipped"] == 1


def test_recipe_apply_allows_review_required_matches_only_with_explicit_approval(monkeypatch) -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    payload = {
        "accepted": True,
        "recipeId": "demo",
        "stats": {"matches": 1, "applied": 1, "skipped": 0, "refused": 0},
        "matches": [{"risk": "REVIEW_REQUIRED"}],
        "groups": {"byRisk": [{"key": "REVIEW_REQUIRED", "matches": [{"risk": "REVIEW_REQUIRED"}]}]},
        "summary": {"filesChanged": 1},
        "edit": {"format": "serena-workspace-edit-v1", "changes": []},
    }
    routed = {}

    manager._v3_disabled_refusal = lambda operation, apply: None  # type: ignore[attr-defined]
    manager._validate_supported_project = lambda: None  # type: ignore[attr-defined]
    manager._select_recipe = lambda operation, apply, recipe_name, recipe_document: ("demo", {})  # type: ignore[attr-defined]
    manager._get_or_start_client = lambda refresh=False: object()  # type: ignore[attr-defined]

    def _route(operation, sidecar_payload, *, apply, validate, allow_review_required):
        routed["allow_review_required"] = allow_review_required
        return {"accepted": True, "applied": True, "operation": operation}

    manager._route_sidecar_v3_edit = _route  # type: ignore[attr-defined]
    monkeypatch.setattr(
        "serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient.apply_recipe",
        lambda self, **kwargs: payload,
    )

    result = manager.apply_refactor_recipe(
        recipe_document='{"rules": []}', apply=True, allow_review_required=True
    )

    assert result["accepted"] is True
    assert result["applied"] is True
    assert routed["allow_review_required"] is True


def test_recipe_apply_refuses_refused_matches_before_writing(monkeypatch) -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    payload = {
        "accepted": True,
        "recipeId": "demo",
        "stats": {"matches": 1, "applied": 0, "skipped": 0, "refused": 1},
        "matches": [{"risk": "REFUSED"}],
        "summary": {"filesChanged": 0},
        "edit": {"format": "serena-workspace-edit-v1", "changes": []},
    }

    manager._v3_disabled_refusal = lambda operation, apply: None  # type: ignore[attr-defined]
    manager._validate_supported_project = lambda: None  # type: ignore[attr-defined]
    manager._select_recipe = lambda operation, apply, recipe_name, recipe_document: ("demo", {})  # type: ignore[attr-defined]
    manager._get_or_start_client = lambda refresh=False: object()  # type: ignore[attr-defined]
    manager._route_sidecar_v3_edit = lambda *args, **kwargs: (_ for _ in ()).throw(  # type: ignore[attr-defined]
        AssertionError("refused recipe apply must be refused before routing edits")
    )
    monkeypatch.setattr(
        "serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient.apply_recipe",
        lambda self, **kwargs: payload,
    )

    result = manager.apply_refactor_recipe(
        recipe_document='{"rules": []}', apply=True, allow_review_required=True
    )

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "recipe_refused_match"


def test_recipe_apply_refuses_review_required_match_even_when_sidecar_marks_applied(monkeypatch):
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    payload = {
        "accepted": True,
        "recipeId": "demo",
        "stats": {"matches": 1, "applied": 1, "skipped": 0, "refused": 0},
        "matches": [{"risk": "REVIEW_REQUIRED"}],
        "workspaceEdit": {"format": "serena-workspace-edit-v1", "changes": []},
    }
    routed = []
    manager._v3_disabled_refusal = lambda operation, apply: None
    manager._validate_supported_project = lambda: None
    manager._select_recipe = lambda operation, apply, recipe_name, recipe_document: ("demo", {})
    manager._get_or_start_client = lambda refresh=False: object()
    manager._route_sidecar_v3_edit = lambda *args, **kwargs: routed.append((args, kwargs)) or {"accepted": True}
    monkeypatch.setattr(
        "serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient.apply_recipe",
        lambda self, **kwargs: payload,
    )

    result = manager.apply_refactor_recipe(recipe_document='{"id":"demo","rules":[]}', apply=True)

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "recipe_review_required"
    assert routed == []


def test_recipe_apply_refuses_refused_match_even_with_review_approval(monkeypatch):
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    payload = {
        "accepted": True,
        "recipeId": "demo",
        "stats": {"matches": 1, "applied": 1, "skipped": 0, "refused": 0},
        "matches": [{"risk": "REFUSED"}],
        "workspaceEdit": {"format": "serena-workspace-edit-v1", "changes": []},
    }
    routed = []
    manager._v3_disabled_refusal = lambda operation, apply: None
    manager._validate_supported_project = lambda: None
    manager._select_recipe = lambda operation, apply, recipe_name, recipe_document: ("demo", {})
    manager._get_or_start_client = lambda refresh=False: object()
    manager._route_sidecar_v3_edit = lambda *args, **kwargs: routed.append((args, kwargs)) or {"accepted": True}
    monkeypatch.setattr(
        "serena.java_refactor_v3.recipe_engine_client.RecipeEngineClient.apply_recipe",
        lambda self, **kwargs: payload,
    )

    result = manager.apply_refactor_recipe(
        recipe_document='{"id":"demo","rules":[]}', apply=True, allow_review_required=True
    )

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "recipe_refused_match"
    assert routed == []
