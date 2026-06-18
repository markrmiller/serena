"""F6 (framework participation, type-rename path): renaming a top-level type keeps its package but changes its simple
name, so the type's fully-qualified name changes (``com.acme.Old`` -> ``com.acme.New``). EXACT string-encoded FQN
references the compiler never sees — Spring/CDI ``<bean class="...">`` attributes, JPA ``<class>...</class>`` in
persistence.xml, and ``META-INF/services/<fqn>`` registrations — DO move with the rename and must be auto-rewritten at
HIGH confidence (distinct from the review-only JPQL/bean-name string bindings, which are surfaced as warnings only).

These tests drive the LIVE sidecar through ``semanticRename`` and assert on the produced ``workspaceEdit`` (the resource
text edits land in ``changes`` for the resource file; a service-loader registration lands in ``fileOperations`` as a
rename). The no-op honesty case proves we never emit a phantom resource edit when nothing references the type.
"""

import json
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _decl_position(source: str, anchor: str, name: str) -> tuple[int, int]:
    flat_index = source.index(anchor) + anchor.index(name)
    prefix = source[:flat_index]
    line = prefix.count("\n") + 1
    column = flat_index - (prefix.rfind("\n") + 1) + 1
    return line, column


def _rename_result(sidecar_jar: Path, project: Path, rel: str, name_anchor: str, name: str, new_name: str) -> dict:
    source = (project / rel).read_text(encoding="utf-8")
    line, column = _decl_position(source, name_anchor, name)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project), configuration="default"))
        result = client.preview("semanticRename", {"relativePath": rel, "line": line, "column": column, "newName": new_name})
    finally:
        client.shutdown()
    assert result.get("accepted") is True, result
    return result["workspaceEdit"]


def _change_for(workspace_edit: dict, path_suffix: str) -> dict | None:
    for change in workspace_edit["changes"]:
        if change["path"].replace("\\", "/").endswith(path_suffix):
            return change
    return None


def test_type_rename_rewrites_spring_bean_class_attribute(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "spring_bean_class"
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    xml = "src/main/resources/applicationContext.xml"
    _write(
        project,
        xml,
        '<beans>\n  <bean id="svc" class="com.acme.CustomerService"/>\n</beans>\n',
    )

    workspace_edit = _rename_result(sidecar_jar, project, rel, "class CustomerService", "CustomerService", "ClientService")

    change = _change_for(workspace_edit, "applicationContext.xml")
    assert change is not None, workspace_edit
    edits = change["edits"]
    assert len(edits) == 1, edits
    assert edits[0]["newText"] == "com.acme.ClientService", edits
    assert edits[0]["kind"].startswith("RESOURCE_REFERENCE:HIGH"), edits


def test_type_rename_rewrites_jpa_persistence_xml_class_element(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "jpa_persistence_xml"
    rel = "src/main/java/com/acme/Customer.java"
    _write(project, rel, "package com.acme;\npublic class Customer {}\n")
    xml = "src/main/resources/META-INF/persistence.xml"
    _write(
        project,
        xml,
        '<persistence>\n  <persistence-unit name="pu">\n'
        "    <class>com.acme.Customer</class>\n"
        "  </persistence-unit>\n</persistence>\n",
    )

    workspace_edit = _rename_result(sidecar_jar, project, rel, "class Customer", "Customer", "Client")

    change = _change_for(workspace_edit, "persistence.xml")
    assert change is not None, workspace_edit
    assert any(e["newText"] == "com.acme.Client" for e in change["edits"]), change


def test_type_rename_renames_service_loader_registration_file(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "service_loader"
    rel = "src/main/java/com/acme/PaymentSpi.java"
    _write(project, rel, "package com.acme;\npublic interface PaymentSpi {}\n")
    # The service-interface FQN is encoded in the registration FILENAME, so renaming the interface renames the file.
    _write(project, "src/main/resources/META-INF/services/com.acme.PaymentSpi", "com.acme.StripeProvider\n")
    _write(project, "src/main/java/com/acme/StripeProvider.java", "package com.acme;\npublic class StripeProvider implements PaymentSpi {}\n")

    workspace_edit = _rename_result(sidecar_jar, project, rel, "interface PaymentSpi", "PaymentSpi", "BillingSpi")

    renames = [op for op in workspace_edit["fileOperations"] if op["kind"] == "rename"]
    service_renames = [op for op in renames if op["oldPath"].replace("\\", "/").endswith("META-INF/services/com.acme.PaymentSpi")]
    assert len(service_renames) == 1, workspace_edit
    assert service_renames[0]["newPath"].replace("\\", "/").endswith("META-INF/services/com.acme.BillingSpi"), service_renames
    assert any("ServiceLoader registration" in w for w in workspace_edit["warnings"]), workspace_edit


def test_type_rename_without_resource_references_emits_no_resource_edit(sidecar_jar: Path, tmp_path: Path) -> None:
    # No-op honesty: an unrelated resource file that never names the type produces NO resource edit group for it.
    project = tmp_path / "no_resource_ref"
    rel = "src/main/java/com/acme/Customer.java"
    _write(project, rel, "package com.acme;\npublic class Customer {}\n")
    xml = "src/main/resources/applicationContext.xml"
    _write(project, xml, '<beans>\n  <bean id="other" class="com.acme.Unrelated"/>\n</beans>\n')

    workspace_edit = _rename_result(sidecar_jar, project, rel, "class Customer", "Customer", "Client")

    assert _change_for(workspace_edit, "applicationContext.xml") is None, workspace_edit


def test_type_rename_resource_rewrite_round_trips_through_json(sidecar_jar: Path, tmp_path: Path) -> None:
    # Guard: the workspaceEdit is valid JSON end-to-end with the appended resource edits (no malformed concatenation).
    project = tmp_path / "json_roundtrip"
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(project, rel, "package com.acme;\npublic class CustomerService {}\n")
    _write(project, "src/main/resources/beans.xml", '<beans>\n  <bean class="com.acme.CustomerService"/>\n</beans>\n')

    workspace_edit = _rename_result(sidecar_jar, project, rel, "class CustomerService", "CustomerService", "ClientService")
    # Re-serialize to prove the structure is well-formed and the stats agree with the emitted edits.
    json.dumps(workspace_edit)
    total_edits = sum(len(c["edits"]) for c in workspace_edit["changes"])
    assert workspace_edit["stats"]["editCount"] == total_edits, workspace_edit
