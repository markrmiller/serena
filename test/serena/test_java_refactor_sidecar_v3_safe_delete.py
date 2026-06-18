"""Sidecar-backed tests for the V3 propagating safe delete + dead-code scan (G004 / JavaPropagateSafeDeleteTool).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the manager's ``propagate_safe_delete`` / ``find_dead_code`` methods. These
prove the JAVAC VALIDATION BRIDGE: the sidecar-planned deletion edit is routed through the sidecar's generic
``validateEdit`` so an accepted result carries a REAL before/after diagnostic delta (``diagnosticDeltaValidated`` true),
and a deletion that would break compilation is REFUSED with newly-introduced compiler errors and writes nothing.

The project is generated in a tmp dir with an EXPLICIT single-source-root configuration (no build tool) so the model is
hermetic, and ``allowIncompleteAnalysis`` keeps the harness tolerant of a classpath-less conventional layout while still
diffing newly-introduced compiler errors against the pre-edit baseline.
"""

from pathlib import Path

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


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


def _write_project(root: Path) -> None:
    """A self-contained single-root project: a live Main->Service->Repo chain plus a genuinely-orphaned Orphan cluster."""
    j = "src/main/java/com/acme/app"
    _write(
        root,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) { new Service().run(); }\n}\n",
    )
    _write(
        root,
        f"{j}/Service.java",
        "package com.acme.app;\npublic class Service {\n  public void run() { new Repo().load(); }\n}\n",
    )
    _write(root, f"{j}/Repo.java", "package com.acme.app;\npublic class Repo {\n  public void load() {}\n}\n")
    # Orphan -> OrphanHelper: a dead cluster referenced by nobody live.
    _write(root, f"{j}/Orphan.java", "package com.acme.app;\nclass Orphan {\n  void use() { new OrphanHelper(); }\n}\n")
    _write(root, f"{j}/OrphanHelper.java", "package com.acme.app;\nclass OrphanHelper {}\n")


def test_sidecar_safe_delete_preview_validates_orphan_deletion(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # (a) A safe-delete preview of a genuinely-orphaned type passes the real javac bridge (diagnosticDeltaValidated true),
    # returns the native graph-shaped deletePlan {requested, cascade, blocked}, plans the expected file deletion, and
    # writes NOTHING (preview only).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_orphan"
    _write_project(project)

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Orphan"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert "diagnosticDelta" in result, result
    # Native graph-shaped deletePlan: the requested root resolves and is deletable, nothing is blocked.
    plan = result["deletePlan"]
    assert plan["requested"] == ["com.acme.app.Orphan"], plan
    assert plan["blocked"] == [], plan
    # The cascade is keyed on reference edges between *deleted* nodes; OrphanHelper is referenced from Orphan#use() (a
    # member node not in the deleted set), so the native engine deletes only the requested root here (single-level).
    assert result["stats"]["deleted"] == 1, result["stats"]
    assert result["stats"]["blocked"] == 0, result["stats"]
    touched = set(result["preview"]["touchedFiles"])
    assert "src/main/java/com/acme/app/Orphan.java" in touched, touched
    # Preview must not touch disk.
    assert (project / "src/main/java/com/acme/app/Orphan.java").exists()
    assert (project / "src/main/java/com/acme/app/OrphanHelper.java").exists()


def test_sidecar_safe_delete_apply_removes_orphan_files(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Apply path: the javac-validated deletion is committed transactionally, the requested root's file is gone, and the
    # live chain is untouched. diagnosticDeltaValidated remains true (post-commit javac post-validation passed). The
    # native engine deletes the requested root only (single-level cascade); OrphanHelper is referenced from Orphan#use()
    # so it survives this deletion and still compiles standalone.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_apply"
    _write_project(project)

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Orphan"], apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert not (project / "src/main/java/com/acme/app/Orphan.java").exists()
    assert (project / "src/main/java/com/acme/app/OrphanHelper.java").exists()
    assert (project / "src/main/java/com/acme/app/Service.java").exists()
    assert (project / "src/main/java/com/acme/app/Repo.java").exists()


def test_sidecar_safe_delete_refuses_when_deletion_breaks_compilation(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # (b) Forcing a still-referenced type as a seed plans a deletion the planner accepts (the type is package-private, not
    # on the API boundary) but that WOULD break compilation: Service still calls Repo.load(). The javac bridge catches the
    # newly-introduced compiler error, refuses with new_compiler_errors, and writes nothing.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_breaks"
    j = "src/main/java/com/acme/app"
    _write(
        project,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) { new Service().run(); }\n}\n",
    )
    _write(
        project,
        f"{j}/Service.java",
        "package com.acme.app;\npublic class Service {\n  public void run() { new Repo().load(); }\n}\n",
    )
    # Repo is package-private (so it is not refused as a public-API boundary seed) but is still referenced by Service.
    _write(project, f"{j}/Repo.java", "package com.acme.app;\nclass Repo {\n  void load() {}\n}\n")

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Repo"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is False, result
    assert result["refusal"]["code"] == "new_compiler_errors", result
    # Nothing was written: the still-referenced type remains on disk.
    assert (project / "src/main/java/com/acme/app/Repo.java").exists()


def test_sidecar_safe_delete_apply_refuses_breaking_deletion_and_writes_nothing(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # The apply-time safety gate also fails closed: a deletion that breaks compilation is refused at staged pre-commit
    # validation, leaving every file on disk untouched.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_breaks_apply"
    j = "src/main/java/com/acme/app"
    _write(
        project,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) { new Service().run(); }\n}\n",
    )
    _write(
        project,
        f"{j}/Service.java",
        "package com.acme.app;\npublic class Service {\n  public void run() { new Repo().load(); }\n}\n",
    )
    _write(project, f"{j}/Repo.java", "package com.acme.app;\nclass Repo {\n  void load() {}\n}\n")

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Repo"], apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result.get("applied") is False, result
    assert result.get("editsAlreadyApplied") is False, result
    # The refusal is the staged pre-commit gate carrying the new-compiler-errors distinction.
    assert result["refusal"]["code"] in ("pre_apply_validation_failed", "post_validation_failed"), result
    assert (project / "src/main/java/com/acme/app/Repo.java").exists()


def test_sidecar_safe_delete_models_unresolvable_and_boundary_roots_as_blocked(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # Native graph-shaped contract: an unresolvable root and a public-API / framework-boundary root are ACCEPTED
    # outcomes surfaced in deletePlan.blocked (each with a reason), absent from cascade, and the emitted edit removes
    # NOTHING. An empty seed list is the sole planner-level REFUSAL (no_roots).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_blocked"
    _write_project(project)

    manager = _manager(project)
    try:
        unknown = manager.propagate_safe_delete(["com.acme.app.DoesNotExist"], apply=False)
        # Main is public AND a main(String[]) entry point: it is on the API/framework boundary, so it is blocked.
        blocked = manager.propagate_safe_delete(["com.acme.app.Main"], apply=False)
        empty = manager.propagate_safe_delete([], apply=False)
    finally:
        manager.shutdown()

    # --- unresolvable root: accepted, blocked-with-reason, absent from cascade, writes nothing. -----------------------
    assert unknown.get("accepted") is True, unknown
    unknown_plan = unknown["deletePlan"]
    unknown_blocked = {entry["symbol"]: entry["reason"] for entry in unknown_plan["blocked"]}
    assert "com.acme.app.DoesNotExist" in unknown_blocked, unknown_plan
    assert unknown_blocked["com.acme.app.DoesNotExist"], unknown_plan  # non-empty reason
    assert {entry["symbol"] for entry in unknown_plan["cascade"]} == set(), unknown_plan
    assert unknown["stats"]["deleted"] == 0, unknown["stats"]
    assert set(unknown["preview"]["touchedFiles"]) == set(), unknown["preview"]

    # --- boundary root: accepted, blocked-with-reason, absent from cascade, writes nothing. ---------------------------
    assert blocked.get("accepted") is True, blocked
    blocked_plan = blocked["deletePlan"]
    blocked_blocked = {entry["symbol"]: entry["reason"] for entry in blocked_plan["blocked"]}
    assert "com.acme.app.Main" in blocked_blocked, blocked_plan
    assert blocked_blocked["com.acme.app.Main"], blocked_plan  # non-empty reason
    assert {entry["symbol"] for entry in blocked_plan["cascade"]} == set(), blocked_plan
    assert blocked["stats"]["deleted"] == 0, blocked["stats"]
    assert set(blocked["preview"]["touchedFiles"]) == set(), blocked["preview"]

    # --- empty seed list: the sole planner-level refusal, writing nothing. -------------------------------------------
    assert empty.get("accepted") is False, empty
    assert empty["refusal"]["code"] == "no_roots", empty


def test_sidecar_find_dead_code_scan_is_read_only(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # The dead-code scan is a read-only analysis: it reports the orphaned cluster as candidates, never an edit, and
    # touches no files. It runs no javac (there is nothing to validate).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "find_dead_code"
    _write_project(project)

    manager = _manager(project)
    try:
        result = manager.find_dead_code()
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("mode") == "scan", result
    found = {entry["symbol"] for entry in result["deadCodeCandidates"]}
    # The orphaned, non-public type with no incoming semantic references is flagged.
    assert "com.acme.app.Orphan" in found, found
    # OrphanHelper is still referenced (from Orphan#use()), so the native scan does NOT flag it while Orphan exists.
    assert "com.acme.app.OrphanHelper" not in found, found
    # The live chain is never flagged.
    assert "com.acme.app.Main" not in found, found
    assert "com.acme.app.Service" not in found, found
    assert "com.acme.app.Repo" not in found, found


def test_sidecar_safe_delete_removes_unambiguous_xml_bean(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # Plan §7.3 step 8 / §16.1 / §15.2: a deleted type whose FQN survives as an UNAMBIGUOUS exact class reference in a
    # Spring XML bean (`<bean class="com.old.Foo"/>`) is rewritten by the unified resource engine — the bean definition
    # is removed, not surfaced as a hand-review warning. Per §24 the resource touch keeps the result at REVIEW_REQUIRED,
    # so the apply requires allow_review_required=True; the deletion is still accepted and javac-validated.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_xml_bean"
    j = "src/main/java/com/acme/app"
    _write(
        root=project,
        rel=f"{j}/Main.java",
        content="package com.acme.app;\npublic class Main {\n  public static void main(String[] args) {}\n}\n",
    )
    # Orphan is referenced by nobody in Java, so it deletes cleanly — and its FQN in beans.xml is an exact class ref.
    _write(project, f"{j}/Orphan.java", "package com.acme.app;\nclass Orphan {}\n")
    _write(
        project,
        "src/main/resources/beans.xml",
        '<beans>\n  <bean id="orphan" class="com.acme.app.Orphan"/>\n</beans>\n',
    )

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(
            ["com.acme.app.Orphan"], apply=True, allow_review_required=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    # The unambiguous exact-class bean is auto-removed, not flagged as a dangling must-review reference.
    warnings = result.get("warnings", [])
    assert not [w for w in warnings if "must be reviewed" in w], warnings
    # The bean definition naming the deleted type is gone from the resource.
    assert (project / "src/main/resources/beans.xml").read_text(encoding="utf-8").count("com.acme.app.Orphan") == 0
    # The Java declaration is deleted too.
    assert not (project / f"{j}/Orphan.java").exists()


def test_sidecar_safe_delete_removes_emptied_package_directory(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Plan §7.3 step 7 / §19.2: deleting the SOLE class of a package empties that package directory, so the planner
    # reports it under removedDirectories. The shared parent package (com/acme/app) still holds the live Main, so it is
    # kept; only the now-empty leaf subpackage (com/acme/app/legacy) is pruned. The block contract is honored for free:
    # Main is a live entry point whose file is never deleted, so its package is never reported empty.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_empty_pkg"
    j = "src/main/java/com/acme/app"
    _write(
        project,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) {}\n}\n",
    )
    # LegacyOnly is the single, orphaned (package-private, referenced by nobody) class in com.acme.app.legacy.
    _write(
        project,
        f"{j}/legacy/LegacyOnly.java",
        "package com.acme.app.legacy;\nclass LegacyOnly {}\n",
    )

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.legacy.LegacyOnly"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result["stats"]["deleted"] == 1, result["stats"]
    removed = set(result.get("removedDirectories", []))
    # The emptied leaf package directory is pruned; the shared parent package (still holding live Main) is not.
    assert "src/main/java/com/acme/app/legacy" in removed, removed
    assert "src/main/java/com/acme/app" not in removed, removed
    # The source root itself is never a removal candidate.
    assert "src/main/java" not in removed, removed


def test_sidecar_safe_delete_keeps_package_with_surviving_class(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Plan §19.2 safety: a package directory with a surviving file is NEVER removed. Two orphan classes share a package;
    # deleting one of them leaves the other on disk, so the package stays non-empty and is absent from removedDirectories.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_keep_pkg"
    j = "src/main/java/com/acme/app"
    _write(
        project,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) {}\n}\n",
    )
    # Two independent orphans in com.acme.app.legacy: deleting one keeps the package alive via the other.
    _write(project, f"{j}/legacy/OrphanA.java", "package com.acme.app.legacy;\nclass OrphanA {}\n")
    _write(project, f"{j}/legacy/OrphanB.java", "package com.acme.app.legacy;\nclass OrphanB {}\n")

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.legacy.OrphanA"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result["stats"]["deleted"] == 1, result["stats"]
    removed = set(result.get("removedDirectories", []))
    # OrphanB survives in com.acme.app.legacy, so the package directory is NOT pruned.
    assert "src/main/java/com/acme/app/legacy" not in removed, removed
    assert "src/main/java/com/acme/app" not in removed, removed


def test_sidecar_safe_delete_blocks_exact_framework_annotation_not_lookalike(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # F6 honesty: framework-entry conservatism keys on the EXACT fully-qualified annotation, never the simple name.
    # Two orphan beans share the simple name @Service, but only the one carrying the real Spring annotation
    # (org.springframework.stereotype.Service) is a framework entry point and BLOCKED; a user's own same-simple-name
    # annotation (com.acme.anno.Service) carries no framework meaning, so its bean stays deletable.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "safe_delete_framework_fqn"
    j = "src/main/java/com/acme/app"
    _write(
        project,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) {}\n}\n",
    )
    # Real Spring annotation, stubbed under its true package so the compiler resolves the exact FQN.
    _write(
        project,
        "src/main/java/org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;\npublic @interface Service {}\n",
    )
    # A user-defined annotation that merely happens to be named Service — no framework meaning.
    _write(
        project,
        "src/main/java/com/acme/anno/Service.java",
        "package com.acme.anno;\npublic @interface Service {}\n",
    )
    # Both beans are package-private orphans (off the public-API boundary, referenced by nobody live).
    _write(
        project,
        f"{j}/RealBean.java",
        "package com.acme.app;\nimport org.springframework.stereotype.Service;\n@Service\nclass RealBean {}\n",
    )
    _write(
        project,
        f"{j}/FakeBean.java",
        "package com.acme.app;\nimport com.acme.anno.Service;\n@Service\nclass FakeBean {}\n",
    )

    manager = _manager(project)
    try:
        real = manager.propagate_safe_delete(["com.acme.app.RealBean"], apply=False)
        fake = manager.propagate_safe_delete(["com.acme.app.FakeBean"], apply=False)
    finally:
        manager.shutdown()

    # --- Real Spring @Service bean: blocked as a framework entry point, named by its exact annotation FQN. -----------
    assert real.get("accepted") is True, real
    real_blocked = {entry["symbol"]: entry["reason"] for entry in real["deletePlan"]["blocked"]}
    assert "com.acme.app.RealBean" in real_blocked, real["deletePlan"]
    assert "framework entry point" in real_blocked["com.acme.app.RealBean"], real_blocked
    assert "org.springframework.stereotype.Service" in real_blocked["com.acme.app.RealBean"], real_blocked
    assert real["stats"]["deleted"] == 0, real["stats"]
    assert set(real["preview"]["touchedFiles"]) == set(), real["preview"]

    # --- Lookalike @Service bean: NOT a framework entry, so it remains deletable (no false positive). ----------------
    assert fake.get("accepted") is True, fake
    assert fake.get("diagnosticDeltaValidated") is True, fake
    assert fake["deletePlan"]["blocked"] == [], fake["deletePlan"]
    assert fake["stats"]["deleted"] == 1, fake["stats"]
    fake_touched = set(fake["preview"]["touchedFiles"])
    assert any("FakeBean.java" in path for path in fake_touched), fake_touched
