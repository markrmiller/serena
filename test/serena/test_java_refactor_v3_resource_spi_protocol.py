"""Live-sidecar coverage for the V3 ``resources.*`` protocol (refactor-feature-plan-V3.md §15).

These boot the real Java sidecar jar and drive the resource-reference SPI end to end via
:class:`~serena.java_refactor_v3.resource_spi_client.ResourceSpiClient`. The read half (``resources.findReferences``)
proves the sidecar locates references to a Java type inside non-Java resource files — ``META-INF/services`` provider
lists (HIGH), XML config (HIGH) and ``.properties`` config (MEDIUM) — with the right per-kind ``kind``/``confidence``,
that matching is exact-class-only, and that the §15 refusals surface with their canonical ``code``. The rewrite half
(``resources.planEdits``) proves the SAME unified engine plans the SAFE in-place edits (exact-class HIGH, package-prefix
MEDIUM only when enabled) and the §15.2 ServiceLoader interface-file rename, never auto-editing reflective/free-text
matches, and that planned-edit offsets line up with the references the read half reports.

Capabilities exercised:
    §15 test_resource_find_across_providers       — service-loader + xml + properties references, correct kinds
    §15 test_resource_find_package_prefix         — a package target matches sub-package tokens (PACKAGE_PREFIX)
    §15 test_resource_find_refuses_empty_target   — an empty target is refused (resource_target_unresolved)
    §15 test_resource_find_refuses_unknown_kind   — an unknown kinds filter is refused (unsupported_resource_kind)
    §15 test_resource_plan_edits_across_providers  — exact-class rewrites in service-loader/xml/properties (all HIGH)
    §15 test_resource_plan_edits_interface_rename  — moving the service interface renames its registration file (§15.2)
    §15 test_resource_plan_edits_package_prefix    — bare package tokens rewrite only when prefixes are enabled (MEDIUM)
    §15 test_resource_plan_edits_offsets_match_find — planned-edit spans equal the read-half reference spans
    §15 test_resource_plan_edits_refuses_empty     — planEdits with no maps is refused (resource_rename_empty)
    §15 test_resource_find_scans_yaml_and_json_by_default — .yml/.yaml/.json scanned by default (MEDIUM, offset-exact)
    §15 test_resource_find_exact_resolution_rejects_substring_similar_token — a prefix-only lookalike FQN is NOT reported
    §18.4 test_resource_plan_edits_high_confidence_auto_applies — HIGH exact-class edits are AUTO_APPLY (autoApply array)
    §18.4 test_resource_plan_edits_medium_previews_unless_configured — MEDIUM package-prefix edits PREVIEW unless opted in
    §18.4 test_resource_plan_edits_low_confidence_never_auto_applies — LOW reflective matches stay reviewOnly, never edited
    R06  test_resource_plan_edits_over_cap_resource_blocks_auto_apply — an incomplete (over-cap) scan blocks auto-apply
"""

from __future__ import annotations

import contextlib
import json
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _resources(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[ResourceSpiClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield ResourceSpiClient(client)
    finally:
        client.shutdown()


@contextlib.contextmanager
def _resources_with_config(
    sidecar_jar: Path, project_root: Path, configuration: str, java_command: str = "java"
) -> Iterator[ResourceSpiClient]:
    # Story R06 variant of :func:`_resources`: initializes the live sidecar with an explicit java_refactor configuration
    # string (rather than the "default" profile) so the resource-scan max-file-size cap
    # (java_refactor.v3.graph.max_resource_file_bytes) can be driven end-to-end, producing a deterministic over-cap
    # (incomplete) resource scan.
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield ResourceSpiClient(client)
    finally:
        client.shutdown()


def _seed_project(root: Path) -> None:
    _write(
        root,
        "src/main/java/com/acme/MyServiceImpl.java",
        "package com.acme;\npublic class MyServiceImpl {}\n",
    )
    _write(
        root,
        "src/main/resources/META-INF/services/com.acme.MyService",
        "# default provider\ncom.acme.MyServiceImpl\n",
    )
    _write(
        root,
        "src/main/resources/beans.xml",
        '<beans>\n  <bean id="impl" class="com.acme.MyServiceImpl"/>\n</beans>\n',
    )
    # A plain (non-Spring-bean) XML element carrying the FQN: a generic exact-class token (EXACT_CLASS_NAME/HIGH),
    # distinct from the specially-recognized <bean class="..."> case above (SPRING_BEAN_CLASS).
    _write(
        root,
        "src/main/resources/wiring.xml",
        "<config>\n  <handler>com.acme.MyServiceImpl</handler>\n</config>\n",
    )
    _write(
        root,
        "src/main/resources/application.properties",
        "service.handler=com.acme.MyServiceImpl\n",
    )
    _write(
        root,
        "src/main/resources/application.yaml",
        "service:\n  handler: com.acme.MyServiceImpl\n",
    )
    _write(
        root,
        "src/main/resources/handlers.json",
        '{ "handlerClass": "com.acme.MyServiceImpl" }\n',
    )


# ── §15 cross-provider find ───────────────────────────────────────────────────────────────────────────────────────


def test_resource_find_across_providers(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyServiceImpl")

    assert result.get("accepted") is True, result
    refs = result["references"]
    by_kind = {(r["kind"], r["confidence"]) for r in refs}
    assert ("SERVICE_LOADER_PROVIDER", "HIGH") in by_kind, result
    assert ("SPRING_BEAN_CLASS", "HIGH") in by_kind, result  # beans.xml <bean class="...">
    assert ("EXACT_CLASS_NAME", "HIGH") in by_kind, result  # wiring.xml (plain XML element text)
    assert ("EXACT_CLASS_NAME", "MEDIUM") in by_kind, result  # properties
    # The service file name (com.acme.MyService) must NOT match — exact-class-only, no substring/prefix bleed.
    assert all(r["oldText"] == "com.acme.MyServiceImpl" for r in refs), result


def test_resource_find_package_prefix(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme", target_is_package=True)

    assert result.get("accepted") is True, result
    refs = result["references"]
    assert refs, result
    assert any(r["kind"] == "PACKAGE_PREFIX" for r in refs), result


# ── §15 refusals ──────────────────────────────────────────────────────────────────────────────────────────────────


def test_resource_find_refuses_empty_target(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("   ")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "resource_target_unresolved", result


def test_resource_find_refuses_unknown_kind(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyServiceImpl", kinds=["NOT_A_KIND"])

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_resource_kind", result


# ── §15 planEdits (rewrite half) ──────────────────────────────────────────────────────────────────────────────────


def test_resource_plan_edits_across_providers(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.plan_edits(type_fqn_map={"com.acme.MyServiceImpl": "com.acme.NewImpl"})

    assert result.get("accepted") is True, result
    edits = result["edits"]
    # Every provider rewrites the exact moved FQN to the new one; the rewrite confidence is uniformly HIGH (exact class),
    # even in .properties where the *find* confidence is MEDIUM — planEdits confidence means "how safe to rewrite".
    assert all(e["newText"] == "com.acme.NewImpl" for e in edits), result
    assert all(e["confidence"] == "HIGH" for e in edits), result
    kinds_by_suffix = {Path(e["path"]).name: e["kind"] for e in edits}
    assert kinds_by_suffix.get("com.acme.MyService") == "SERVICE_LOADER_PROVIDER", result
    assert kinds_by_suffix.get("beans.xml") == "EXACT_CLASS_NAME", result
    assert kinds_by_suffix.get("application.properties") == "EXACT_CLASS_NAME", result
    # Renaming an implementation (not the interface) triggers no §15.2 file rename.
    assert result["fileRenames"] == [], result


def test_resource_plan_edits_interface_rename(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        # Moving the service *interface* renames its META-INF/services registration file (§15.2).
        result = client.plan_edits(type_fqn_map={"com.acme.MyService": "com.acme.MyServiceRenamed"})

    assert result.get("accepted") is True, result
    renames = result["fileRenames"]
    assert len(renames) == 1, result
    rename = renames[0]
    assert rename["from"].endswith("META-INF/services/com.acme.MyService"), result
    assert rename["to"].endswith("META-INF/services/com.acme.MyServiceRenamed"), result
    assert rename["provider"] == "service-loader", result
    # The interface FQN appears only in the filename, not in any file's content, so no in-place edits.
    assert result["edits"] == [], result


def test_resource_plan_edits_package_prefix(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    # A bare package token (a component-scan root) — the ambiguous case rewritten only when prefixes are enabled.
    _write(tmp_path, "src/main/resources/scan.xml", '<context:component-scan base-package="com.acme"/>\n')
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        disabled = client.plan_edits(package_map={"com.acme": "org.acme"})
        enabled = client.plan_edits(package_map={"com.acme": "org.acme"}, rewrite_package_prefixes=True)

    assert disabled.get("accepted") is True, disabled
    # With prefixes disabled (the default, per §5.5) a bare package token is left untouched.
    assert all(Path(e["path"]).name != "scan.xml" for e in disabled["edits"]), disabled

    assert enabled.get("accepted") is True, enabled
    scan_edits = [e for e in enabled["edits"] if Path(e["path"]).name == "scan.xml"]
    assert len(scan_edits) == 1, enabled
    assert scan_edits[0]["newText"] == "org.acme", enabled
    assert scan_edits[0]["kind"] == "PACKAGE_PREFIX", enabled
    assert scan_edits[0]["confidence"] == "MEDIUM", enabled


def test_resource_plan_edits_offsets_match_find(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        found = client.find_references("com.acme.MyServiceImpl")
        planned = client.plan_edits(type_fqn_map={"com.acme.MyServiceImpl": "com.acme.NewImpl"})

    assert found.get("accepted") is True, found
    assert planned.get("accepted") is True, planned
    find_spans = {(Path(r["path"]).name, r["startOffset"], r["endOffset"]) for r in found["references"]}
    plan_spans = {(Path(e["path"]).name, e["startOffset"], e["endOffset"]) for e in planned["edits"]}
    # Every safe rewrite targets exactly a span the read half reported as a reference (no invented edits).
    assert plan_spans, planned
    assert plan_spans <= find_spans, (plan_spans, find_spans)


def test_resource_plan_edits_refuses_empty(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.plan_edits()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "resource_rename_empty", result


# ── §15 per-format default scan coverage (yaml/json) ──────────────────────────────────────────────────────────────


def test_resource_find_scans_yaml_and_json_by_default(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §15: .yml/.yaml and .json are scanned by default; an exact dotted FQN value is a MEDIUM EXACT_CLASS_NAME with a
    # real offset that points at the FQN token (no fuzzy/substring matching — the value must equal the target).
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyServiceImpl")

    assert result.get("accepted") is True, result
    refs = result["references"]
    by_file = {Path(r["path"]).name: r for r in refs}
    for resource_name in ("application.yaml", "handlers.json"):
        ref = by_file.get(resource_name)
        assert ref is not None, (resource_name, result)
        assert ref["kind"] == "EXACT_CLASS_NAME", ref
        assert ref["confidence"] == "MEDIUM", ref
        # The reported span must isolate exactly the FQN token in the file's text.
        content = (tmp_path / "src/main/resources" / resource_name).read_text(encoding="utf-8")
        assert content[ref["startOffset"] : ref["endOffset"]] == "com.acme.MyServiceImpl", (resource_name, ref)


def test_resource_find_exact_resolution_rejects_substring_similar_token(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # §15: matching resolves the EXACT FQN token, never a substring-similar one. A sibling type whose FQN merely shares
    # a prefix (com.acme.MyServiceImplFactory) must NOT be reported when the target is com.acme.MyServiceImpl.
    _seed_project(tmp_path)
    _write(
        tmp_path,
        "src/main/resources/lookalike.json",
        '{ "factory": "com.acme.MyServiceImplFactory" }\n',
    )
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyServiceImpl")

    assert result.get("accepted") is True, result
    assert all(r["oldText"] == "com.acme.MyServiceImpl" for r in result["references"]), result
    assert all(Path(r["path"]).name != "lookalike.json" for r in result["references"]), result


# ── §18.4 confidence-based auto-apply policy (disposition) ─────────────────────────────────────────────────────────


def test_resource_plan_edits_high_confidence_auto_applies(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # §18.4: an exact-class rewrite is HIGH everywhere ("how safe to rewrite" — the token equals the moved FQN), so every
    # planned edit for a pure type-FQN move auto-applies: disposition AUTO_APPLY, present in the autoApply partition, and
    # nothing is left to preview.
    _seed_project(tmp_path)
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.plan_edits(type_fqn_map={"com.acme.MyServiceImpl": "com.acme.NewImpl"})

    assert result.get("accepted") is True, result
    assert result["applyMediumConfidence"] is False, result
    edits = result["edits"]
    assert edits, result
    assert all(e["confidence"] == "HIGH" for e in edits), result
    assert all(e["disposition"] == "AUTO_APPLY" for e in edits), result
    auto_spans = {(Path(e["path"]).name, e["startOffset"]) for e in result["autoApply"]}
    edit_spans = {(Path(e["path"]).name, e["startOffset"]) for e in edits}
    assert auto_spans == edit_spans, result
    assert result["preview"] == [], result
    assert result["stats"]["autoApply"] == len(edits), result
    assert result["stats"]["preview"] == 0, result


def test_resource_plan_edits_medium_previews_unless_configured(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # §18.4: "preview MEDIUM unless configured". A bare package-prefix rewrite is the MEDIUM case (ambiguous: it may be a
    # scanning root or an unrelated string), generated only when rewrite_package_prefixes is enabled. By default such a
    # MEDIUM edit is PREVIEW (not auto-applied); it flips to AUTO_APPLY only when the caller opts in via
    # apply_medium_confidence.
    _write(tmp_path, "src/main/resources/scan-roots.properties", "scan.base=com.acme\n")
    _seed_project(tmp_path)
    package_move = {"com.acme": "com.zeta"}
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        default = client.plan_edits(package_map=package_move, rewrite_package_prefixes=True)
        configured = client.plan_edits(
            package_map=package_move, rewrite_package_prefixes=True, apply_medium_confidence=True
        )

    medium_default = [e for e in default["edits"] if e["confidence"] == "MEDIUM"]
    assert medium_default, default
    assert all(e["kind"] == "PACKAGE_PREFIX" for e in medium_default), medium_default
    assert all(e["disposition"] == "PREVIEW" for e in medium_default), medium_default
    preview_spans = {(Path(e["path"]).name, e["startOffset"]) for e in default["preview"]}
    medium_spans = {(Path(e["path"]).name, e["startOffset"]) for e in medium_default}
    assert medium_spans <= preview_spans, default
    assert default["autoApply"] == [], default

    assert configured["applyMediumConfidence"] is True, configured
    medium_configured = [e for e in configured["edits"] if e["confidence"] == "MEDIUM"]
    assert medium_configured, configured
    assert all(e["disposition"] == "AUTO_APPLY" for e in medium_configured), medium_configured
    # Every edit now auto-applies (HIGH + MEDIUM); nothing left to preview.
    assert configured["preview"] == [], configured
    assert configured["stats"]["preview"] == 0, configured
    assert configured["stats"]["autoApply"] == len(configured["edits"]), configured


def test_resource_plan_edits_low_confidence_never_auto_applies(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # §18.4: "never auto-apply LOW". A reflective/free-text match (an unstructured .txt resource) is never planned as an
    # edit; it surfaces only in the review-only partition with disposition REVIEW_ONLY, even with MEDIUM auto-apply on.
    _seed_project(tmp_path)
    _write(tmp_path, "src/main/resources/notes.txt", "see com.acme.MyServiceImpl for details\n")
    with _resources(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.plan_edits(
            type_fqn_map={"com.acme.MyServiceImpl": "com.acme.NewImpl"}, apply_medium_confidence=True
        )

    assert result.get("accepted") is True, result
    # The reflective candidate is never in edits/autoApply/preview.
    for partition in ("edits", "autoApply", "preview"):
        assert all(Path(e["path"]).name != "notes.txt" for e in result[partition]), (partition, result)
    review = result["reviewOnly"]
    txt_refs = [r for r in review if Path(r["path"]).name == "notes.txt"]
    assert len(txt_refs) == 1, result
    assert txt_refs[0]["confidence"] == "LOW", txt_refs
    assert txt_refs[0]["disposition"] == "REVIEW_ONLY", txt_refs
    assert txt_refs[0]["kind"] == "REFLECTIVE_STRING_CANDIDATE", txt_refs
    assert result["stats"]["reviewOnly"] >= 1, result


def test_resource_plan_edits_over_cap_resource_blocks_auto_apply(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Story R06 (end-to-end, live sidecar): an INCOMPLETE resource scan must BLOCK auto-apply — it is not downgraded to a
    # benign warning on an otherwise-accepted auto-apply edit. Here beans.xml carries a HIGH exact-class token that would
    # normally auto-apply, while a second in-scope resource (huge.xml) exceeds the configured max-file-size cap, so its
    # content is never read and its references can't be determined. The op must mark resourceScanIncomplete, surface the
    # over-cap file in incompleteResources, partition NO edit into autoApply (stats.autoApply == 0), and downgrade every
    # planned edit to PREVIEW — proving the gate blocks auto-apply (criterion #3) and the result classifies needs_review
    # (REVIEW_REQUIRED) rather than auto-applying behind an incomplete scan (criteria #1-#2).
    _write(tmp_path, "src/main/java/com/acme/MyServiceImpl.java", "package com.acme;\npublic class MyServiceImpl {}\n")
    _write(
        tmp_path,
        "src/main/resources/beans.xml",
        '<beans>\n  <bean id="impl" class="com.acme.MyServiceImpl"/>\n</beans>\n',
    )
    # An in-scope XML resource that is larger than the tiny cap below, so the scanner cannot examine it.
    _write(tmp_path, "src/main/resources/huge.xml", "<config>\n" + (" " * 4096) + "\n</config>\n")

    # A small cap (256 bytes) makes huge.xml (>4 KiB) over-cap while leaving the ~68-byte beans.xml scannable, so beans'
    # HIGH edit is still planned (and then withheld from auto-apply by the incompleteness gate).
    configuration = json.dumps({"java_refactor": {"v3": {"graph": {"max_resource_file_bytes": 256}}}})
    with _resources_with_config(sidecar_jar, tmp_path, configuration, java_command=sidecar_java_cmd) as client:
        result = client.plan_edits(type_fqn_map={"com.acme.MyServiceImpl": "com.other.MyServiceImpl"})

    assert result.get("accepted") is True, result
    # The scan is flagged incomplete and the specific over-cap file is surfaced (never silently dropped).
    assert result["resourceScanIncomplete"] is True, result
    incomplete = result["incompleteResources"]
    assert any(str(p).replace("\\", "/").endswith("huge.xml") for p in incomplete), result
    # No edit may be auto-applied on an incomplete scan: the autoApply partition is empty and stats agree.
    assert result["autoApply"] == [], result
    assert result["stats"]["autoApply"] == 0, result
    # beans.xml's HIGH edit is still planned (not lost) — only WITHHELD from auto-apply, dropped to PREVIEW.
    edits = result["edits"]
    assert edits, result
    assert all(e["disposition"] == "PREVIEW" for e in edits), result
    # Every edit is in the preview partition; nothing auto-applies behind the incomplete scan.
    preview_spans = {(Path(e["path"]).name, e["startOffset"]) for e in result["preview"]}
    edit_spans = {(Path(e["path"]).name, e["startOffset"]) for e in edits}
    assert preview_spans == edit_spans, result
    assert result["stats"]["preview"] == len(edits), result
