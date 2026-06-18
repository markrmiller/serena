"""Sidecar-backed tests for the V3 ``renamePackage`` operation (G003).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the same JSON-lines preview harness the other ``test_java_refactor_sidecar_*`` modules use. They prove the Python MCP
surface's underlying operation end-to-end: an accepted package rename carries the file rename, the package-declaration
edit, and the cross-package import rewrite, AND a javac-validated before/after diagnostic delta (it went through the
central ``PreviewDiagnosticValidator``, not a placeholder); a colliding target is refused with ``package_collision``.
"""

import shutil
from pathlib import Path

from test.serena._java_refactor_sidecar_helpers import (
    _preview_op,
    file_ops,
    sidecar_jar,
    text_edits,
)

FIXTURE_ROOT = Path(__file__).parent.parent / "resources/repos/java_refactor_v3/package_rename_basic"


def _stage_fixture(tmp_path: Path) -> Path:
    """Copies the read-only fixture repo into a writable tmp project root for the sidecar to operate on."""
    project_root = tmp_path / "package_rename_basic"
    shutil.copytree(FIXTURE_ROOT, project_root)
    return project_root


def test_sidecar_rename_package_basic_moves_file_and_rewrites_references(sidecar_jar: Path, tmp_path: Path) -> None:
    # Happy path: renaming com.acme.app -> com.acme.core moves Service.java app/ -> core/, rewrites its package
    # declaration, rewrites the cross-package import in Client.java, and the accepted result carries the REAL javac
    # diagnostic delta (diagnosticDeltaValidated true) proving it passed through PreviewDiagnosticValidator.
    project_root = _stage_fixture(tmp_path)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result

    # The result went through the central preview diagnostic validator: the marker is true and the diagnostics field is
    # present (an empty list here is the validated CLEAN delta, byte-distinct from the unvalidated placeholder which the
    # sidecar refuses to surface).
    assert result.get("diagnosticDeltaValidated") is True, result
    assert "diagnostics" in result, result
    assert isinstance(result["diagnostics"], list), result

    # File operation: Service.java moves from the old package directory to the new one.
    operations = file_ops(result["workspaceEdit"])
    renames = [op for op in operations if op["kind"] == "rename"]
    assert any(
        op["relativePath"].replace("\\", "/") == "src/main/java/com/acme/app/Service.java"
        and op["newRelativePath"].replace("\\", "/") == "src/main/java/com/acme/core/Service.java"
        for op in renames
    ), operations

    edits = text_edits(result["workspaceEdit"])

    # Package-declaration edit on the moved file rewrites the old package qualifier to the new one.
    service_relative = "src/main/java/com/acme/app/Service.java"
    service_source = (project_root / "src/main/java/com/acme/app/Service.java").read_text(encoding="utf-8")
    package_offset = service_source.index("com.acme.app")
    package_edits = [
        edit
        for edit in edits
        if edit["relativePath"].replace("\\", "/") == service_relative
        and edit["startOffset"] == package_offset
        and edit["replacement"] == "com.acme.core"
    ]
    assert len(package_edits) == 1, edits

    # Import rewrite in the referencing file: the cross-package import qualifier is updated to the new package.
    client_relative = "src/main/java/com/acme/client/Client.java"
    client_source = (project_root / "src/main/java/com/acme/client/Client.java").read_text(encoding="utf-8")
    import_offset = client_source.index("com.acme.app", client_source.index("import"))
    import_edits = [
        edit
        for edit in edits
        if edit["relativePath"].replace("\\", "/") == client_relative
        and edit["startOffset"] == import_offset
        and edit["replacement"] == "com.acme.core"
    ]
    assert len(import_edits) == 1, edits


def test_sidecar_rename_package_refuses_collision_with_existing_target_type(sidecar_jar: Path, tmp_path: Path) -> None:
    # Refusal: the destination package already contains a type whose simple name collides with one being moved. Staging
    # a same-named Service into com.acme.core makes the rename of com.acme.app -> com.acme.core a package_collision.
    project_root = _stage_fixture(tmp_path)
    core_pkg = project_root / "src/main/java/com/acme/core"
    core_pkg.mkdir(parents=True, exist_ok=True)
    (core_pkg / "Service.java").write_text("package com.acme.core;\npublic class Service {}\n", encoding="utf-8")

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_collision", result


def _stage_nested_fixture(tmp_path: Path) -> Path:
    """Stages the base fixture and adds a ``com.acme.app.util`` subpackage plus a client that imports both packages.

    This gives a two-level package tree (``com.acme.app`` and its subpackage ``com.acme.app.util``) with a referencing
    file outside the renamed tree, so a rename can be checked for both subpackage inclusion and exclusion.
    """
    project_root = _stage_fixture(tmp_path)
    util_pkg = project_root / "src/main/java/com/acme/app/util"
    util_pkg.mkdir(parents=True, exist_ok=True)
    (util_pkg / "Helper.java").write_text(
        "package com.acme.app.util;\n\npublic class Helper {\n    public int help() {\n        return 7;\n    }\n}\n",
        encoding="utf-8",
    )
    (project_root / "src/main/java/com/acme/client/Client.java").write_text(
        "package com.acme.client;\n\n"
        "import com.acme.app.Service;\n"
        "import com.acme.app.util.Helper;\n\n"
        "public class Client {\n"
        "    public int run() {\n"
        "        return new Service().value() + new Helper().help();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    return project_root


def test_sidecar_rename_package_includes_subpackages_by_default(sidecar_jar: Path, tmp_path: Path) -> None:
    # includeSubpackages defaults true: renaming com.acme.app -> com.acme.core moves BOTH the package's own type
    # (Service: app/ -> core/) AND the nested subpackage's type (Helper: app/util/ -> core/util/), rewrites both package
    # declarations, and rewrites both cross-package imports in Client.java, all under a validated javac diagnostic delta.
    project_root = _stage_nested_fixture(tmp_path)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    renames = [op for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"]
    renamed = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/")) for op in renames
    }
    assert (
        "src/main/java/com/acme/app/Service.java",
        "src/main/java/com/acme/core/Service.java",
    ) in renamed, renames
    assert (
        "src/main/java/com/acme/app/util/Helper.java",
        "src/main/java/com/acme/core/util/Helper.java",
    ) in renamed, renames

    edits = text_edits(result["workspaceEdit"])

    # The subpackage type's package declaration is rewritten preserving the subpackage suffix.
    helper_relative = "src/main/java/com/acme/app/util/Helper.java"
    helper_pkg_edits = [
        edit
        for edit in edits
        if edit["relativePath"].replace("\\", "/") == helper_relative
        and edit["replacement"] == "com.acme.core.util"
    ]
    assert len(helper_pkg_edits) == 1, edits

    # Both imports in the referencing file are rewritten: the exact package and the subpackage.
    client_relative = "src/main/java/com/acme/client/Client.java"
    client_replacements = {
        edit["replacement"]
        for edit in edits
        if edit["relativePath"].replace("\\", "/") == client_relative
    }
    assert "com.acme.core" in client_replacements, edits
    assert "com.acme.core.util" in client_replacements, edits


def test_sidecar_rename_package_excludes_subpackages_when_disabled(sidecar_jar: Path, tmp_path: Path) -> None:
    # includeSubpackages=false: only the exact package com.acme.app is renamed. Service moves app/ -> core/ and its
    # Client import is rewritten, but the nested com.acme.app.util subpackage (Helper) is left in place and its import is
    # untouched.
    project_root = _stage_nested_fixture(tmp_path)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core", "includeSubpackages": False},
    )

    assert result.get("accepted") is True, result

    renames = [op for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"]
    renamed_old = {op["relativePath"].replace("\\", "/") for op in renames}
    assert "src/main/java/com/acme/app/Service.java" in renamed_old, renames
    # The subpackage type is NOT moved.
    assert "src/main/java/com/acme/app/util/Helper.java" not in renamed_old, renames

    edits = text_edits(result["workspaceEdit"])
    replacements = {edit["replacement"] for edit in edits}
    # The exact-package qualifier is rewritten somewhere (Service decl / Client import) ...
    assert "com.acme.core" in replacements, edits
    # ... but the subpackage qualifier is never rewritten.
    assert "com.acme.core.util" not in replacements, edits
