"""Live-sidecar coverage for the V3 ``frameworks.*`` protocol (refactor-feature-plan-V3.md §16).

These boot the real Java sidecar jar and drive the framework SPI end to end via
:class:`~serena.java_refactor_v3.framework_spi_client.FrameworkSpiClient`. They prove framework recognition is backed by
exact compiler-resolved annotation FQNs (not package-name heuristics): ``detect`` reports a framework as present only
when its annotations are actually applied, and ``find_references`` distinguishes a type whose own declaration/members
carry framework annotations (``matchKind: "declares"``) from a type merely named inside a framework annotation's
arguments (``matchKind: "names"``). Both ops are read-only; planner integration is deferred to Phase 7.

Because real framework jars are not on the test fixtures' classpath, the framework annotations are stubbed in-tree under
their exact package names so javac resolves them to the same FQNs the SPI keys on (here: JUnit Jupiter).

Capabilities exercised:
    §16 test_framework_detect_junit            — JUnit is detected from applied @Test/@ExtendWith with evidence
    §16 test_framework_find_declares           — a type carrying framework annotations is reported (matchKind declares)
    §16 test_framework_find_names              — a type named in an annotation argument is reported (matchKind names)
    §16 test_framework_find_refuses_empty      — an empty target is refused (framework_target_unresolved)
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.framework_spi_client import FrameworkSpiClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _frameworks(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[FrameworkSpiClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield FrameworkSpiClient(client)
    finally:
        client.shutdown()


def _seed_project(root: Path) -> None:
    # Stub the JUnit Jupiter annotations the SPI keys on, under their exact package names, so javac resolves their FQNs.
    _write(
        root,
        "src/main/java/org/junit/jupiter/api/Test.java",
        "package org.junit.jupiter.api;\npublic @interface Test {}\n",
    )
    _write(
        root,
        "src/main/java/org/junit/jupiter/api/extension/ExtendWith.java",
        "package org.junit.jupiter.api.extension;\npublic @interface ExtendWith { Class<?>[] value(); }\n",
    )
    _write(
        root,
        "src/main/java/com/acme/MyExtension.java",
        "package com.acme;\npublic class MyExtension {}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/MyTest.java",
        "package com.acme;\n"
        "import org.junit.jupiter.api.Test;\n"
        "import org.junit.jupiter.api.extension.ExtendWith;\n"
        "@ExtendWith(MyExtension.class)\n"
        "public class MyTest {\n"
        "    @Test void runs() {}\n"
        "}\n",
    )


# ── §16 detect ────────────────────────────────────────────────────────────────────────────────────────────────────


def test_framework_detect_junit(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.detect()

    assert result.get("accepted") is True, result
    by_id = {f["framework"]: f for f in result["frameworks"]}
    assert by_id["junit"]["detected"] is True, result
    annotations = {e["annotation"] for e in by_id["junit"]["evidence"]}
    assert "org.junit.jupiter.api.Test" in annotations, result
    # A framework whose annotations are absent stays undetected.
    assert by_id["spring"]["detected"] is False, result


# ── §16 find references: declares vs names ────────────────────────────────────────────────────────────────────────


def test_framework_find_declares(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyTest")

    assert result.get("accepted") is True, result
    refs = result["references"]
    assert refs, result
    assert all(r["framework"] == "junit" for r in refs), result
    assert all(r["matchKind"] == "declares" for r in refs), result
    roles = {r["role"] for r in refs}
    assert "TEST" in roles, result  # the @Test method
    assert "EXTEND_WITH" in roles, result  # the @ExtendWith class


def test_framework_find_names(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("com.acme.MyExtension")

    assert result.get("accepted") is True, result
    refs = result["references"]
    assert refs, result
    assert any(r["matchKind"] == "names" and r["role"] == "EXTEND_WITH" for r in refs), result


# ── §16 refusal ───────────────────────────────────────────────────────────────────────────────────────────────────


def test_framework_find_refuses_empty(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.find_references("")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "framework_target_unresolved", result


# ── §16 participate: the transformation-participant half ───────────────────────────────────────────────────────────


def _seed_spring(root: Path) -> None:
    # Stub the real Spring @Service stereotype FQN so javac resolves it to the FQN the SPI keys on.
    _write(
        root,
        "src/main/java/org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;\npublic @interface Service {}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/OrderService.java",
        "package com.acme;\n"
        "import org.springframework.stereotype.Service;\n"
        "@Service\n"
        "public class OrderService {}\n",
    )


def _seed_jpa(root: Path, *, with_id: bool) -> None:
    # Stub the real JPA (jakarta) annotation FQNs so javac resolves them to the FQNs the SPI keys on.
    _write(
        root,
        "src/main/java/jakarta/persistence/Entity.java",
        "package jakarta.persistence;\npublic @interface Entity {}\n",
    )
    _write(
        root,
        "src/main/java/jakarta/persistence/Id.java",
        "package jakarta.persistence;\npublic @interface Id {}\n",
    )
    id_field = "    @jakarta.persistence.Id long id;\n" if with_id else ""
    _write(
        root,
        "src/main/java/com/acme/Customer.java",
        "package com.acme;\n"
        "import jakarta.persistence.Entity;\n"
        "@Entity\n"
        "public class Customer {\n" + id_field + "}\n",
    )


def test_participate_jpa_entity_blocks_safe_delete(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A plugin BLOCKS deletion of a framework-critical symbol: deleting a @Entity is vetoed with a reason.
    _seed_jpa(tmp_path, with_id=True)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("safeDelete", target="com.acme.Customer")

    assert result.get("accepted") is True, result
    blocked = {b["symbol"]: b["reason"] for b in result["blocks"]}
    assert "com.acme.Customer" in blocked, result
    assert "persistence entry point" in blocked["com.acme.Customer"], blocked


def test_participate_jpa_metadata_validation_warns_without_id(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Metadata validation path: a @Entity with no @Id is flagged as likely-misconfigured during a rename participate.
    _seed_jpa(tmp_path, with_id=False)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("renameType", target="com.acme.Customer", new_name="com.acme.Client")

    assert result.get("accepted") is True, result
    warnings = " ".join(result["warnings"])
    assert "no @Id/@EmbeddedId" in warnings, result
    # The rename also flags the exact-class rewrite in persistence/ORM XML.
    assert any("persistence.xml" in edit for edit in result["resourceEdits"]), result


def test_participate_jpa_metadata_no_warning_with_id(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # A correctly-mapped @Entity (carrying @Id) produces no metadata warning — the validation path is real, not constant.
    _seed_jpa(tmp_path, with_id=True)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("renameType", target="com.acme.Customer", new_name="com.acme.Client")

    assert result.get("accepted") is True, result
    assert not any("no @Id/@EmbeddedId" in w for w in result["warnings"]), result


def test_participate_spring_rename_contributes_resource_edit(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # A plugin contributes a resource edit + warning during a rename of a framework-managed type.
    _seed_spring(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("renameType", target="com.acme.OrderService", new_name="com.acme.PurchaseService")

    assert result.get("accepted") is True, result
    assert any("bean-definition XML" in edit for edit in result["resourceEdits"]), result
    assert any("string bean name" in w for w in result["warnings"]), result


def test_participate_junit_test_is_reachability_root(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # A JUnit @Test method (and its enclosing class) is contributed as a reachability root for the dead-code scan.
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("deadCodeScan")

    assert result.get("accepted") is True, result
    roots = set(result["roots"])
    assert "com.acme.MyTest" in roots, result
    assert "com.acme.MyTest#runs" in roots, result


def test_participate_refuses_unrecognized_change_kind(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _seed_project(tmp_path)
    with _frameworks(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.participate("teleport", target="com.acme.MyTest")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "framework_change_unrecognized", result
