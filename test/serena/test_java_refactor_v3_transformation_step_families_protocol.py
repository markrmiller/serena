"""Live-sidecar coverage for the V3 ``transformation.*`` workspace step-planning families (Wave 2 / F-WS2).

These boot the real Java sidecar jar and drive ``transformation.createWorkspace`` for each operation family the
transformation workspace can now compose into a ``TransformationStep`` (conversions, deep-inline, class-ops, and
propagating-delete). They prove the sidecar's ``planTransformationStep`` switch routes each operation to the matching
V3 planner, builds a step from the same structured plan the standalone path serializes, and serves a preview-ready,
javac-validated composed workspace.

The final two tests exercise :class:`~io.serena.javarefactor.v3.transformation.EditComposer` end to end: two
non-overlapping steps compose into a single accepted workspace (one javac validation over the merged overlay), while two
steps that rewrite overlapping spans of the same file are refused with ``workspace_edit_conflict``.
"""

from __future__ import annotations

import contextlib
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


@contextlib.contextmanager
def _transformation(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[TransformationClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield TransformationClient(client)
    finally:
        client.shutdown()


def _assert_preview_ready(created: dict) -> None:
    assert created.get("accepted") is True, created
    assert created["workspaceId"].startswith("jwt-"), created
    assert created["status"] == "previewReady", created


# ── conversions: anonymousToLambda ───────────────────────────────────────────────────────────────────────────────


def test_step_anonymous_to_lambda(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A bare Runnable anonymous class with a single-statement body composes cleanly into a lambda step.
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
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "anonymousToLambda", {"relativePath": "src/main/java/com/acme/Main.java", "line": 4}
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── conversions: lambdaToMethodReference ─────────────────────────────────────────────────────────────────────────


def test_step_lambda_to_method_reference(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # `s -> Integer.parseInt(s)` forwards its single parameter untransformed → `Integer::parseInt`.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "import java.util.function.Function;\n"
        "public class Main {\n"
        "    public Function<String, Integer> make() {\n"
        "        return s -> Integer.parseInt(s);\n"
        "    }\n"
        "}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "lambdaToMethodReference", {"relativePath": "src/main/java/com/acme/Main.java", "line": 5}
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── inline: deepInlineMethod ─────────────────────────────────────────────────────────────────────────────────────


def test_step_deep_inline_method(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # log() is a private straight-line method called as a statement; inlining (with delete) composes into one step.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    private void log(String msg) {\n"
        "        String prefix = \"[x] \";\n"
        "        System.out.println(prefix + msg);\n"
        "    }\n"
        "    void run() {\n"
        "        log(\"hi\");\n"
        "    }\n"
        "}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "deepInlineMethod",
            {"relativePath": "src/main/java/com/acme/Main.java", "line": 3, "deleteMethod": True},
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── class-ops: extractClass ──────────────────────────────────────────────────────────────────────────────────────


def test_step_extract_class(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # rate + tax(double) form a self-contained collaborator pulled into a new TaxCalc.java; PriceService is rewritten.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    private String label = \"p\";\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "    String label() { return label; }\n"
        "}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "extractClass",
            {
                "relativePath": "src/main/java/com/acme/PriceService.java",
                "newClassName": "TaxCalc",
                "members": ["field:rate", "method:tax(double)"],
            },
        )
    _assert_preview_ready(created)
    # A brand-new collaborator file is created (javaFilesMoved counts file operations); PriceService is edited.
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── class-ops: extractSuperclass ─────────────────────────────────────────────────────────────────────────────────


def test_step_extract_superclass(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # tick() is common to both sibling classes; hoisting it synthesizes a Base superclass and rewrites both children.
    _write(
        tmp_path,
        "src/main/java/com/acme/Alpha.java",
        "package com.acme;\npublic class Alpha {\n    int tick() { return 1; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Beta.java",
        "package com.acme;\npublic class Beta {\n    int tick() { return 1; }\n}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "extractSuperclass",
            {
                "classes": [
                    "src/main/java/com/acme/Alpha.java",
                    "src/main/java/com/acme/Beta.java",
                ],
                "superclassName": "Base",
                "members": ["method:tick()"],
            },
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── class-ops: replaceInheritanceWithDelegation ──────────────────────────────────────────────────────────────────


def test_step_replace_inheritance_with_delegation(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Derived extends Base only to reuse greet(); §10 drops the extends and forwards through a delegate field.
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public String greet() { return \"hi\"; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Derived.java",
        "package com.acme;\npublic class Derived extends Base {\n}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "replaceInheritanceWithDelegation",
            {"relativePath": "src/main/java/com/acme/Derived.java", "confirmPublicApiChange": True},
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── propagating delete: propagateSafeDelete ──────────────────────────────────────────────────────────────────────


def test_step_propagate_safe_delete(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # legacyCompute (package-private root) cascades into the private helper it solely uses; run() keeps the class alive.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\n"
        "public class Service {\n"
        "    public int run() { return 1; }\n"
        "    int legacyCompute() { return helper() + 1; }\n"
        "    private int helper() { return 41; }\n"
        "}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            "propagateSafeDelete",
            {"roots": ["com.acme.app.Service#legacyCompute()"]},
        )
    _assert_preview_ready(created)
    assert created["stats"]["javaFilesEdited"] >= 1, created


# ── EditComposer: two non-overlapping steps compose into one accepted workspace ──────────────────────────────────


def test_compose_extract_class_with_rename_package_accepted(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # extractClass rewrites com.acme.PriceService (and creates TaxCalc.java); renamePackage moves an independent
    # com.acme.legacy package. The two steps touch disjoint files, so EditComposer merges them and the sidecar runs a
    # single javac validation over the composed overlay.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    private String label = \"p\";\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "    String label() { return label; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/legacy/Helper.java",
        "package com.acme.legacy;\npublic class Helper {\n    public int value() { return 7; }\n}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        created = tx.create_workspace(
            operations=[
                {
                    "operation": "extractClass",
                    "arguments": {
                        "relativePath": "src/main/java/com/acme/PriceService.java",
                        "newClassName": "TaxCalc",
                        "members": ["field:rate", "method:tax(double)"],
                    },
                },
                {
                    "operation": "renamePackage",
                    "arguments": {"oldPackage": "com.acme.legacy", "newPackage": "com.acme.archived"},
                },
            ]
        )
    _assert_preview_ready(created)
    # Both step families landed: an edit (PriceService) and a move (Helper.java relocated by the rename).
    stats = created["stats"]
    assert stats["javaFilesEdited"] >= 1, created
    assert stats["javaFilesMoved"] >= 1, created


# ── EditComposer: two steps rewriting overlapping spans of the same file are refused ─────────────────────────────


def test_compose_overlapping_same_file_steps_conflict(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Two extractClass steps over the SAME source file both move the SAME `tax(double)` member (into TaxCalc and into
    # AltCalc), so both rewrite the identical declaration span of PriceService. EditComposer detects the true overlap on
    # the shared `[start,end)` range and refuses with workspace_edit_conflict rather than producing a corrupt merge.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    private final int qty = 3;\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "    int units() { return qty; }\n"
        "}\n",
    )
    with _transformation(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as tx:
        result = tx.create_workspace(
            operations=[
                {
                    "operation": "extractClass",
                    "arguments": {
                        "relativePath": "src/main/java/com/acme/PriceService.java",
                        "newClassName": "TaxCalc",
                        "members": ["field:rate", "method:tax(double)"],
                    },
                },
                {
                    "operation": "extractClass",
                    "arguments": {
                        "relativePath": "src/main/java/com/acme/PriceService.java",
                        "newClassName": "AltCalc",
                        "members": ["field:rate", "method:tax(double)"],
                    },
                },
            ]
        )
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "workspace_edit_conflict", result
