"""F8 (static-validation layer 7 §18.1.7 + resolution half of framework validation §18.3): the ``validateEdit`` op must
flag EXACT class references in resources that a staged edit leaves dangling — a renamed-away or deleted type whose
fully-qualified name is still named by an unrewritten Spring ``<bean class="...">``, JPA ``<class>...</class>``, or
``META-INF/services`` provider line. The check is exact and edit-scoped: it reports a reference ONLY when the FQN is one
the edit actually removes (computed from the overlay via a real javac parse), so it never false-positives on library
types, unchanged types, or a reference the same edit already rewrote.

These tests drive the LIVE sidecar through ``validateEdit`` with a staged overlay and assert on ``resourceFindings`` and
``ready``. The honesty gates prove: (1) an unrelated/unchanged edit produces NO finding; (2) when the SAME overlay
rewrites the resource, the (now-correct) reference is NOT flagged; (3) a reference to a library type is never flagged.
"""

from pathlib import Path
from typing import Any

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _validate(project: Path, sidecar_jar: Path, overlay: dict[str, Any]) -> dict[str, Any]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project), configuration="default"))
        return client.validate_edit(overlay)
    finally:
        client.shutdown()


def _findings(result: dict[str, Any]) -> list[str]:
    return list(result.get("resourceFindings") or [])


def _framework_findings(result: dict[str, Any]) -> list[str]:
    return list(result.get("frameworkFindings") or [])


def _build_findings(result: dict[str, Any]) -> list[str]:
    return list(result.get("buildFindings") or [])


def test_validate_edit_flags_framework_findings_for_annotated_type_delete(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "framework_findings"
    annotation = "src/main/java/org/springframework/stereotype/Component.java"
    service = "src/main/java/com/acme/Service.java"
    _write(
        project,
        annotation,
        "package org.springframework.stereotype;\npublic @interface Component {}\n",
    )
    _write(
        project,
        service,
        "package com.acme;\n"
        "import org.springframework.stereotype.Component;\n"
        "@Component\n"
        "public class Service {}\n",
    )

    result = _validate(project, sidecar_jar, {"deletedFiles": [service]})
    assert result.get("accepted") is True, result
    findings = _framework_findings(result)
    assert any("com.acme.Service" in finding for finding in findings), result
    assert result.get("ready") is False, result


def test_validate_edit_flags_build_findings_for_build_descriptor_change(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "build_findings"
    _write(project, "src/main/java/com/acme/App.java", "package com.acme;\npublic class App {}\n")

    result = _validate(project, sidecar_jar, {"changedFiles": {"pom.xml": "<project></project>\n"}})
    assert result.get("accepted") is True, result
    findings = _build_findings(result)
    assert any("pom.xml" in finding for finding in findings), result
    assert result.get("ready") is True, result


def test_validate_edit_flags_dangling_spring_bean_after_rename_without_resource_rewrite(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "dangling_spring_bean"
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    _write(project, "src/main/resources/applicationContext.xml",
           '<beans>\n  <bean id="svc" class="com.acme.CustomerService"/>\n</beans>\n')

    # Stage a rename of the type's FILE (old removed, new declared) but DO NOT rewrite the XML — the bean class dangles.
    overlay = {
        "changedFiles": {"src/main/java/com/acme/ClientService.java": "package com.acme;\npublic class ClientService {}\n"},
        "renamedFiles": [{"oldPath": rel, "newPath": "src/main/java/com/acme/ClientService.java"}],
    }
    result = _validate(project, sidecar_jar, overlay)
    assert result.get("accepted") is True, result
    findings = _findings(result)
    assert any("com.acme.CustomerService" in f and "applicationContext.xml" in f for f in findings), result
    # A Spring <bean class="..."> is reported with its precise kind, SPRING_BEAN_CLASS: it is an exact reference, but the
    # specific kind (not the generic EXACT_CLASS_NAME) is what enables Spring-aware rewrite/removal downstream.
    assert any("SPRING_BEAN_CLASS" in f for f in findings), findings
    assert result.get("ready") is False, result


def test_validate_edit_flags_dangling_jpa_class_after_delete(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "dangling_jpa"
    rel = "src/main/java/com/acme/Customer.java"
    _write(project, rel, "package com.acme;\npublic class Customer {}\n")
    _write(project, "src/main/resources/META-INF/persistence.xml",
           '<persistence>\n  <persistence-unit name="pu">\n'
           "    <class>com.acme.Customer</class>\n"
           "  </persistence-unit>\n</persistence>\n")

    overlay = {"deletedFiles": [rel]}
    result = _validate(project, sidecar_jar, overlay)
    assert result.get("accepted") is True, result
    findings = _findings(result)
    assert any("com.acme.Customer" in f and "persistence.xml" in f for f in findings), result
    assert result.get("ready") is False, result


def test_validate_edit_no_finding_when_resource_rewritten_in_same_overlay(sidecar_jar: Path, tmp_path: Path) -> None:
    """Honesty gate: when the SAME overlay rewrites the bean class to the new FQN, the reference is correct, not dangling."""
    project = tmp_path / "rewritten_together"
    rel = "src/main/java/com/acme/CustomerService.java"
    xml = "src/main/resources/applicationContext.xml"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    _write(project, xml, '<beans>\n  <bean id="svc" class="com.acme.CustomerService"/>\n</beans>\n')

    overlay = {
        "changedFiles": {
            "src/main/java/com/acme/ClientService.java": "package com.acme;\npublic class ClientService {}\n",
            xml: '<beans>\n  <bean id="svc" class="com.acme.ClientService"/>\n</beans>\n',
        },
        "renamedFiles": [{"oldPath": rel, "newPath": "src/main/java/com/acme/ClientService.java"}],
    }
    result = _validate(project, sidecar_jar, overlay)
    assert result.get("accepted") is True, result
    assert _findings(result) == [], result


def test_validate_edit_no_finding_for_unrelated_edit(sidecar_jar: Path, tmp_path: Path) -> None:
    """Honesty gate: an edit that removes no type referenced by any resource produces NO resource finding."""
    project = tmp_path / "unrelated"
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    _write(project, "src/main/resources/applicationContext.xml",
           '<beans>\n  <bean id="svc" class="com.acme.CustomerService"/>\n</beans>\n')
    other = "src/main/java/com/acme/Helper.java"
    _write(project, other, "package com.acme;\npublic class Helper {}\n")

    # Edit the unrelated Helper (still declares com.acme.Helper); CustomerService is untouched and still resolves.
    overlay = {"changedFiles": {other: "package com.acme;\npublic class Helper { void touched() {} }\n"}}
    result = _validate(project, sidecar_jar, overlay)
    assert result.get("accepted") is True, result
    assert _findings(result) == [], result


def test_validate_edit_no_finding_for_library_type_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    """Honesty gate: a bean class naming a non-project (library-style) type is never flagged, even when an edit removes a
    DIFFERENT project type — removed FQNs come from the edit, so an FQN the edit does not declare-away is never a finding."""
    project = tmp_path / "library_ref"
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    # Bean class points at a type that the project never declares (library-owned); deleting CustomerService must not flag it.
    _write(project, "src/main/resources/applicationContext.xml",
           '<beans>\n  <bean id="ext" class="org.springframework.example.ExternalBean"/>\n</beans>\n')

    overlay = {"deletedFiles": [rel]}
    result = _validate(project, sidecar_jar, overlay)
    assert result.get("accepted") is True, result
    assert not any("org.springframework.example.ExternalBean" in f for f in _findings(result)), result
