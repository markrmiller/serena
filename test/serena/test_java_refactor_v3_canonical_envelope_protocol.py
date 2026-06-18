"""Live-sidecar coverage for the V3 canonical success envelope (refactor-feature-plan-V3.md §1.1 + §14.3; blocker B13).

Before B13 the dedicated V3 ops disagreed on their accepted-result shape: single-op planners emitted ``stats`` as
``{editCount, ...}`` while ``transformation.*`` emitted ``stats`` as the §1.1 ``{javaFilesMoved, ...}`` shape — the same
key carrying two schemas — and none carried the §14.3 ``risk`` classification the plan's acceptance requires. These tests
boot the real sidecar and prove that every accepted V3 result now carries an identically-shaped ``impact`` summary and a
``risk`` field, BOTH computed in the sidecar from the actual ``workspaceEdit`` — and that ``risk`` is real, flipping from
``safe`` (a clean in-place .java edit) to ``needs_review`` (an edit that deletes a file and rewrites a resource).
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.conversions_client import ConversionsClient
from serena.java_refactor_v3.deletion_client import DeletionClient
from serena.java_refactor_v3.transformation_client import TransformationClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

_IMPACT_KEYS = {
    "javaFilesMoved",
    "javaFilesEdited",
    "resourceFilesEdited",
    "buildFilesEdited",
    "textEdits",
    "fileOperations",
}


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _client(sidecar_jar: Path, project_root: Path, java_command: str) -> Iterator[JavaRefactorClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield client
    finally:
        client.shutdown()


def _assert_canonical_impact(result: dict[str, Any]) -> dict[str, Any]:
    """Every accepted V3 envelope carries the identically-shaped §1.1 impact summary with integer counts."""
    assert "impact" in result, result
    impact = result["impact"]
    assert set(impact) == _IMPACT_KEYS, impact
    assert all(isinstance(value, int) for value in impact.values()), impact
    return impact


def test_clean_inplace_edit_is_safe(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # An anonymous-class → lambda conversion edits a single .java file in place: no resource/build files, no file
    # operations, javac-validated and warning-free, so the sidecar classifies it as safe.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    public Runnable make() {\n"
        "        return new Runnable() {\n"
        "            public void run() {\n"
        "                System.out.println(1);\n"
        "            }\n"
        "        };\n"
        "    }\n"
        "}\n",
    )
    with _client(sidecar_jar, tmp_path, sidecar_java_cmd) as client:
        result = ConversionsClient(client).anonymous_to_lambda("src/main/java/com/acme/Main.java", 4)

    assert result.get("accepted") is True, result
    impact = _assert_canonical_impact(result)
    assert impact["javaFilesEdited"] >= 1, result
    assert impact["textEdits"] >= 1, result
    assert impact["resourceFilesEdited"] == 0, result
    assert impact["buildFilesEdited"] == 0, result
    assert impact["fileOperations"] == 0, result
    assert result["risk"] == "safe", result


def test_resource_and_delete_edit_is_needs_review(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Deleting a ServiceLoader provider deletes its .java file AND rewrites the META-INF/services resource. Per §24 the
    # plain delete is SAFE on its own; it is the resource touch that forces needs_review here.
    _write(
        tmp_path,
        "src/main/java/com/acme/spi/Greeter.java",
        "package com.acme.spi;\npublic interface Greeter {\n    String greet();\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/spi/ActiveGreeter.java",
        "package com.acme.spi;\npublic class ActiveGreeter implements Greeter {\n"
        "    public String greet() { return \"hi\"; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/spi/LegacyGreeter.java",
        "package com.acme.spi;\nclass LegacyGreeter implements Greeter {\n"
        "    public String greet() { return \"legacy\"; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/resources/META-INF/services/com.acme.spi.Greeter",
        "com.acme.spi.ActiveGreeter\ncom.acme.spi.LegacyGreeter\n",
    )
    with _client(sidecar_jar, tmp_path, sidecar_java_cmd) as client:
        result = DeletionClient(client).propagate_safe_delete(["com.acme.spi.LegacyGreeter"])

    assert result.get("accepted") is True, result
    impact = _assert_canonical_impact(result)
    assert impact["resourceFilesEdited"] >= 1, result
    assert impact["fileOperations"] >= 1, result
    assert result["risk"] == "needs_review", result


def test_transformation_envelopes_are_canonical(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The transformation.* path builds its own envelope (it bypasses ResponseBuilder). createWorkspace carries the §1.1
    # stats summary; preview carries the composed workspaceEdit. Both must now carry the SAME canonical impact + risk.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public static final String NAME = \"s\";\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/client/Client.java",
        "package com.acme.client;\nimport com.acme.app.Service;\n"
        "public class Client {\n    Service service;\n    String name = Service.NAME;\n}\n",
    )
    args = {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"}
    with _client(sidecar_jar, tmp_path, sidecar_java_cmd) as client:
        tx = TransformationClient(client)
        created = tx.create_workspace("renamePackage", args, goal="rename app -> core")
        assert created.get("accepted") is True, created
        preview = tx.preview(created["workspaceId"])

    created_impact = _assert_canonical_impact(created)
    assert created_impact["javaFilesMoved"] >= 1, created
    assert created["risk"] in {"safe", "needs_review"}, created

    assert preview.get("accepted") is True, preview
    preview_impact = _assert_canonical_impact(preview)
    assert preview_impact["javaFilesMoved"] >= 1, preview
    assert preview["risk"] in {"safe", "needs_review"}, preview
