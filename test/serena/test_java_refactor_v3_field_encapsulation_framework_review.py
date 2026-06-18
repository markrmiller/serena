"""F6 (framework participation, encapsulate-field path): making a field ``private`` and routing access through generated
accessors is behaviour-preserving for plain Java, but two frameworks bind to the FIELD itself and can change behaviour
when accessors appear (refactor-feature-plan-V3.md §16.2 / §16.3):

* JPA — a managed type (``@Entity``/``@Embeddable``/``@MappedSuperclass``) whose field carries a JPA mapping annotation
  uses FIELD access; the review must warn that the annotation must stay on the field (moving it to the getter flips JPA to
  PROPERTY access and changes the mapping).
* Jackson — a field carrying ``@JsonProperty``/``@JsonIgnore``/``@JsonValue`` drives JSON binding; the new public getter
  is a second accessor Jackson discovers by reflection, so the review must warn to preserve the binding.

Each warning is exact-FQN-gated through the shared ``FrameworkAnnotationCatalog`` (never a package/simple-name heuristic)
and emitted only when a real annotation occurrence is found on the encapsulated field. The honesty gates assert that a
plain field, and a JPA-annotated field on a NON-managed type, produce NO framework warning at all.
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


def _jpa_stubs(project: Path) -> None:
    base = "src/main/java/jakarta/persistence"
    _write(project, f"{base}/Entity.java", "package jakarta.persistence;\npublic @interface Entity { String name() default \"\"; }\n")
    _write(project, f"{base}/Id.java", "package jakarta.persistence;\npublic @interface Id {}\n")
    _write(project, f"{base}/Column.java", "package jakarta.persistence;\npublic @interface Column { String name() default \"\"; }\n")


def _jackson_stubs(project: Path) -> None:
    base = "src/main/java/com/fasterxml/jackson/annotation"
    _write(project, f"{base}/JsonProperty.java", "package com.fasterxml.jackson.annotation;\npublic @interface JsonProperty { String value() default \"\"; }\n")


def _encapsulate(project: Path, sidecar_jar: Path, rel: str, field_name: str) -> dict[str, Any]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project), configuration="default"))
        return client.create_session("encapsulateField", {"relativePath": rel, "fieldName": field_name, "rewriteInternalUsages": True})
    finally:
        client.shutdown()


def _warnings(result: dict[str, Any]) -> list[str]:
    return list(result.get("warnings") or [])


def test_encapsulate_jpa_entity_field_warns_field_access(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "jpa_entity"
    _jpa_stubs(project)
    rel = "src/main/java/com/acme/Customer.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import jakarta.persistence.Entity;\n"
        "import jakarta.persistence.Id;\n"
        "import jakarta.persistence.Column;\n"
        "@Entity\n"
        "public class Customer {\n"
        "    @Id\n"
        "    @Column(name = \"cust_id\")\n"
        "    Long id;\n"
        "}\n",
    )
    result = _encapsulate(project, sidecar_jar, rel, "id")
    assert result.get("accepted") is True, result
    warnings = _warnings(result)
    jpa = [w for w in warnings if w.startswith("JPA field encapsulation")]
    assert len(jpa) == 1, warnings
    assert "com.acme.Customer" in jpa[0], jpa
    assert "jakarta.persistence.Column" in jpa[0], jpa
    assert "jakarta.persistence.Id" in jpa[0], jpa
    assert "FIELD access" in jpa[0] and "PROPERTY access" in jpa[0], jpa


def test_encapsulate_jackson_field_warns_binding(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "jackson_field"
    _jackson_stubs(project)
    rel = "src/main/java/com/acme/Dto.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import com.fasterxml.jackson.annotation.JsonProperty;\n"
        "public class Dto {\n"
        "    @JsonProperty(\"full_name\")\n"
        "    String name;\n"
        "}\n",
    )
    result = _encapsulate(project, sidecar_jar, rel, "name")
    assert result.get("accepted") is True, result
    warnings = _warnings(result)
    jackson = [w for w in warnings if w.startswith("Jackson field encapsulation")]
    assert len(jackson) == 1, warnings
    assert "com.fasterxml.jackson.annotation.JsonProperty" in jackson[0], jackson
    assert "name" in jackson[0], jackson


def test_encapsulate_plain_field_emits_no_framework_warning(sidecar_jar: Path, tmp_path: Path) -> None:
    """Honesty gate: a field bound to no framework gets only the generic V2 caveat, never a vacuous framework note."""
    project = tmp_path / "plain_field"
    rel = "src/main/java/com/acme/Plain.java"
    _write(project, rel, "package com.acme;\npublic class Plain {\n    int count;\n}\n")
    result = _encapsulate(project, sidecar_jar, rel, "count")
    assert result.get("accepted") is True, result
    warnings = _warnings(result)
    assert not any(w.startswith("JPA field encapsulation") for w in warnings), warnings
    assert not any(w.startswith("Jackson field encapsulation") for w in warnings), warnings


def test_encapsulate_jpa_column_on_non_managed_type_emits_no_jpa_warning(sidecar_jar: Path, tmp_path: Path) -> None:
    """Honesty gate: ``@Column`` on a plain (non-``@Entity``) type does not use JPA field access, so NO JPA warning."""
    project = tmp_path / "jpa_unmanaged"
    _jpa_stubs(project)
    rel = "src/main/java/com/acme/NotAnEntity.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import jakarta.persistence.Column;\n"
        "public class NotAnEntity {\n"
        "    @Column(name = \"label\")\n"
        "    String label;\n"
        "}\n",
    )
    result = _encapsulate(project, sidecar_jar, rel, "label")
    assert result.get("accepted") is True, result
    warnings = _warnings(result)
    assert not any(w.startswith("JPA field encapsulation") for w in warnings), warnings
