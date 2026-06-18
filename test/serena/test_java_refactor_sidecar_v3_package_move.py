"""Sidecar-backed tests for the V3 ``movePackage`` operation (G003 second half).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the same JSON-lines preview harness the other ``test_java_refactor_sidecar_*`` modules use. They prove the Python MCP
surface's underlying operation end-to-end: an accepted package move relocates the source package AND its subpackage,
carrying the file rename ops, the package-declaration edits, and the cross-package import rewrites, AND a javac-validated
before/after diagnostic delta (it went through the central ``PreviewDiagnosticValidator``, not a placeholder); excluding
subpackages leaves them untouched; a colliding target is refused with ``package_collision``.
"""

import shutil
from pathlib import Path

from test.serena._java_refactor_sidecar_helpers import (
    _preview_op,
    file_ops,
    sidecar_jar,
    text_edits,
)

FIXTURE_ROOT = Path(__file__).parent.parent / "resources/repos/java_refactor_v3/package_move_basic"


def _stage_fixture(tmp_path: Path) -> Path:
    """Copies the read-only fixture repo into a writable tmp project root for the sidecar to operate on."""
    project_root = tmp_path / "package_move_basic"
    shutil.copytree(FIXTURE_ROOT, project_root)
    return project_root


def test_sidecar_move_package_moves_package_and_subpackage_with_validated_delta(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # Happy path: moving com.acme.app -> com.acme.core relocates Service.java app/ -> core/ AND (subpackages included by
    # default) Helper.java app/util/ -> core/util/, rewrites both package declarations, rewrites both cross-package
    # imports in Client.java, and the accepted result carries the REAL javac diagnostic delta (diagnosticDeltaValidated
    # true) proving it passed through PreviewDiagnosticValidator.
    project_root = _stage_fixture(tmp_path)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert "diagnostics" in result, result
    assert isinstance(result["diagnostics"], list), result

    operations = file_ops(result["workspaceEdit"])
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in operations
        if op["kind"] == "rename"
    }
    assert (
        "src/main/java/com/acme/app/Service.java",
        "src/main/java/com/acme/core/Service.java",
    ) in renames, operations
    assert (
        "src/main/java/com/acme/app/util/Helper.java",
        "src/main/java/com/acme/core/util/Helper.java",
    ) in renames, operations

    edits = text_edits(result["workspaceEdit"])

    # Package-declaration edit on the moved root-package file.
    service_relative = "src/main/java/com/acme/app/Service.java"
    service_source = (project_root / "src/main/java/com/acme/app/Service.java").read_text(encoding="utf-8")
    assert any(
        edit["relativePath"].replace("\\", "/") == service_relative
        and edit["startOffset"] == service_source.index("com.acme.app")
        and edit["replacement"] == "com.acme.core"
        for edit in edits
    ), edits

    # Package-declaration edit on the moved subpackage file.
    helper_relative = "src/main/java/com/acme/app/util/Helper.java"
    helper_source = (project_root / "src/main/java/com/acme/app/util/Helper.java").read_text(encoding="utf-8")
    assert any(
        edit["relativePath"].replace("\\", "/") == helper_relative
        and edit["startOffset"] == helper_source.index("com.acme.app.util")
        and edit["replacement"] == "com.acme.core.util"
        for edit in edits
    ), edits

    # Import rewrites in the referencing file: each import's OWNING package span is rewritten to its moved target.
    # com.acme.app.Service -> com.acme.core.Service (owner com.acme.app) and com.acme.app.util.Helper ->
    # com.acme.core.util.Helper (owner com.acme.app.util), so the two replacements are the two mapped owning packages.
    client_relative = "src/main/java/com/acme/client/Client.java"
    client_edits = [edit for edit in edits if edit["relativePath"].replace("\\", "/") == client_relative]
    assert len(client_edits) == 2, edits
    assert {edit["replacement"] for edit in client_edits} == {"com.acme.core", "com.acme.core.util"}, edits


def test_sidecar_move_package_excludes_subpackages_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    # With includeSubpackages=false only the exact source package moves; the util subpackage file is left in place.
    project_root = _stage_fixture(tmp_path)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core", "includeSubpackages": False},
    )

    assert result.get("accepted") is True, result
    operations = file_ops(result["workspaceEdit"])
    moved = {op["relativePath"].replace("\\", "/") for op in operations if op["kind"] == "rename"}
    assert "src/main/java/com/acme/app/Service.java" in moved, operations
    assert "src/main/java/com/acme/app/util/Helper.java" not in moved, operations


def test_sidecar_move_package_refuses_collision_with_existing_target_type(sidecar_jar: Path, tmp_path: Path) -> None:
    # Refusal: the destination package already contains a type whose simple name collides with one being moved.
    project_root = _stage_fixture(tmp_path)
    core_pkg = project_root / "src/main/java/com/acme/core"
    core_pkg.mkdir(parents=True, exist_ok=True)
    (core_pkg / "Service.java").write_text("package com.acme.core;\npublic class Service {}\n", encoding="utf-8")

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_collision", result
