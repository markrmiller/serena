"""Sidecar-backed tests for V3 Phase 2 package-operation side effects (refactor-feature-plan-V3 §5.4/§5.5/§6.3).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the JSON-lines preview harness, proving the three Phase 2 capabilities that go BEYOND moving files and rewriting Java
references:

* §5.4 ``module-info.java`` — when a package is renamed, the matching ``exports``/``opens``/``provides``/``uses``
  directive is rewritten to the new package while the surrounding module declaration (module name, ``requires``, the
  ``to`` target list) is left untouched, and the modular after-state still compiles (``diagnosticDeltaValidated`` true).
* §5.5 resources — an exact fully-qualified class name in a scanned resource (here a CDI ``beans.xml``) is rewritten
  safely, while a dynamic/reflective ``Class.forName("com.old." + name)`` prefix is left untouched and reported as a
  reflection candidate warning rather than half-rewritten.
* §6.3 build files — ``moveSourceRoot`` makes NO build-file edits by default, so a target that is not already a
  configured source root is refused with the coded ``BUILD_FILE_UPDATE_REQUIRED`` signal instead of relocating files
  into a directory the build would not compile.
"""

import json
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import (
    _preview_op,
    _resolve_sidecar_java,
    file_ops,
    sidecar_jar,
    text_edits,
)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def test_sidecar_rename_package_rewrites_module_info_directive(sidecar_jar: Path, tmp_path: Path) -> None:
    # §5.4: renaming com.acme.app.api -> com.acme.app.core moves Api.java and rewrites the matching `exports` directive
    # in module-info.java to the new package, leaving the module name and braces untouched. The modular after-state is
    # compiled in module mode by the central PreviewDiagnosticValidator (diagnosticDeltaValidated true).
    project_root = tmp_path / "module_info_rename"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.acme.app {\n    exports com.acme.app.api;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/acme/app/api/Api.java",
        "package com.acme.app.api;\npublic class Api {\n    public static int v() { return 1; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app.api", "newPackage": "com.acme.app.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    # The moved type relocates from the old package directory to the new one.
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert ("src/main/java/com/acme/app/api/Api.java", "src/main/java/com/acme/app/core/Api.java") in renames, renames

    # The module-info directive package qualifier is rewritten to the new package (module name left untouched).
    module_info_rel = "src/main/java/module-info.java"
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == module_info_rel
    ]
    assert any(edit["replacement"] == "com.acme.app.core" for edit in module_edits), module_edits
    # The module declaration name (com.acme.app) must NOT be rewritten.
    assert all(edit["replacement"] != "com.acme.app" for edit in module_edits), module_edits


def test_sidecar_rename_package_rewrites_resource_fqcn_and_warns_on_reflection(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.5: renaming com.acme.app -> com.acme.core rewrites the exact FQCN com.acme.app.Service inside a scanned
    # beans.xml resource, while a dynamic Class.forName("com.acme.app." + name) prefix in a referencing Java file is
    # left untouched and surfaced as a reflection-candidate warning (NOT half-rewritten).
    project_root = tmp_path / "resource_rename"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    # A reflective loader in a DIFFERENT package: the string prefix must not be rewritten.
    _write(
        project_root,
        "src/main/java/com/acme/client/Loader.java",
        "package com.acme.client;\n"
        "public class Loader {\n"
        "    Class<?> load(String name) throws ClassNotFoundException {\n"
        '        return Class.forName("com.acme.app." + name);\n'
        "    }\n"
        "}\n",
    )
    # A CDI resource that names the type by its exact FQCN: this IS safe to rewrite.
    _write(
        project_root,
        "src/main/resources/META-INF/beans.xml",
        '<beans>\n    <bean class="com.acme.app.Service"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    # The resource's exact FQCN is rewritten com.acme.app.Service -> com.acme.core.Service.
    resource_rel = "src/main/resources/META-INF/beans.xml"
    resource_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == resource_rel
    ]
    assert any(edit["replacement"] == "com.acme.core.Service" for edit in resource_edits), resource_edits

    # The reflective string prefix is NOT rewritten anywhere (no edit produces the dynamic-prefix replacement).
    loader_rel = "src/main/java/com/acme/client/Loader.java"
    loader_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == loader_rel
    ]
    assert loader_edits == [], loader_edits

    # ...and the reflective usage is surfaced as a warning so the human knows to review it.
    warnings = result["workspaceEdit"].get("warnings") or []
    assert any("com.acme.app" in warning and "reflect" in warning.lower() for warning in warnings), warnings


def test_sidecar_rename_package_respects_rewrite_resources_false(sidecar_jar: Path, tmp_path: Path) -> None:
    # Exact Spring XML references cannot be silently left dangling when resource rewrites are disabled.
    project_root = tmp_path / "resource_rewrite_off"
    _write(project_root, "src/main/java/com/acme/app/Service.java", "package com.acme.app;\npublic class Service { int value = 1; }\n")
    _write(
        project_root,
        "src/main/resources/META-INF/beans.xml",
        '<beans>\n    <bean class="com.acme.app.Service"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core", "rewriteResources": False},
    )

    assert result.get("accepted") is False
    assert result.get("refusal", {}).get("code") == "validation_findings_not_ready"
    assert "SPRING_BEAN_CLASS" in result.get("refusal", {}).get("message", "")


def test_sidecar_move_package_rewrites_resource_fqcn(sidecar_jar: Path, tmp_path: Path) -> None:
    # §5.5 for movePackage: moving com.acme.app -> com.acme.core rewrites the exact FQCN com.acme.app.Service inside a
    # scanned beans.xml resource (rewrite_resources defaults on). MovePackagePlanner honors policy.rewriteResources()
    # via the same ResourceRewriter path as renamePackage, so this proves the move op is not a Java-only rewrite.
    project_root = tmp_path / "move_resource_default"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/resources/META-INF/beans.xml",
        '<beans>\n    <bean class="com.acme.app.Service"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    resource_rel = "src/main/resources/META-INF/beans.xml"
    resource_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == resource_rel
    ]
    assert any(edit["replacement"] == "com.acme.core.Service" for edit in resource_edits), resource_edits


def test_sidecar_move_package_respects_rewrite_resources_false(sidecar_jar: Path, tmp_path: Path) -> None:
    # Exact Spring XML references cannot be silently left dangling when resource rewrites are disabled.
    project_root = tmp_path / "move_resource_rewrite_off"
    _write(project_root, "src/main/java/com/acme/app/Service.java", "package com.acme.app;\npublic class Service { int value = 1; }\n")
    _write(
        project_root,
        "src/main/resources/META-INF/beans.xml",
        '<beans>\n    <bean class="com.acme.app.Service"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core", "rewriteResources": False},
    )

    assert result.get("accepted") is False
    assert result.get("refusal", {}).get("code") == "validation_findings_not_ready"
    assert "SPRING_BEAN_CLASS" in result.get("refusal", {}).get("message", "")


def test_sidecar_rename_package_respects_rewrite_module_info_false(sidecar_jar: Path, tmp_path: Path) -> None:
    # §5.4 per-call override: renaming a package that is NOT referenced by module-info, with rewriteModuleInfo=false.
    # The module exports a sibling package (com.acme.app.api) that is untouched by the rename of com.acme.app.impl, so
    # the after-state still compiles in module mode; the opt-out simply guarantees no module-info edit is produced.
    project_root = tmp_path / "module_info_rewrite_off"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.acme.app {\n    exports com.acme.app.api;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/acme/app/api/Api.java",
        "package com.acme.app.api;\npublic class Api {\n    public static int v() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/acme/app/impl/Impl.java",
        "package com.acme.app.impl;\npublic class Impl {\n    public int v() { return 2; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app.impl", "newPackage": "com.acme.app.internal", "rewriteModuleInfo": False},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    # The impl type still moves to the new package.
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert (
        "src/main/java/com/acme/app/impl/Impl.java",
        "src/main/java/com/acme/app/internal/Impl.java",
    ) in renames, renames

    # No module-info edit is produced (the directive opt-out is honored).
    module_info_rel = "src/main/java/module-info.java"
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == module_info_rel
    ]
    assert module_edits == [], module_edits


def test_sidecar_move_source_root_refuses_unknown_target_with_build_file_code(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §6.3: moveSourceRoot performs NO build-file edits by default. A target that is not already a configured source
    # root would require editing build.gradle (sourceSets.main.java.srcDirs), so the sidecar refuses with the coded
    # BUILD_FILE_UPDATE_REQUIRED signal rather than relocating files into a directory the build does not compile.
    project_root = tmp_path / "build_file_required"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    # Explicit config: src/main/java is a configured root; src/main/java11 is NOT, so the TARGET resolution fails.
    config = json.dumps(
        {"buildToolMode": "explicit", "sourceRoots": ["src/main/java"], "allowIncompleteAnalysis": True}
    )

    client = JavaRefactorClient(sidecar_jar, java_command=_resolve_sidecar_java())
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=config))
        result = client.preview(
            "moveSourceRoot",
            {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java11"},
        )
    finally:
        client.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "BUILD_FILE_UPDATE_REQUIRED", result


def _preview_op_with_config(sidecar_jar: Path, project_root: Path, operation: str, params: dict, configuration: str) -> dict:
    # §5.5 config-driven variant of _preview_op: initializes the sidecar with an explicit java_refactor configuration
    # (rather than the "default" profile) so resource-policy flags can be exercised end-to-end against the live sidecar.
    client = JavaRefactorClient(sidecar_jar, java_command=_resolve_sidecar_java())
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        return client.preview(operation, params)
    finally:
        client.shutdown()


def test_sidecar_rename_package_renames_service_loader_file_and_rewrites_provider_line(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §15.2: a META-INF/services/<interface-fqn> registration encodes the service INTERFACE fqn in its FILENAME and the
    # provider IMPLEMENTATION fqn in its CONTENT. Renaming the package that owns both must (a) RENAME the registration
    # file com.acme.spi.Spi -> com.acme.core.Spi (otherwise ServiceLoader.load(Spi.class) finds nothing) and (b) rewrite
    # the provider line com.acme.spi.SpiImpl -> com.acme.core.SpiImpl as a HIGH-confidence exact-class-name edit. Without
    # the file rename the registration would dangle; without the content rewrite the provider would not resolve.
    project_root = tmp_path / "service_loader_rename"
    _write(
        project_root,
        "src/main/java/com/acme/spi/Spi.java",
        "package com.acme.spi;\npublic interface Spi {\n    int value();\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/acme/spi/SpiImpl.java",
        "package com.acme.spi;\npublic class SpiImpl implements Spi {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/resources/META-INF/services/com.acme.spi.Spi",
        "com.acme.spi.SpiImpl\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.spi", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    # The ServiceLoader registration file is renamed to track the moved service-interface fqn encoded in its name.
    renames = {
        (op["relativePath"].replace("\\", "/"), op["newRelativePath"].replace("\\", "/"))
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert (
        "src/main/resources/META-INF/services/com.acme.spi.Spi",
        "src/main/resources/META-INF/services/com.acme.core.Spi",
    ) in renames, renames

    # The provider implementation line inside the registration is rewritten as a HIGH-confidence exact class name. The
    # text edit is keyed by the file's CURRENT (pre-rename) path so it applies before the file operation moves it.
    service_rel = "src/main/resources/META-INF/services/com.acme.spi.Spi"
    provider_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == service_rel
    ]
    assert any(
        edit["replacement"] == "com.acme.core.SpiImpl" and edit["kind"] == "RESOURCE_REFERENCE:HIGH"
        for edit in provider_edits
    ), provider_edits


def test_sidecar_resource_class_name_rewrite_carries_high_confidence_kind(sidecar_jar: Path, tmp_path: Path) -> None:
    # §5.5/§15: an exact dotted FQCN rewrite in a structured resource is structurally unambiguous, so the emitted edit
    # carries ResourceConfidence.HIGH on its kind ("RESOURCE_REFERENCE:HIGH"). This proves confidence is a real,
    # preview-visible attribute of resource edits, not an internal-only label.
    project_root = tmp_path / "resource_confidence"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/resources/META-INF/beans.xml",
        '<beans>\n    <bean class="com.acme.app.Service"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    resource_rel = "src/main/resources/META-INF/beans.xml"
    resource_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == resource_rel
    ]
    assert any(
        edit["replacement"] == "com.acme.core.Service" and edit["kind"] == "RESOURCE_REFERENCE:HIGH"
        for edit in resource_edits
    ), resource_edits


def test_sidecar_rename_package_leaves_standalone_package_prefix_untouched_by_default(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.5: a STANDALONE package-name token in a resource (a Spring component-scan base-package) is ambiguous (it may be a
    # scanning root that should follow the move, or an unrelated string), so rewrite_package_prefixes defaults OFF: the
    # bare prefix com.acme.app is NOT rewritten even though the exact-class-name path is on. Only exact FQCNs are touched
    # by default; a bare package prefix is left for human review.
    project_root = tmp_path / "package_prefix_default_off"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/resources/spring.xml",
        '<beans>\n    <context:component-scan base-package="com.acme.app"/>\n</beans>\n',
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
    )

    assert result.get("accepted") is True, result
    resource_rel = "src/main/resources/spring.xml"
    resource_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == resource_rel
    ]
    assert resource_edits == [], resource_edits


def test_sidecar_rename_package_rewrites_package_prefix_with_medium_confidence_when_enabled(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.5: when an operator opts in via java_refactor.v3.packages.rewrite_package_prefixes=true, the same standalone
    # base-package token IS rewritten com.acme.app -> com.acme.core, but carries ResourceConfidence.MEDIUM
    # ("RESOURCE_REFERENCE:MEDIUM") to flag that a bare package prefix is a less-certain match than an exact FQCN.
    project_root = tmp_path / "package_prefix_enabled"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/resources/spring.xml",
        '<beans>\n    <context:component-scan base-package="com.acme.app"/>\n</beans>\n',
    )

    configuration = json.dumps({"java_refactor": {"v3": {"packages": {"rewrite_package_prefixes": True}}}})
    result = _preview_op_with_config(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.core"},
        configuration,
    )

    assert result.get("accepted") is True, result
    resource_rel = "src/main/resources/spring.xml"
    resource_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == resource_rel
    ]
    assert any(
        edit["replacement"] == "com.acme.core" and edit["kind"] == "RESOURCE_REFERENCE:MEDIUM"
        for edit in resource_edits
    ), resource_edits


def test_sidecar_move_package_removes_redundant_export_when_merging_into_exported_package(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4 (remove a stale directive only when safe): movePackage com.a -> com.b MERGES com.a's types into com.b, which
    # the descriptor ALSO exports. Naively rewriting `exports com.a;` to `exports com.b;` would emit a SECOND
    # `exports com.b;` — a `duplicate export` javac error. The rewriter instead DELETES the now-redundant source
    # directive, so the descriptor still compiles (diagnosticDeltaValidated true) with a single `exports com.b;`.
    project_root = tmp_path / "module_info_merge_dedup"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.acme.app {\n    exports com.a;\n    exports com.b;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/a/A.java",
        "package com.a;\npublic class A {\n    public static int v() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/b/B.java",
        "package com.b;\npublic class B {\n    public static int w() { return 2; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.a", "targetPackage": "com.b"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    module_info_rel = "src/main/java/module-info.java"
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == module_info_rel
    ]
    # The redundant `exports com.a;` is DELETED (an empty-replacement edit), NOT rewritten to a duplicate `exports com.b;`.
    assert any(edit["replacement"] == "" for edit in module_edits), module_edits
    assert all(edit["replacement"] != "com.b" for edit in module_edits), module_edits
    warnings = result["workspaceEdit"].get("warnings") or []
    assert any("redundant" in warning.lower() and "com.b" in warning for warning in warnings), warnings


def test_sidecar_rename_package_warns_on_partially_moved_provides_directive(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4 rule 1 (partial move): a `provides Service with ImplA, ImplB` clause where only ImplA's package moves ends up
    # spanning a moved and a non-moved implementation package. The descriptor is still rewritten and compiles, but the
    # partial move is surfaced as a warning for review.
    project_root = tmp_path / "module_info_partial_provides"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.acme.app {\n"
        "    exports com.svc;\n"
        "    uses com.svc.Service;\n"
        "    provides com.svc.Service with com.a.ImplA, com.other.ImplB;\n"
        "}\n",
    )
    _write(
        project_root,
        "src/main/java/com/svc/Service.java",
        "package com.svc;\npublic interface Service {\n    int run();\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/a/ImplA.java",
        "package com.a;\npublic class ImplA implements com.svc.Service {\n    public int run() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/other/ImplB.java",
        "package com.other;\npublic class ImplB implements com.svc.Service {\n    public int run() { return 2; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.a", "newPackage": "com.a2"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    module_info_rel = "src/main/java/module-info.java"
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == module_info_rel
    ]
    # The moved implementation FQN is rewritten in the `with` list; the non-moved one is left untouched.
    assert any(edit["replacement"] == "com.a2.ImplA" for edit in module_edits), module_edits
    assert all(edit["replacement"] != "com.other.ImplB" for edit in module_edits), module_edits
    warnings = result["workspaceEdit"].get("warnings") or []
    assert any("partial move" in warning.lower() for warning in warnings), warnings


def test_sidecar_rename_package_preserves_exports_to_target_module_list(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4 rule 3 (preserve `to` lists): renaming com.a -> com.c rewrites ONLY the exported package token, leaving the
    # `exports ... to java.base;` qualified target list intact (the rewrite never touches the module names after `to`).
    project_root = tmp_path / "module_info_qualified_export"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.acme.app {\n    exports com.a to java.base;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/a/A.java",
        "package com.a;\npublic class A {\n    public static int v() { return 1; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.a", "newPackage": "com.c"},
    )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    module_info_rel = "src/main/java/module-info.java"
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == module_info_rel
    ]
    # Only the package token is rewritten; nothing rewrites the `to java.base` target module list.
    assert any(edit["replacement"] == "com.c" for edit in module_edits), module_edits
    assert all("java.base" not in (edit["replacement"] or "") for edit in module_edits), module_edits


def _write_split_package_two_module_project(project_root: Path) -> None:
    # A valid module graph (no JPMS split package): com.x.a lives ONLY in modA, com.x.b ONLY in modB. Renaming the parent
    # package com.x therefore spans two module descriptors — the §5.4 "split across modules" case.
    _write(
        project_root,
        "src/main/java/modA/module-info.java",
        "module modA {\n    exports com.x.a;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modA/com/x/a/A.java",
        "package com.x.a;\npublic class A {\n    public static int v() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modB/module-info.java",
        "module modB {\n    exports com.x.b;\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modB/com/x/b/B.java",
        "package com.x.b;\npublic class B {\n    public static int w() { return 2; }\n}\n",
    )


def test_sidecar_rename_package_refuses_when_split_across_modules(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4 rule 2: renaming com.x -> com.y moves com.x.a (owned/exported by modA) and com.x.b (owned by modB), so the
    # package is split across modules. Without an explicit module strategy the operation is refused with a coded signal
    # rather than silently editing two module descriptors.
    project_root = tmp_path / "module_info_split_refuse"
    _write_split_package_two_module_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.x", "newPackage": "com.y"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_split_across_modules", result


def test_sidecar_rename_package_split_across_modules_proceeds_with_module_strategy(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4 rule 2: when the caller supplies an explicit moduleStrategy the split-package guard is lifted and the rename
    # proceeds, rewriting BOTH module descriptors' exports for their respective moved subpackages.
    project_root = tmp_path / "module_info_split_strategy"
    _write_split_package_two_module_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.x", "newPackage": "com.y", "moduleStrategy": "rewrite-all"},
    )

    assert result.get("refusal", {}).get("code") != "package_split_across_modules", result
    assert result.get("accepted") is True, result
    module_replacements = {
        edit["replacement"]
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/").endswith("module-info.java")
    }
    assert "com.y.a" in module_replacements, module_replacements
    assert "com.y.b" in module_replacements, module_replacements


# ── Build-graph split-package detection (§5.3 collision check / §5.4): split detection must use the REAL
# package-to-source-root facts derived from the build model for ALL packages, exported or not. A package physically
# present in two source roots/modules is split regardless of module-info exports. ─────────────────────────────────────


def _write_non_exported_split_two_module_project(project_root: Path) -> None:
    # com.x.a is declared by files under TWO module roots (modA and modB, both under the single src/main/java source
    # root) yet is NEVER exported or opened by either module-info.java. The package is therefore split across modules in
    # the build graph even though module-info offers no signal of it: the only way to detect this is real
    # package-to-source-root facts, not module-info exports.
    _write(
        project_root,
        "src/main/java/modA/module-info.java",
        "module modA {\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modA/com/x/a/A.java",
        "package com.x.a;\nclass A {\n    static int v() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modB/module-info.java",
        "module modB {\n}\n",
    )
    _write(
        project_root,
        "src/main/java/modB/com/x/a/B.java",
        "package com.x.a;\nclass B {\n    static int w() { return 2; }\n}\n",
    )


def test_sidecar_rename_package_detects_split_of_non_exported_package_via_build_graph(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.3/§5.4: com.x.a lives under two distinct module roots but is exported/opened by NEITHER module-info, so the
    # module-info-exports guard cannot see the split. The build-graph package-to-source-root facts still report it as
    # split across modules, so renaming it without a moduleStrategy is refused. This proves split detection no longer
    # depends on module-info exports.
    project_root = tmp_path / "non_exported_split_refuse"
    _write_non_exported_split_two_module_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.x.a", "newPackage": "com.y.a"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_split_across_modules", result
    # The refusal must cite the build-graph source-root facts, not the module-info export list (which is empty here).
    assert "source roots/modules" in result["refusal"]["message"], result


def test_sidecar_rename_package_split_of_non_exported_package_proceeds_with_module_strategy(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4: supplying an explicit moduleStrategy lifts the build-graph split guard for a non-exported split package, so
    # the rename proceeds (the caller has decided how the split is resolved).
    project_root = tmp_path / "non_exported_split_strategy"
    _write_non_exported_split_two_module_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.x.a", "newPackage": "com.y.a", "moduleStrategy": "rewrite-all"},
    )

    assert result.get("refusal", {}).get("code") != "package_split_across_modules", result
    assert result.get("accepted") is True, result
    # Both files (one per module) are relocated under the new package directory.
    renames = {
        op["newRelativePath"].replace("\\", "/")
        for op in file_ops(result["workspaceEdit"])
        if op["kind"] == "rename"
    }
    assert "src/main/java/modA/com/y/a/A.java" in renames, renames
    assert "src/main/java/modB/com/y/a/B.java" in renames, renames


def test_sidecar_move_package_detects_non_exported_split_across_two_source_roots(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.3: the same package com.demo is declared in BOTH configured source roots (src/main/java and src/test/java) and is
    # not exported anywhere (no module-info). The build-graph facts report it as split across two source roots, so moving
    # it without a moduleStrategy is refused — proving the source-root dimension of build-graph split detection.
    project_root = tmp_path / "two_root_split_refuse"
    _write(
        project_root,
        "src/main/java/com/demo/Main.java",
        "package com.demo;\nclass Main {\n    static int v() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/test/java/com/demo/MainTest.java",
        "package com.demo;\nclass MainTest {\n    static int w() { return 2; }\n}\n",
    )
    config = json.dumps(
        {"buildToolMode": "explicit", "sourceRoots": ["src/main/java", "src/test/java"], "allowIncompleteAnalysis": True}
    )

    result = _preview_op_with_config(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.demo", "targetPackage": "com.demo.moved"},
        config,
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "package_split_across_modules", result
    assert "source roots/modules" in result["refusal"]["message"], result


def test_sidecar_rename_package_preserves_qualified_directives_and_provides_uses(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # §5.4: a rename of com.old must rewrite the package qualifier in every directive while PRESERVING the qualified
    # `exports ... to` / `opens ... to` target-module lists verbatim and rewriting the `provides`/`uses` service FQNs.
    # None of these directive forms may be dropped or have their `to` target list altered.
    project_root = tmp_path / "qualified_directive_preserve"
    _write(
        project_root,
        "src/main/java/module-info.java",
        "module com.old {\n"
        "    exports com.old.api to consumer.one, consumer.two;\n"
        "    opens com.old.model to framework.mod;\n"
        "    uses com.old.spi.Service;\n"
        "    provides com.old.spi.Service with com.old.impl.ServiceImpl;\n"
        "}\n",
    )
    _write(
        project_root,
        "src/main/java/com/old/api/Api.java",
        "package com.old.api;\npublic class Api {}\n",
    )
    _write(
        project_root,
        "src/main/java/com/old/model/Model.java",
        "package com.old.model;\npublic class Model {}\n",
    )
    _write(
        project_root,
        "src/main/java/com/old/spi/Service.java",
        "package com.old.spi;\npublic interface Service {\n    int value();\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/old/impl/ServiceImpl.java",
        "package com.old.impl;\nimport com.old.spi.Service;\n"
        "public class ServiceImpl implements Service {\n    public int value() { return 1; }\n}\n",
    )

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.old", "newPackage": "com.fresh"},
    )

    assert result.get("accepted") is True, result
    module_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/").endswith("module-info.java")
    ]
    replacements = {edit["replacement"] for edit in module_edits}
    # The directive package qualifiers and the service FQNs are all rewritten to the new package tree.
    assert "com.fresh.api" in replacements, replacements
    assert "com.fresh.model" in replacements, replacements
    assert "com.fresh.spi.Service" in replacements, replacements
    assert "com.fresh.impl.ServiceImpl" in replacements, replacements
    # CRITICAL: only the package qualifier / service FQN tokens are rewritten — the qualified `to` target-module lists
    # (consumer.one, consumer.two, framework.mod) are NEVER touched, so no module-info edit replaces or contains them.
    for edit in module_edits:
        assert "consumer.one" not in edit["replacement"], edit
        assert "consumer.two" not in edit["replacement"], edit
        assert "framework.mod" not in edit["replacement"], edit
    # The four directive forms (exports-to, opens-to, uses, provides) each contribute at least one edit; none is dropped.
    assert len(replacements) >= 4, replacements
