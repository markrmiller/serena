"""Live-sidecar coverage for the V3 ``transformation.*`` workspace protocol (refactor-feature-plan-V3.md §1.1).

Unlike the pure-Python V3 planner unit tests, these boot the real Java sidecar jar and drive the
``transformation.createWorkspace`` / ``preview`` / ``apply`` / ``cancel`` / ``list`` / ``report`` methods end to end via
:class:`~serena.java_refactor_v3.transformation_client.TransformationClient`. They prove that the sidecar — not Python —
now owns composition, runs the authoritative before/after javac validation once over the composed overlay, and serves a
preview-ready, guard-protected workspace. This is the test that closes blocker #1: the transformation protocol is
exercised against a live JVM rather than a stub.
"""

from __future__ import annotations

import contextlib
import json
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.transformation_client import TransformationClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _rename_project(root: Path) -> None:
    """A tiny project whose ``com.acme.app`` package is renamed; ``Client`` references it across packages."""
    _write(
        root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public static final String NAME = \"s\";\n}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/client/Client.java",
        "package com.acme.client;\nimport com.acme.app.Service;\npublic class Client {\n    Service service;\n    String name = Service.NAME;\n}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/extra/Extra.java",
        "package com.acme.extra;\npublic class Extra {}\n",
    )


@contextlib.contextmanager
def _transformation(
    sidecar_jar: Path, project_root: Path, java_command: str = "java", configuration: str = "default"
) -> Iterator[TransformationClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield TransformationClient(client)
    finally:
        client.shutdown()


def _explicit_main_test_model() -> str:
    """An initialize ``configuration`` JSON declaring the conventional ``main`` + ``test`` source sets explicitly.

    Build-tool-less fixtures otherwise collapse ``src/main/java`` and ``src/test/java`` into a single ``main`` source
    set, so the sidecar cannot tell which referrers are tests. Declaring the layout via the documented ``model``
    contract is exactly what a real Gradle/Maven-discovered project supplies, and it lets the impact report honestly
    classify the test-source referrer (``likelyAffectedTests``).
    """
    return json.dumps(
        {
            "model": {
                "modules": [
                    {
                        "project": "root",
                        "sourceSets": [
                            {"name": "main", "srcDirs": ["src/main/java"]},
                            {"name": "test", "srcDirs": ["src/test/java"], "dependsOnProjects": ["root:main"]},
                        ],
                    }
                ]
            }
        }
    )


def _rename_args() -> dict:
    return {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"}


def test_create_workspace_composes_and_validates_rename_package(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args(), goal="rename app -> core")

    assert created.get("accepted") is True, created
    assert created["workspaceId"].startswith("jwt-")
    assert created["status"] == "previewReady"
    # §1.1 stats: Service.java is moved; Client.java is edited (import + qualified reference rewrite).
    stats = created["stats"]
    assert stats["javaFilesMoved"] >= 1, created
    assert stats["javaFilesEdited"] >= 1, created
    assert "rename app -> core" in created["summary"]


def test_preview_returns_javac_validated_composed_edit(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args())
        assert created.get("accepted") is True, created
        preview = tx.preview(created["workspaceId"])

    assert preview.get("accepted") is True, preview
    # The composed after-state was actually compiled by the sidecar (not a placeholder delta).
    assert preview["diagnosticDeltaValidated"] is True, preview
    edit = preview["workspaceEdit"]
    rename_targets = [op["newPath"] for op in edit["fileOperations"] if op["kind"] == "rename"]
    assert any("com/acme/core/Service.java" in target for target in rename_targets), preview
    changed_files = {change["path"] for change in edit["changes"]}
    assert any(path.endswith("Client.java") for path in changed_files), preview


def test_add_operation_preserves_nested_arguments(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args())
        assert created.get("accepted") is True, created
        added = tx.add_operation(
            created["workspaceId"],
            "renamePackage",
            {"oldPackage": "com.acme.extra", "newPackage": "com.acme.renamed"},
        )

    assert added.get("accepted") is True, added
    edit = added["workspaceEdit"]
    rename_targets = [op["newPath"] for op in edit["fileOperations"] if op["kind"] == "rename"]
    assert any("com/acme/renamed/Extra.java" in target for target in rename_targets), added


def test_apply_prepares_then_ack_terminalizes_workspace(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args())
        assert created.get("accepted") is True, created
        workspace_id = created["workspaceId"]

        applied = tx.apply(workspace_id)
        assert applied.get("accepted") is True, applied
        assert applied["workspaceStatus"] == "previewReady"

        acked = tx.ack_apply(workspace_id)
        assert acked.get("accepted") is True, acked
        assert acked["status"] == "applied"

        listed = tx.list()
        listed_status = {entry["workspaceId"]: entry["status"] for entry in listed["workspaces"]}
        assert listed_status[workspace_id] == "applied"

        again = tx.apply(workspace_id)
        assert again.get("accepted") is False, again
        assert again["refusal"]["code"] == "workspace_already_applied"


def test_cancel_evicts_workspace(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args())
        workspace_id = created["workspaceId"]
        assert tx.list()["workspaces"], "workspace should be listed while open"

        cancelled = tx.cancel(workspace_id)
        assert cancelled.get("accepted") is True, cancelled

        after = tx.preview(workspace_id)
        assert after.get("accepted") is False, after
        assert after["refusal"]["code"] == "workspace_not_found"


def test_create_workspace_refuses_unknown_operation(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        result = tx.create_workspace("noSuchOperation", {})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_transformation_operation"


def test_create_workspace_propagates_planner_refusal(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        # No file declares com.acme.absent, so the planner refuses; the workspace surfaces that refusal verbatim.
        result = tx.create_workspace("renamePackage", {"oldPackage": "com.acme.absent", "newPackage": "com.acme.core"})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_not_found"


# ── report: the authoritative §17 five-section impact report (G3) ──────────────────────────────────────────────────


def _rich_rename_project(root: Path) -> None:
    """A package rename whose moved type ``com.acme.app.MyProvider`` is wired by a META-INF/services line AND
    referenced by a test, so the impact report's resource/test sections carry honest non-trivial counts.
    """
    _write(
        root,
        "src/main/java/com/acme/app/MyProvider.java",
        "package com.acme.app;\npublic class MyProvider {\n    public static final String NAME = \"p\";\n}\n",
    )
    # A cross-package main-source referrer (drives callSitesChanged / public-API surface).
    _write(
        root,
        "src/main/java/com/acme/client/Client.java",
        "package com.acme.client;\nimport com.acme.app.MyProvider;\n"
        "public class Client {\n    MyProvider provider;\n    String name = MyProvider.NAME;\n}\n",
    )
    # A test (in its own package, so the moved package is not split across source roots) that references the moved
    # type → likelyAffectedTests is non-empty.
    _write(
        root,
        "src/test/java/com/acme/apptest/MyProviderTest.java",
        "package com.acme.apptest;\nimport com.acme.app.MyProvider;\n"
        "public class MyProviderTest {\n    MyProvider provider = new MyProvider();\n}\n",
    )
    # A ServiceLoader provider line referencing the moved FQN → resourceImpact.serviceLoaderFilesChanged is non-zero.
    _write(
        root,
        "src/main/resources/META-INF/services/com.acme.app.Spi",
        "com.acme.app.MyProvider\n",
    )


def test_report_computes_all_five_sections_with_honest_counts(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """transformation.report computes the real §17 sections (NO computed:false): the rename moves a Java type wired by
    a ServiceLoader provider line and referenced by a test, so the resource/test counts are non-trivial and honest.
    """
    _rich_rename_project(tmp_path)
    with _transformation(
        sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_explicit_main_test_model()
    ) as tx:
        created = tx.create_workspace("renamePackage", _rename_args(), goal="renamePackage")
        assert created.get("accepted") is True, created
        report_envelope = tx.report(created["workspaceId"])

    assert report_envelope.get("accepted") is True, report_envelope
    report = report_envelope["report"]

    # No section may be a skeleton.
    for section in ("summary", "semanticImpact", "resourceImpact", "tests", "warnings"):
        assert section in report, report
    for section in ("semanticImpact", "resourceImpact", "tests"):
        assert report[section].get("computed") is not False, (section, report[section])

    # summary §17 fields, all computed.
    summary = report["summary"]
    assert summary["operation"] == "renamePackage", summary
    assert summary["javaFilesMoved"] >= 1, summary
    assert "filesChanged" in summary and summary["filesChanged"] >= 1, summary
    assert summary["newCompileErrors"] == 0, summary  # the after-state was javac-validated clean at creation
    assert summary["risk"] in {"HIGH", "MEDIUM", "LOW"}, summary

    # semanticImpact: the moved type relocates and is referenced across packages.
    semantic = report["semanticImpact"]
    assert semantic["typesMoved"] >= 1, semantic
    assert semantic["callSitesChanged"] >= 1, semantic
    assert semantic["publicApisChanged"] >= 1, semantic
    assert "overridesAffected" in semantic, semantic

    # resourceImpact: the ServiceLoader provider line is an exact changed entry.
    resource = report["resourceImpact"]
    assert resource["serviceLoaderFilesChanged"] >= 1, resource
    assert "xmlFilesChanged" in resource, resource
    assert "reflectionCandidatesNotChanged" in resource, resource

    # tests: build-model suggested commands + the test that references the moved type.
    tests = report["tests"]
    assert tests["suggestedTestCommands"], tests
    assert any("MyProviderTest" in t for t in tests["likelyAffectedTests"]), tests


def test_report_zero_sections_are_computed_not_skeleton(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """Honesty gate: a no-resource, no-framework, no-test rename yields zeroed sections (computed value 0), NEVER
    computed:false — a plain type honestly produces zero, not a heuristic.
    """
    # A lone package with no resources/tests/framework annotations and no external referrers.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Lonely.java",
        "package com.acme.app;\nclass Lonely {\n}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace("renamePackage", _rename_args(), goal="renamePackage")
        assert created.get("accepted") is True, created
        report_envelope = tx.report(created["workspaceId"])

    assert report_envelope.get("accepted") is True, report_envelope
    report = report_envelope["report"]
    resource = report["resourceImpact"]
    assert resource["serviceLoaderFilesChanged"] == 0, resource
    assert resource["xmlFilesChanged"] == 0, resource
    assert resource["reflectionCandidatesNotChanged"] == 0, resource
    assert report["tests"]["likelyAffectedTests"] == [], report["tests"]
    # still computed, not a skeleton:
    assert report["resourceImpact"].get("computed") is not False, report
    assert report["tests"].get("computed") is not False, report


def test_report_refuses_unknown_workspace(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _rename_project(tmp_path)
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        result = tx.report("jwt-does-not-exist")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "workspace_not_found", result
