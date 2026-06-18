"""Live-sidecar coverage for the V3 ``impact.facts`` protocol (refactor-feature-plan-V3.md Phase 7).

These tests boot the real Java sidecar jar and drive ``impact.facts`` end to end via
:class:`~serena.java_refactor_v3.impact_facts_client.ImpactFactsClient`. They prove that impact analysis is computed by
javac inside the sidecar (not by a Python heuristic), that the op is stateless and purely analytical (never writes
files), and that the returned fact report covers all four fact categories.

Capabilities exercised:
    (a) test_source_roots_classified         — main/test/resource root classification
    (b) test_touched_types_with_flags        — FQN, relativePath, publicApi, testSource flags per touched type
    (c) test_incoming_refs_main_vs_test      — incomingRefs split by fromTestSource
    (d) test_resource_refs_detected          — resourceRefs for FQN substring match in a .properties file
    (e) test_no_incoming_refs_for_isolated   — isolated type reports zero incomingRefs
    (f) test_multiple_touched_files          — multiple paths in one call aggregated correctly
    (g) test_mutates_nothing                 — purely analytical, no file changes
    (h) test_empty_touched_paths             — empty list → accepted with zero touchedTypes
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.impact_facts_client import ImpactFactsClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

# Explicit config that declares separate named main/test source sets so the sidecar can classify roots
# correctly without a Gradle/Maven build model. Uses the model.modules.sourceSets form (§17.2 §17.3) with
# a nested "model" object; the sidecar derives testSource from sourceSet.name().contains("test").
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
def _impact(sidecar_jar: Path, project_root: Path, java_command: str = "java", configuration: str = "default") -> Iterator[ImpactFactsClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield ImpactFactsClient(client)
    finally:
        client.shutdown()


# ── (a) source-root classification ───────────────────────────────────────────────────────────────────────────────


def test_source_roots_classified(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Standard Maven layout: src/main/java (main), src/test/java (test), src/main/resources (resource).
    _write(tmp_path, "src/main/java/com/acme/App.java",
           "package com.acme;\npublic class App {}\n")
    _write(tmp_path, "src/test/java/com/acme/AppTest.java",
           "package com.acme;\npublic class AppTest {}\n")
    _write(tmp_path, "src/main/resources/app.properties", "key=value\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/App.java"])

    assert result.get("accepted") is True, result
    roots = result["sourceRoots"]
    assert any("main/java" in r for r in roots["main"]), roots
    assert any("test/java" in r for r in roots["test"]), roots
    assert any("resources" in r for r in roots["resources"]), roots


# ── (b) touched-type flags ────────────────────────────────────────────────────────────────────────────────────────


def test_touched_types_with_flags(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Two types in the same file: one public, one package-private. Both should appear in touchedTypes.
    _write(
        tmp_path,
        "src/main/java/com/acme/pkg/Widget.java",
        "package com.acme.pkg;\n"
        "public class Widget {}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/pkg/Helper.java",
        "package com.acme.pkg;\n"
        "class Helper {}\n",
    )

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts([
            "src/main/java/com/acme/pkg/Widget.java",
            "src/main/java/com/acme/pkg/Helper.java",
        ])

    assert result.get("accepted") is True, result
    by_fqn = {t["fqn"]: t for t in result["touchedTypes"]}
    assert "com.acme.pkg.Widget" in by_fqn, result
    assert "com.acme.pkg.Helper" in by_fqn, result

    widget = by_fqn["com.acme.pkg.Widget"]
    assert widget["publicApi"] is True, widget
    assert widget["testSource"] is False, widget
    assert "Widget.java" in widget["relativePath"], widget

    helper = by_fqn["com.acme.pkg.Helper"]
    assert helper["publicApi"] is False, helper
    assert helper["testSource"] is False, helper


def test_test_source_flag_set_for_test_type(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A type in src/test/java must have testSource=true.
    _write(tmp_path, "src/main/java/com/acme/Svc.java", "package com.acme;\npublic class Svc {}\n")
    _write(tmp_path, "src/test/java/com/acme/SvcTest.java",
           "package com.acme;\npublic class SvcTest {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/test/java/com/acme/SvcTest.java"])

    assert result.get("accepted") is True, result
    types = {t["fqn"]: t for t in result["touchedTypes"]}
    assert "com.acme.SvcTest" in types, result
    assert types["com.acme.SvcTest"]["testSource"] is True, types["com.acme.SvcTest"]


# ── (c) incomingRefs split by fromTestSource ──────────────────────────────────────────────────────────────────────


def test_incoming_refs_main_vs_test(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Core is referenced by both a main-source caller (Caller) and a test-source caller (CoreTest).
    _write(tmp_path, "src/main/java/com/acme/Core.java",
           "package com.acme;\npublic class Core { public int value() { return 1; } }\n")
    _write(tmp_path, "src/main/java/com/acme/Caller.java",
           "package com.acme;\npublic class Caller { Core c = new Core(); }\n")
    _write(tmp_path, "src/test/java/com/acme/CoreTest.java",
           "package com.acme;\npublic class CoreTest { Core c = new Core(); }\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Core.java"])

    assert result.get("accepted") is True, result
    refs_to_core = [r for r in result["incomingRefs"] if r["toFqn"] == "com.acme.Core"]

    from_main = [r for r in refs_to_core if not r["fromTestSource"]]
    from_test = [r for r in refs_to_core if r["fromTestSource"]]
    assert from_main, f"expected at least one main-source referrer; got {refs_to_core}"
    assert from_test, f"expected at least one test-source referrer; got {refs_to_core}"


# ── (d) resource refs detected ────────────────────────────────────────────────────────────────────────────────────


def test_resource_refs_detected(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A .properties resource file that mentions the FQN should appear in resourceRefs.
    _write(tmp_path, "src/main/java/com/acme/Processor.java",
           "package com.acme;\npublic class Processor {}\n")
    _write(tmp_path, "src/main/resources/config.properties",
           "handler.class=com.acme.Processor\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/Processor.java"])

    assert result.get("accepted") is True, result
    resource_refs = result["resourceRefs"]
    matching = [r for r in resource_refs if r["target"] == "com.acme.Processor"]
    assert matching, f"expected a resource ref to com.acme.Processor; got {resource_refs}"
    assert any("config.properties" in r["resourcePath"] for r in matching), matching


# ── (e) isolated type → no incoming refs ─────────────────────────────────────────────────────────────────────────


def test_no_incoming_refs_for_isolated(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Isolated type — nothing references it; incomingRefs should be empty.
    _write(tmp_path, "src/main/java/com/acme/Isolated.java",
           "package com.acme;\nclass Isolated {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/Isolated.java"])

    assert result.get("accepted") is True, result
    assert result["incomingRefs"] == [], result
    assert result["stats"]["incomingRefs"] == 0, result


# ── (f) multiple touched files aggregated ────────────────────────────────────────────────────────────────────────


def test_multiple_touched_files(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Alpha.java",
           "package com.acme;\npublic class Alpha {}\n")
    _write(tmp_path, "src/main/java/com/acme/Beta.java",
           "package com.acme;\npublic class Beta {}\n")
    _write(tmp_path, "src/main/java/com/acme/Gamma.java",
           "package com.acme;\nclass Gamma {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts([
            "src/main/java/com/acme/Alpha.java",
            "src/main/java/com/acme/Beta.java",
            "src/main/java/com/acme/Gamma.java",
        ])

    assert result.get("accepted") is True, result
    fqns = {t["fqn"] for t in result["touchedTypes"]}
    assert {"com.acme.Alpha", "com.acme.Beta", "com.acme.Gamma"} <= fqns, result
    assert result["stats"]["touchedTypes"] >= 3, result


# ── (g) mutates nothing ──────────────────────────────────────────────────────────────────────────────────────────


def test_mutates_nothing(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Pure.java",
           "package com.acme;\npublic class Pure {}\n")
    _write(tmp_path, "src/main/resources/pure.properties", "key=com.acme.Pure\n")

    before_java = {p: p.read_text("utf-8") for p in tmp_path.rglob("*.java")}
    before_props = {p: p.read_text("utf-8") for p in tmp_path.rglob("*.properties")}

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/Pure.java"])

    assert result.get("accepted") is True, result
    assert "workspaceEdit" not in result, "impact.facts must not produce a workspaceEdit"

    after_java = {p: p.read_text("utf-8") for p in tmp_path.rglob("*.java")}
    after_props = {p: p.read_text("utf-8") for p in tmp_path.rglob("*.properties")}
    assert before_java == after_java, "impact.facts must not modify Java files"
    assert before_props == after_props, "impact.facts must not modify resource files"


# ── (h) empty touched paths ──────────────────────────────────────────────────────────────────────────────────────


def test_empty_touched_paths(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/App.java",
           "package com.acme;\npublic class App {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts([])

    assert result.get("accepted") is True, result
    assert result["touchedTypes"] == [], result
    assert result["incomingRefs"] == [], result
    assert result["stats"]["touchedTypes"] == 0, result


# ── (i) framework participation section ──────────────────────────────────────────────────────────────────────────


def _jpa_entity_stub(root: Path) -> None:
    _write(root, "src/main/java/jakarta/persistence/Entity.java",
           "package jakarta.persistence;\npublic @interface Entity { String name() default \"\"; }\n")


def test_framework_refs_detected_for_entity(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    """A touched ``@Entity`` type is reported in ``frameworkRefs`` with its exact owning framework + role + FQN."""
    _jpa_entity_stub(tmp_path)
    _write(tmp_path, "src/main/java/com/acme/Customer.java",
           "package com.acme;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Customer {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Customer.java"])

    assert result.get("accepted") is True, result
    framework_refs = result["frameworkRefs"]
    entity = [r for r in framework_refs if r["annotationFqn"] == "jakarta.persistence.Entity"]
    assert len(entity) == 1, framework_refs
    assert entity[0]["frameworkId"] == "jpa", entity
    assert entity[0]["role"] == "ENTITY", entity
    assert entity[0]["typeFqn"] == "com.acme.Customer", entity
    assert result["stats"]["frameworkRefs"] == 1, result


def test_framework_refs_empty_for_plain_type(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    """Honesty gate: a type with no framework annotation produces NO frameworkRefs entry (never a heuristic match)."""
    _write(tmp_path, "src/main/java/com/acme/Plain.java",
           "package com.acme;\npublic class Plain {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Plain.java"])

    assert result.get("accepted") is True, result
    assert result["frameworkRefs"] == [], result
    assert result["stats"]["frameworkRefs"] == 0, result


# ── (j) risk classification ──────────────────────────────────────────────────────────────────────────────────────


def test_risk_high_for_framework_entry_point(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _jpa_entity_stub(tmp_path)
    _write(tmp_path, "src/main/java/com/acme/Customer.java",
           "package com.acme;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Customer {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Customer.java"])

    risk = result["risk"]
    assert risk["level"] == "HIGH", risk
    assert risk["frameworkEntryPoints"] == 1, risk
    assert any("framework entry-point" in reason for reason in risk["reasons"]), risk


def test_risk_high_for_resource_reference(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Wired.java",
           "package com.acme;\npublic class Wired {}\n")
    _write(tmp_path, "src/main/resources/beans.properties", "handler=com.acme.Wired\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Wired.java"])

    risk = result["risk"]
    assert risk["level"] == "HIGH", risk
    assert risk["resourceRefs"] >= 1, risk
    assert any("string-encoded" in reason for reason in risk["reasons"]), risk


def test_risk_low_for_isolated_plain_type(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    """A non-public, unreferenced, framework-free type is LOW risk — the change cannot escape the compiler's net."""
    _write(tmp_path, "src/main/java/com/acme/Isolated.java",
           "package com.acme;\nclass Isolated {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Isolated.java"])

    risk = result["risk"]
    assert risk["level"] == "LOW", risk
    assert risk["frameworkEntryPoints"] == 0, risk
    assert risk["resourceRefs"] == 0, risk


# ── (k) provider-backed resource references (confidence + kind + exact location), Review Gap 13 ────────────────────


def test_resource_ref_is_provider_backed_xml_bean_high_confidence(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """A Spring ``<bean class="...">`` reference is reported by the XML provider with HIGH confidence + the
    ``SPRING_BEAN_CLASS`` kind + exact offsets — NOT a bare substring match. This proves impact resource refs come from
    the resource SPI providers, carrying confidence/kind/location, rather than the old text-scan."""
    _write(tmp_path, "src/main/java/com/acme/OrderService.java",
           "package com.acme;\npublic class OrderService {}\n")
    _write(tmp_path, "src/main/resources/beans.xml",
           '<beans>\n  <bean id="orderService" class="com.acme.OrderService"/>\n</beans>\n')

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/OrderService.java"])

    assert result.get("accepted") is True, result
    bean_refs = [r for r in result["resourceRefs"] if r["target"] == "com.acme.OrderService"]
    assert len(bean_refs) == 1, result["resourceRefs"]
    ref = bean_refs[0]
    assert ref["kind"] == "SPRING_BEAN_CLASS", ref
    assert ref["confidence"] == "HIGH", ref
    assert ref["provider"] == "xml", ref
    # Exact location: offsets bound the matched FQN token and oldText is the matched text verbatim.
    assert ref["oldText"] == "com.acme.OrderService", ref
    assert ref["endOffset"] - ref["startOffset"] == len("com.acme.OrderService"), ref
    assert "beans.xml" in ref["resourcePath"], ref


def test_substring_similar_non_match_is_not_reported(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """Honesty gate over the old substring scan: a resource that mentions a STRICT PREFIX of the FQN
    (``com.acme.OrderServiceFactory`` contains ``com.acme.OrderService`` as a substring) must NOT be reported, because
    the providers match maximal dotted tokens, not substrings."""
    _write(tmp_path, "src/main/java/com/acme/OrderService.java",
           "package com.acme;\npublic class OrderService {}\n")
    # The token here is a DIFFERENT class whose name merely starts with the target FQN's text.
    _write(tmp_path, "src/main/resources/decoy.properties",
           "handler=com.acme.OrderServiceFactory\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/OrderService.java"])

    assert result.get("accepted") is True, result
    matching = [r for r in result["resourceRefs"] if r["target"] == "com.acme.OrderService"]
    assert matching == [], f"a substring-similar non-match leaked into provider-backed refs: {matching}"


def test_service_loader_ref_is_high_confidence_exact_changed_entry(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """A META-INF/services provider line is a HIGH-confidence SERVICE_LOADER_PROVIDER ref and appears in the
    ``exactChangedEntries`` set (the entries a rename/move would actually rewrite)."""
    _write(tmp_path, "src/main/java/com/acme/MyProvider.java",
           "package com.acme;\npublic class MyProvider {}\n")
    _write(tmp_path, "src/main/resources/META-INF/services/com.acme.Spi",
           "com.acme.MyProvider\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/MyProvider.java"])

    assert result.get("accepted") is True, result
    sl = [r for r in result["resourceRefs"] if r["kind"] == "SERVICE_LOADER_PROVIDER"]
    assert len(sl) == 1, result["resourceRefs"]
    assert sl[0]["confidence"] == "HIGH", sl[0]
    assert sl[0]["provider"] == "service-loader", sl[0]
    # exact changed entries: the HIGH-confidence provider line a refactor would rewrite.
    exact = [r for r in result["exactChangedEntries"] if r["kind"] == "SERVICE_LOADER_PROVIDER"]
    assert len(exact) == 1, result["exactChangedEntries"]
    assert result["resourceSubtypeCounts"].get("SERVICE_LOADER_PROVIDER") == 1, result["resourceSubtypeCounts"]


def test_reflection_candidate_is_review_only_never_auto_changed(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """A free-text resource the reflection provider claims yields a LOW/REFLECTIVE_STRING_CANDIDATE ref that is surfaced
    under ``reflectionCandidates`` (for human review) and is NEVER in ``exactChangedEntries`` (never auto-changed)."""
    _write(tmp_path, "src/main/java/com/acme/Plugin.java",
           "package com.acme;\npublic class Plugin {}\n")
    # An unstructured text resource (no recognized structured provider) → reflection-candidate fallback.
    _write(tmp_path, "src/main/resources/notes.txt",
           "Load the class com.acme.Plugin at runtime via reflection.\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as impact:
        result = impact.facts(["src/main/java/com/acme/Plugin.java"])

    assert result.get("accepted") is True, result
    candidates = [r for r in result["reflectionCandidates"] if r["target"] == "com.acme.Plugin"]
    assert len(candidates) == 1, result["reflectionCandidates"]
    assert candidates[0]["kind"] == "REFLECTIVE_STRING_CANDIDATE", candidates[0]
    assert candidates[0]["confidence"] == "LOW", candidates[0]
    # Never auto-changed: the LOW/reflective candidate must not appear in the exact-changed set.
    exact_targets = [r for r in result["exactChangedEntries"] if r["target"] == "com.acme.Plugin"]
    assert exact_targets == [], f"a reflective candidate leaked into exactChangedEntries: {exact_targets}"
    assert result["resourceSubtypeCounts"].get("REFLECTIVE_STRING_CANDIDATE") == 1, result["resourceSubtypeCounts"]


# ── (l) framework metadata impact contributes framework-discovered refs ───────────────────────────────────────────


def test_framework_metadata_impact_includes_jpa_discovered_ref(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """Framework metadata impact: a JPA ``@Entity`` on a touched type is a framework-discovered reference in the facts,
    carrying its exact framework id + role + annotation FQN (compiler-resolved, not a heuristic name match)."""
    _jpa_entity_stub(tmp_path)
    _write(tmp_path, "src/main/java/com/acme/Account.java",
           "package com.acme;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Account {}\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/Account.java"])

    assert result.get("accepted") is True, result
    discovered = [r for r in result["frameworkRefs"] if r["typeFqn"] == "com.acme.Account"]
    assert len(discovered) == 1, result["frameworkRefs"]
    assert discovered[0]["frameworkId"] == "jpa", discovered[0]
    assert discovered[0]["role"] == "ENTITY", discovered[0]
    assert discovered[0]["annotationFqn"] == "jakarta.persistence.Entity", discovered[0]


# ── (m) suggested test commands populated per build model ──────────────────────────────────────────────────────────


def test_suggested_test_commands_for_gradle_project(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """A Gradle project (build.gradle + gradlew wrapper) yields the wrapper-based Gradle test command."""
    _write(tmp_path, "src/main/java/com/acme/App.java", "package com.acme;\npublic class App {}\n")
    _write(tmp_path, "build.gradle", "plugins { id 'java' }\n")
    (tmp_path / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/App.java"])

    assert result.get("accepted") is True, result
    commands = result["suggestedTestCommands"]
    assert commands, result
    assert any("./gradlew" in c and "test" in c for c in commands), commands


def test_suggested_test_commands_for_maven_project(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    """A Maven project (pom.xml) yields a Maven test command."""
    _write(tmp_path, "src/main/java/com/acme/App.java", "package com.acme;\npublic class App {}\n")
    _write(tmp_path, "pom.xml", "<project></project>\n")

    with _impact(sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=_EXPLICIT_CONFIG) as impact:
        result = impact.facts(["src/main/java/com/acme/App.java"])

    assert result.get("accepted") is True, result
    commands = result["suggestedTestCommands"]
    assert any("mvn" in c and "test" in c for c in commands), commands
