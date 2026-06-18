"""Live-sidecar coverage for the unified transformation graph (refactor-feature-plan-V3.md §1.2/§3 F-GRAPH).

These tests boot the real Java sidecar jar and exercise the ``graph.build`` / ``graph.buildCount`` protocol, then
hydrate the result through the Python :func:`~serena.java_refactor_v3.graph_client.parse_project_graph` contract. They
prove the graph is a REAL cached, revision-keyed, sidecar-backed structure — not a per-request façade:

* build-system detection + source-root classification across plain / Gradle / Maven layouts (acceptance §(2));
* package → source-root and type → file maps come from the real compiler model (no substring guessing);
* exact resource FQN references are produced by the resource SPI providers — a *substring* of an FQN is NOT matched
  (the regression guard against the old substring scanner);
* test → production edges feed likely-affected-tests for a touched type;
* the cache HITs on an unchanged revision and MISSes (rebuilds) after any source edit, observed via the
  ``graph.buildCount`` diagnostic counter (acceptance §(3)/§(4)).

The Java unit responsibilities (graph component construction) live in the sidecar; this is the protocol + Python
client contract surface that the impact/report consumers read.
"""

from __future__ import annotations

import contextlib
import shutil
from collections.abc import Iterator
from pathlib import Path

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.graph.models import BuildSystem
from serena.java_refactor_v3.graph_client import GraphClient, GraphRefused, parse_project_graph
from test.serena._java_refactor_sidecar_helpers import (
    _write_gradle_java_project,
    write_maven_offline_project,
)

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

# Explicit config that declares separate named main/test source sets so the sidecar classifies roots without a
# Gradle/Maven build model (mirrors the impact-facts protocol harness). Discovery kind for an explicit/no-build-tool
# project is neither maven nor gradle, so the graph reports buildSystem "plain".
_EXPLICIT_CONFIG = (
    '{"buildToolMode": "explicit", "allowIncompleteAnalysis": true, '
    '"model": {"modules": [{"sourceSets": ['
    '{"name": "main", "sourceRoots": ["src/main/java"]}, '
    '{"name": "test", "sourceRoots": ["src/test/java"]}'
    ']}]}}'
)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _graph(
    sidecar_jar: Path,
    project_root: Path,
    java_command: str = "java",
    configuration: str = "default",
) -> Iterator[GraphClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield GraphClient(client)
    finally:
        client.shutdown()


# ── (a) plain layout: build system, source roots, package→root, type→file ────────────────────────────────────────


def test_plain_graph_build_system_roots_and_maps(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/App.java", "package com.acme;\npublic class App {}\n")
    _write(tmp_path, "src/test/java/com/acme/AppTest.java", "package com.acme;\npublic class AppTest {}\n")
    _write(tmp_path, "src/main/resources/app.properties", "key=value\n")

    with _graph(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as graph:
        project = graph.project_graph()

    # build system: an explicit/no-build-tool project is "plain".
    assert project.build.build_system is BuildSystem.PLAIN, project.build.to_dict()
    # source-root classification (main java / test java / resources).
    assert any("main/java" in r.relative_path for r in project.build.main_java_roots()), project.build.to_dict()
    assert any("test/java" in r.relative_path for r in project.build.test_java_roots()), project.build.to_dict()
    assert any("resources" in r.relative_path for r in project.build.resource_roots()), project.build.to_dict()

    # type → file: the declaring file is the real compiler-resolved path, not a guess.
    assert project.symbols.type_to_file.get("com.acme.App") == "src/main/java/com/acme/App.java", (
        project.symbols.type_to_file
    )
    # package → source root: com.acme maps to the main java root.
    roots = project.symbols.package_to_source_roots.get("com.acme", set())
    assert any("main/java" in r for r in roots), project.symbols.package_to_source_roots


# ── (b) gradle layout: build system detected from the gradle model ───────────────────────────────────────────────


def test_gradle_graph_reports_gradle_build_system(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    main_src, _test_src = _write_gradle_java_project(tmp_path)
    (main_src / "Widget.java").write_text("package example;\npublic class Widget {}\n", encoding="utf-8")

    with _graph(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as graph:
        project = graph.project_graph()

    assert project.build.build_system is BuildSystem.GRADLE, project.build.to_dict()
    assert project.symbols.type_to_file.get("example.Widget") == "src/main/java/example/Widget.java", (
        project.symbols.type_to_file
    )


# ── (c) maven layout: build system detected from the maven model ─────────────────────────────────────────────────


def test_maven_graph_reports_maven_build_system(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str, maven_offline_repo: Path
) -> None:
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
        encoding="utf-8",
    )
    _write(tmp_path, "src/main/java/demo/Service.java", "package demo;\npublic class Service {}\n")

    with _graph(
        sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration='{"buildToolMode": "maven", "offline": true}'
    ) as graph:
        project = graph.project_graph()

    assert project.build.build_system is BuildSystem.MAVEN, project.build.to_dict()
    assert project.symbols.type_to_file.get("demo.Service") == "src/main/java/demo/Service.java", (
        project.symbols.type_to_file
    )


# ── (d) exact resource FQN references (NO substring) ─────────────────────────────────────────────────────────────


def test_resource_references_are_exact_fqn_not_substring(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # A ServiceLoader provider file names com.acme.Handler exactly. A *sibling* type com.acme.HandlerFactory shares
    # the "com.acme.Handler" prefix; an exact-FQN provider must reference Handler ONLY, never HandlerFactory.
    _write(tmp_path, "src/main/java/com/acme/Handler.java", "package com.acme;\npublic class Handler {}\n")
    _write(
        tmp_path,
        "src/main/java/com/acme/HandlerFactory.java",
        "package com.acme;\npublic class HandlerFactory {}\n",
    )
    _write(
        tmp_path,
        "src/main/resources/META-INF/services/com.acme.SpiContract",
        "com.acme.Handler\n",
    )

    with _graph(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as graph:
        project = graph.project_graph()

    assert project.resources.references_to("com.acme.Handler"), project.resources.to_dict()
    # The substring sibling must NOT be referenced by the provider file (exact-FQN guard).
    assert project.resources.references_to("com.acme.HandlerFactory") == [], project.resources.to_dict()


# ── (e) likely-affected tests via test→production edges ──────────────────────────────────────────────────────────


def test_tests_referencing_a_touched_type(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Core.java",
           "package com.acme;\npublic class Core { public int value() { return 1; } }\n")
    _write(tmp_path, "src/test/java/com/acme/CoreTest.java",
           "package com.acme;\npublic class CoreTest { Core c = new Core(); }\n")

    with _graph(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as graph:
        project = graph.project_graph()

    affected = {t.test_fqn for t in project.tests.tests_referencing("com.acme.Core")}
    assert "com.acme.CoreTest" in affected, project.tests.to_dict()


# ── (f) caching + invalidation observed via graph.buildCount ─────────────────────────────────────────────────────


def test_graph_cached_then_incrementally_updated_on_source_change(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """R05 incremental-maintenance contract: an unchanged revision HITs, and a ``.java`` edit is served by an
    INCREMENTAL update (re-extracting only the touched file's contribution), NOT a whole-project rebuild.

    The previous version of this test blessed a full rebuild on every source edit (``build_count() == after_first + 1``).
    R05 requires the transformation graph to be maintained incrementally: an edit to ``Helper`` (which ``App``
    references) must still yield the correct ``App`` → ``Helper`` relationship while the FULL build count stays flat and
    the INCREMENTAL-update count advances. Correctness is asserted by comparing the incrementally maintained graph to a
    fresh from-scratch parse of the same revision (a process restart, whose first build is necessarily full).
    """
    _write(tmp_path, "src/main/java/com/acme/Helper.java",
           "package com.acme;\npublic class Helper { public int value() { return 1; } }\n")
    _write(tmp_path, "src/main/java/com/acme/App.java",
           "package com.acme;\npublic class App { int go() { return new Helper().value(); } }\n")

    client = JavaRefactorClient(sidecar_jar, java_command=sidecar_java_cmd)
    client.start()
    incremental_json: str
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        graph = GraphClient(client)

        base_full = graph.build_count()

        # First build → cache MISS, no prior snapshot → exactly one FULL materialization.
        first = graph.build_or_raise()
        assert first["accepted"] is True
        after_first_full = graph.build_count()
        assert after_first_full == base_full + 1, "first graph.build must fully materialize the graph once"
        # The cross-file relationship the incremental edit must preserve.
        assert first["symbols"]["typeToFile"].get("com.acme.App") == "src/main/java/com/acme/App.java"
        assert first["symbols"]["typeToFile"].get("com.acme.Helper") == "src/main/java/com/acme/Helper.java"

        # Second build over an unchanged revision → cache HIT → neither counter advances.
        before_incr = graph.incremental_update_count()
        second = graph.build_or_raise()
        assert second["accepted"] is True
        assert graph.build_count() == after_first_full, "an unchanged revision must reuse the cached graph (HIT)"
        assert graph.incremental_update_count() == before_incr, "a HIT must not advance the incremental counter"

        # Edit Helper (App references it) → INCREMENTAL update: full count flat, incremental count +1.
        _write(tmp_path, "src/main/java/com/acme/Helper.java",
               "package com.acme;\npublic class Helper { public int value() { return 2; } }\n")
        third = graph.build_or_raise()
        assert third["accepted"] is True
        assert graph.build_count() == after_first_full, (
            "a source edit must be served incrementally, NOT by a whole-project rebuild (R05)"
        )
        assert graph.incremental_update_count() == before_incr + 1, (
            "a source edit must advance the incremental-update counter exactly once (R05)"
        )
        # The App → Helper relationship must survive the incremental update.
        assert third["symbols"]["typeToFile"].get("com.acme.App") == "src/main/java/com/acme/App.java"
        assert third["symbols"]["typeToFile"].get("com.acme.Helper") == "src/main/java/com/acme/Helper.java"
        import json as _json
        incremental_json = _json.dumps(third, sort_keys=True)
    finally:
        client.shutdown()

    # Correctness oracle: a fresh process whose FIRST build is full must produce the identical graph for this revision.
    fresh = JavaRefactorClient(sidecar_jar, java_command=sidecar_java_cmd)
    fresh.start()
    try:
        fresh.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        fresh_graph = GraphClient(fresh)
        from_scratch = fresh_graph.build_or_raise()
        import json as _json
        assert _json.dumps(from_scratch, sort_keys=True) == incremental_json, (
            "the incrementally maintained graph must equal a from-scratch rebuild of the same revision (R05)"
        )
    finally:
        fresh.shutdown()


# ── (g) refusal before initialize ────────────────────────────────────────────────────────────────────────────────


def test_graph_build_refuses_before_initialize(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    client = JavaRefactorClient(sidecar_jar, java_command=sidecar_java_cmd)
    client.start()
    try:
        raw = GraphClient(client).build()
        assert raw.get("accepted") is False, raw
        assert raw["refusal"]["code"] == "not_initialized", raw
        with pytest.raises(GraphRefused):
            GraphClient(client).build_or_raise()
    finally:
        client.shutdown()


# ── (h) consumer integration: the parsed graph feeds an impact-report-shaped read ────────────────────────────────


def test_parsed_graph_feeds_report_shaped_reads(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The impact-report builder reads .symbols.type_to_file, .build.*_roots(), .resources.references_to and
    # .tests.tests_referencing off a ProjectGraph. Prove the parsed real graph satisfies that exact accessor surface.
    _write(tmp_path, "src/main/java/com/acme/Core.java", "package com.acme;\npublic class Core {}\n")
    _write(tmp_path, "src/test/java/com/acme/CoreTest.java",
           "package com.acme;\npublic class CoreTest { Core c = new Core(); }\n")
    _write(tmp_path, "src/main/resources/wiring.properties", "bean=com.acme.Core\n")

    with _graph(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as graph:
        raw = graph.build_or_raise()
    project = parse_project_graph(raw)

    # Reverse index the way ImpactReportBuilder does, off the real graph.
    file_to_fqns: dict[str, list[str]] = {}
    for fqn, rel in project.symbols.type_to_file.items():
        file_to_fqns.setdefault(rel, []).append(fqn)
    assert "com.acme.Core" in file_to_fqns.get("src/main/java/com/acme/Core.java", []), project.symbols.type_to_file
    assert any("resources" in r.relative_path for r in project.build.resource_roots())
    assert [r.relative_path for r in project.resources.references_to("com.acme.Core")] == ["src/main/resources/wiring.properties"], (
        project.resources.to_dict()
    )
    assert {t.test_fqn for t in project.tests.tests_referencing("com.acme.Core")} == {"com.acme.CoreTest"}, (
        project.tests.to_dict()
    )
