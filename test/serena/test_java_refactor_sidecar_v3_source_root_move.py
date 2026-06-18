"""Sidecar-backed tests for the V3 ``moveSourceRoot`` operation (G003 / JavaMoveSourceRootTool).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the JSON-lines preview harness. ``moveSourceRoot`` relocates Java source files from one configured source root to ANOTHER
configured source root while keeping every moved file's package declaration unchanged, so it proves a distinct contract
from ``movePackage``: the accepted plan carries ONLY file move operations and NO text edits (fully-qualified names and
imports are deliberately left untouched), yet it still passes through the central ``PreviewDiagnosticValidator`` (a real
javac before/after delta, not a placeholder). An optional ``packages`` restriction scopes the move; a destination
collision is refused with ``package_collision``.

The project is generated in a tmp dir with an EXPLICIT two-source-root configuration (no build tool) so the model
reports both ``src/main/java`` and ``src/test/java`` as configured roots of a single merged source set, exactly the
union ``moveSourceRoot`` operates across.
"""

import json
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import (
    file_ops,
    sidecar_jar,
    text_edits,
)

# Explicit config: both roots belong to one merged "main" source set, so a file moved between them stays on the same
# compile path and ``allowIncompleteAnalysis`` keeps the harness hermetic without a build tool.
_TWO_ROOT_CONFIG = json.dumps(
    {"buildToolMode": "explicit", "sourceRoots": ["src/main/java", "src/test/java"], "allowIncompleteAnalysis": True}
)


def _write_two_root_project(project_root: Path) -> None:
    main = project_root / "src/main/java"
    test = project_root / "src/test/java"
    (main / "com/acme/app").mkdir(parents=True)
    (main / "com/acme/app/util").mkdir(parents=True)
    (main / "com/acme/client").mkdir(parents=True)
    (test / "com/acme/keep").mkdir(parents=True)
    (main / "com/acme/app/Service.java").write_text(
        "package com.acme.app;\npublic class Service {\n    public int value() { return 42; }\n}\n", encoding="utf-8"
    )
    (main / "com/acme/app/util/Helper.java").write_text(
        "package com.acme.app.util;\npublic class Helper {\n    public int twice(int n) { return n * 2; }\n}\n",
        encoding="utf-8",
    )
    (main / "com/acme/client/Client.java").write_text(
        "package com.acme.client;\n"
        "import com.acme.app.Service;\n"
        "import com.acme.app.util.Helper;\n"
        "public class Client {\n"
        "    int run() { return new Service().value() + new Helper().twice(2); }\n"
        "}\n",
        encoding="utf-8",
    )
    # Keep.java keeps src/test/java an existing, non-empty configured root that must NOT be moved.
    (test / "com/acme/keep/Keep.java").write_text(
        "package com.acme.keep;\npublic class Keep {}\n", encoding="utf-8"
    )


def _preview(sidecar_jar: Path, project_root: Path, params: dict) -> dict:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=_TWO_ROOT_CONFIG))
        return client.preview("moveSourceRoot", params)
    finally:
        client.shutdown()


def test_sidecar_move_source_root_relocates_files_without_text_edits(sidecar_jar: Path, tmp_path: Path) -> None:
    # Happy path: moving every package under src/main/java into src/test/java relocates all three source files WITHOUT
    # changing any package declaration, so the plan carries file moves only (zero text edits) yet still passes through
    # the central PreviewDiagnosticValidator (diagnosticDeltaValidated true) because the moved files stay on the merged
    # source set's compile path and continue to resolve.
    project_root = tmp_path / "move_source_root_basic"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/test/java"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    operations = file_ops(result["workspaceEdit"])
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in operations
        if op["kind"] == "rename"
    }
    assert renames == {
        ("src/main/java/com/acme/app/Service.java", "src/test/java/com/acme/app/Service.java"),
        ("src/main/java/com/acme/app/util/Helper.java", "src/test/java/com/acme/app/util/Helper.java"),
        ("src/main/java/com/acme/client/Client.java", "src/test/java/com/acme/client/Client.java"),
    }, operations

    # The defining contract: no package declarations or references are rewritten, so there are NO text edits at all.
    assert text_edits(result["workspaceEdit"]) == [], result["workspaceEdit"]


def test_sidecar_move_source_root_restricts_to_requested_packages(sidecar_jar: Path, tmp_path: Path) -> None:
    # With packages=["com.acme.app"] and subpackages included, only Service (com.acme.app) and Helper (com.acme.app.util)
    # move; the unrelated com.acme.client package is left under src/main/java.
    project_root = tmp_path / "move_source_root_scoped"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {
            "sourceRoot": "src/main/java",
            "targetSourceRoot": "src/test/java",
            "packages": ["com.acme.app"],
            "includeSubpackages": True,
        },
    )

    assert result.get("accepted") is True, result
    moved = {op["relativePath"].replace("\\", "/") for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"}
    assert moved == {
        "src/main/java/com/acme/app/Service.java",
        "src/main/java/com/acme/app/util/Helper.java",
    }, moved


def test_sidecar_move_source_root_excludes_subpackages_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    # includeSubpackages=false moves only the exact com.acme.app package; the com.acme.app.util subpackage stays put.
    project_root = tmp_path / "move_source_root_exact"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {
            "sourceRoot": "src/main/java",
            "targetSourceRoot": "src/test/java",
            "packages": ["com.acme.app"],
            "includeSubpackages": False,
        },
    )

    assert result.get("accepted") is True, result
    moved = {op["relativePath"].replace("\\", "/") for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"}
    assert moved == {"src/main/java/com/acme/app/Service.java"}, moved


def test_sidecar_move_source_root_refuses_destination_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    # Refusal: the target source root already contains a file at a destination path the move would occupy.
    project_root = tmp_path / "move_source_root_collision"
    _write_two_root_project(project_root)
    existing = project_root / "src/test/java/com/acme/app"
    existing.mkdir(parents=True)
    (existing / "Service.java").write_text(
        "package com.acme.app;\npublic class Service { public int value() { return 0; } }\n", encoding="utf-8"
    )

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/test/java"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_collision", result


def test_sidecar_move_source_root_refuses_unknown_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # Refusal: a source root that is not a configured root of the project is rejected rather than scanned blindly.
    project_root = tmp_path / "move_source_root_unknown"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/generated", "targetSourceRoot": "src/test/java"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "source_root_not_found", result


def test_sidecar_move_source_root_preserve_true_keeps_declarations_and_moves_files(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §6.2 step 5: with preservePackageNames=True (the explicit default behavior) every matched file relocates from
    # src/main/java to src/test/java WITHOUT any package-declaration or reference rewrite, so the plan carries file
    # moves only and zero text edits -- proving preserve mode is a pure physical relocation.
    project_root = tmp_path / "move_source_root_preserve_true"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {
            "sourceRoot": "src/main/java",
            "targetSourceRoot": "src/test/java",
            "preservePackageNames": True,
        },
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert renames == {
        ("src/main/java/com/acme/app/Service.java", "src/test/java/com/acme/app/Service.java"),
        ("src/main/java/com/acme/app/util/Helper.java", "src/test/java/com/acme/app/util/Helper.java"),
        ("src/main/java/com/acme/client/Client.java", "src/test/java/com/acme/client/Client.java"),
    }, renames
    # Defining contract of preserve mode: declarations are untouched, so there are NO text edits at all.
    assert text_edits(result["workspaceEdit"]) == [], result["workspaceEdit"]


def _write_mismatched_declaration_project(project_root: Path) -> None:
    # A project whose moved file's declared package does NOT match its on-disk directory: Widget.java lives under
    # com/acme/app but declares package com.acme.legacy, and Caller.java references it by that declared name. javac
    # compiles explicitly-listed files regardless of directory, so both before- and after-states are valid; this is the
    # one case where preservePackageNames=false observably diverges from preserve mode.
    main = project_root / "src/main/java"
    test = project_root / "src/test/java"
    (main / "com/acme/app").mkdir(parents=True)
    (test / "com/acme/keep").mkdir(parents=True)
    (main / "com/acme/app/Widget.java").write_text(
        "package com.acme.legacy;\npublic class Widget {\n    public int size() { return 7; }\n}\n", encoding="utf-8"
    )
    (main / "com/acme/app/Caller.java").write_text(
        "package com.acme.legacy;\n"
        "public class Caller {\n"
        "    int run() { return new Widget().size(); }\n"
        "}\n",
        encoding="utf-8",
    )
    (test / "com/acme/keep/Keep.java").write_text(
        "package com.acme.keep;\npublic class Keep {}\n", encoding="utf-8"
    )


def test_sidecar_move_source_root_preserve_false_recomputes_package_from_directory(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §6.2 step 6: with preservePackageNames=False the new package is computed from the directory mapping -- Widget.java
    # and Caller.java live under com/acme/app, so their declared package com.acme.legacy is recomputed to com.acme.app to
    # match the directory, and the existing package-rename logic rewrites the package declarations (and lands the files
    # under the directory implied by the corrected package beneath the target root). This is the observable divergence
    # from preserve mode, which would leave the declarations untouched.
    project_root = tmp_path / "move_source_root_preserve_false"
    _write_mismatched_declaration_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {
            "sourceRoot": "src/main/java",
            "targetSourceRoot": "src/test/java",
            "preservePackageNames": False,
        },
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    # The package declarations are recomputed from the directory: com.acme.legacy -> com.acme.app.
    package_edits = [
        edit for edit in text_edits(result["workspaceEdit"]) if edit["kind"] == "PACKAGE_DECLARATION"
    ]
    assert package_edits, text_edits(result["workspaceEdit"])
    assert all(edit["replacement"] == "com.acme.app" for edit in package_edits), package_edits
    edited_files = {edit["relativePath"].replace("\\", "/") for edit in package_edits}
    assert edited_files == {
        "src/main/java/com/acme/app/Widget.java",
        "src/main/java/com/acme/app/Caller.java",
    }, edited_files

    # The files land under the directory implied by the corrected package beneath the target root.
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert renames == {
        ("src/main/java/com/acme/app/Widget.java", "src/test/java/com/acme/app/Widget.java"),
        ("src/main/java/com/acme/app/Caller.java", "src/test/java/com/acme/app/Caller.java"),
    }, renames


def test_sidecar_move_source_root_preserve_false_is_identity_for_conventional_layout(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §6.2 step 6 corollary: when every moved file already declares the package implied by its directory, recomputing
    # from the directory mapping yields the same name, so preservePackageNames=False produces NO package-declaration
    # edits and only relocates the files -- matching preserve mode for a conventional codebase (documented behavior, not
    # a bug). The two-root project is fully conventional, so false mode emits zero text edits here.
    project_root = tmp_path / "move_source_root_false_identity"
    _write_two_root_project(project_root)

    result = _preview(
        sidecar_jar,
        project_root,
        {
            "sourceRoot": "src/main/java",
            "targetSourceRoot": "src/test/java",
            "preservePackageNames": False,
        },
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert text_edits(result["workspaceEdit"]) == [], result["workspaceEdit"]
    moved = {op["relativePath"].replace("\\", "/") for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"}
    assert moved == {
        "src/main/java/com/acme/app/Service.java",
        "src/main/java/com/acme/app/util/Helper.java",
        "src/main/java/com/acme/client/Client.java",
    }, moved
