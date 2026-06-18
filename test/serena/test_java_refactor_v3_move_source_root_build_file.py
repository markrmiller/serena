"""Live-sidecar coverage for the moveSourceRoot build-file rewrite (refactor-feature-plan-V3.md §6.3; blocker B4).

``moveSourceRoot`` previously refused any target that was not already a configured source root with
``BUILD_FILE_UPDATE_REQUIRED``, whose message dangled "or enable build file rewrite" — a capability that did not exist
(no parameter, no implementation). B4 makes that honest: ``rewriteBuildFiles`` is a real parameter, and when it is true
the sidecar emits one ADDITIVE Gradle build-file edit registering the target directory as a ``srcDir`` of the source set
that owns the source root, so the relocated files actually land on a directory the build compiles. The default is still
no build-file edits (the ``BUILD_FILE_UPDATE_REQUIRED`` refusal). When enabled, a Gradle module gets one additive
``srcDir`` registration and a Maven module gets an additive ``build-helper-maven-plugin`` ``add-source`` binding (B06,
the Maven analogue of the Gradle ``srcDir`` edit); ``build_file_rewrite_unsupported`` is reserved for genuinely
unsupported shapes (no build file at all, a malformed POM, or an existing build-helper execution that cannot be safely
extended) rather than producing an edit the build cannot use.

These boot the real sidecar against an explicit single-source-root project (so the target root is genuinely
unconfigured) with a real build file on disk, and prove: (1) the refusal when the rewrite is disabled; (2) a real,
additive Groovy build.gradle edit when enabled, classified as a build-file edit that makes the result ``needs_review``;
(3) the ``build.gradle.kts`` Kotlin-DSL variant; (4) the additive Maven build-helper ``add-source`` edit; (5) the
residual ``build_file_rewrite_unsupported`` refusals for a malformed POM and for a module with no build file at all.
"""

from __future__ import annotations

import json
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import (
    _resolve_sidecar_java,
    file_ops,
    text_edits,
)

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

# Explicit config with a SINGLE configured source root, so ``src/main/java11`` is genuinely not part of the model and
# the move into it requires a build-file registration. allowIncompleteAnalysis keeps the harness hermetic (no build).
_ONE_ROOT_CONFIG = json.dumps(
    {"buildToolMode": "explicit", "sourceRoots": ["src/main/java"], "allowIncompleteAnalysis": True}
)


def _write_project(project_root: Path, build_file_name: str | None) -> None:
    main = project_root / "src/main/java/com/acme/app"
    main.mkdir(parents=True)
    (main / "Service.java").write_text(
        "package com.acme.app;\npublic class Service {\n    public int value() { return 42; }\n}\n", encoding="utf-8"
    )
    (main / "Client.java").write_text(
        "package com.acme.app;\npublic class Client {\n    int run() { return new Service().value(); }\n}\n",
        encoding="utf-8",
    )
    if build_file_name is not None:
        contents = {
            "build.gradle": "plugins { id 'java' }\n",
            "build.gradle.kts": "plugins { java }\n",
            "pom.xml": "<project><modelVersion>4.0.0</modelVersion></project>\n",
        }[build_file_name]
        (project_root / build_file_name).write_text(contents, encoding="utf-8")


def _preview(sidecar_jar: Path, project_root: Path, params: dict) -> dict:
    client = JavaRefactorClient(sidecar_jar, java_command=_resolve_sidecar_java())
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(project_root), configuration=_ONE_ROOT_CONFIG)
        )
        return client.preview("moveSourceRoot", params)
    finally:
        client.shutdown()


def test_move_source_root_refuses_unconfigured_target_without_rewrite_flag(sidecar_jar: Path, tmp_path: Path) -> None:
    # Default (rewriteBuildFiles omitted == false, §6.3): a target that is not a configured source root is refused with
    # the coded signal instead of silently relocating files into a directory the build never compiles.
    project_root = tmp_path / "no_rewrite"
    _write_project(project_root, "build.gradle")

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "BUILD_FILE_UPDATE_REQUIRED", result
    # The message must reference the real opt-in, not a phantom capability.
    assert "rewriteBuildFiles=true" in result["refusal"]["message"], result


def test_move_source_root_rewrites_groovy_build_file_when_enabled(sidecar_jar: Path, tmp_path: Path) -> None:
    # rewriteBuildFiles=true: the move is accepted and carries ONE additive Groovy build-file edit registering the
    # target directory under the owning ("main") source set, alongside the file renames. Because every moved file keeps
    # its package declaration, the renames are pure relocations and no Java text edits are produced.
    project_root = tmp_path / "groovy_rewrite"
    _write_project(project_root, "build.gradle")
    build_gradle_len = len((project_root / "build.gradle").read_text(encoding="utf-8"))

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11", "rewriteBuildFiles": True},
    )

    assert result.get("accepted") is True, result

    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert renames == {
        ("src/main/java/com/acme/app/Service.java", "src/main/java11/com/acme/app/Service.java"),
        ("src/main/java/com/acme/app/Client.java", "src/main/java11/com/acme/app/Client.java"),
    }, renames

    edits = text_edits(result["workspaceEdit"])
    assert len(edits) == 1, edits
    edit = edits[0]
    assert edit["relativePath"].replace("\\", "/") == "build.gradle", edit
    # Additive append at end-of-file (a zero-width insertion): never rewrites existing build configuration.
    assert edit["startOffset"] == build_gradle_len, edit
    assert edit["endOffset"] == build_gradle_len, edit
    replacement = edit["replacement"]
    assert "sourceSets" in replacement, replacement
    assert "main" in replacement, replacement
    assert "srcDir('src/main/java11')" in replacement, replacement

    # B13 canonical envelope: a build-file edit is counted and forces a needs_review risk classification.
    assert result["impact"]["buildFilesEdited"] == 1, result["impact"]
    assert result["risk"] == "needs_review", result


def test_move_source_root_rewrites_kotlin_dsl_build_file_when_enabled(sidecar_jar: Path, tmp_path: Path) -> None:
    # The Kotlin-DSL (build.gradle.kts) variant uses the named(...)/srcDir("...") form valid for that DSL.
    project_root = tmp_path / "kotlin_rewrite"
    _write_project(project_root, "build.gradle.kts")

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11", "rewriteBuildFiles": True},
    )

    assert result.get("accepted") is True, result
    edits = text_edits(result["workspaceEdit"])
    assert len(edits) == 1, edits
    assert edits[0]["relativePath"].replace("\\", "/") == "build.gradle.kts", edits
    replacement = edits[0]["replacement"]
    assert 'named("main")' in replacement, replacement
    assert 'srcDir("src/main/java11")' in replacement, replacement


def test_move_source_root_rewrites_maven_build_helper_when_enabled(sidecar_jar: Path, tmp_path: Path) -> None:
    # B06: rewriteBuildFiles=true on a Maven module emits ONE additive build-helper-maven-plugin add-source edit (the
    # Maven analogue of the Gradle srcDir registration) instead of refusing. The fixture POM has a <project> root but no
    # <build>, so the planner inserts a full <build><plugins> block as a zero-width insertion before </project>.
    project_root = tmp_path / "maven_rewrite"
    _write_project(project_root, "pom.xml")
    pom_text = (project_root / "pom.xml").read_text(encoding="utf-8")
    project_close = pom_text.index("</project>")

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11", "rewriteBuildFiles": True},
    )

    assert result.get("accepted") is True, result

    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert renames == {
        ("src/main/java/com/acme/app/Service.java", "src/main/java11/com/acme/app/Service.java"),
        ("src/main/java/com/acme/app/Client.java", "src/main/java11/com/acme/app/Client.java"),
    }, renames

    edits = text_edits(result["workspaceEdit"])
    assert len(edits) == 1, edits
    edit = edits[0]
    assert edit["relativePath"].replace("\\", "/") == "pom.xml", edit
    # Additive zero-width insertion at the </project> offset: never rewrites existing POM configuration.
    assert edit["startOffset"] == project_close, edit
    assert edit["endOffset"] == project_close, edit
    replacement = edit["replacement"]
    assert "build-helper-maven-plugin" in replacement, replacement
    assert "<goal>add-source</goal>" in replacement, replacement
    assert "<source>src/main/java11</source>" in replacement, replacement

    # Canonical envelope: a build-file edit is counted and forces a needs_review risk classification.
    assert result["impact"]["buildFilesEdited"] == 1, result["impact"]
    assert result["risk"] == "needs_review", result


def test_move_source_root_refuses_malformed_maven_pom(sidecar_jar: Path, tmp_path: Path) -> None:
    # Residual refusal: a POM that is not well-formed XML cannot be safely extended, so the planner refuses with
    # build_file_rewrite_unsupported rather than emitting an edit against an unparseable build file.
    project_root = tmp_path / "maven_malformed"
    _write_project(project_root, None)
    (project_root / "pom.xml").write_text("<project><modelVersion>4.0.0</project>\n", encoding="utf-8")

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11", "rewriteBuildFiles": True},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "build_file_rewrite_unsupported", result


def test_move_source_root_refuses_when_no_gradle_build_file(sidecar_jar: Path, tmp_path: Path) -> None:
    # rewriteBuildFiles=true but the module has no Gradle build file at all: refuse instead of guessing where to write.
    project_root = tmp_path / "no_build_file"
    _write_project(project_root, None)

    result = _preview(
        sidecar_jar,
        project_root,
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11", "rewriteBuildFiles": True},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "build_file_rewrite_unsupported", result
