"""F6 (framework participation, type-rename path): renaming a top-level type changes its *simple name*, and several
frameworks bind to a managed type by that simple name through plain strings the compiler never rewrites. The sidecar
must surface those string bindings as review-required WARNINGS on a type rename — exact-FQN-gated (the renamed type must
itself carry the owning framework annotation, resolved through the compiler, never a package/simple-name heuristic), and
emitted only when a real string occurrence exists (never a vacuous caveat).

Two cases are proven end-to-end through the LIVE sidecar:
  * JPA   — renaming an ``@Entity`` whose default entity name appears in ``@NamedQuery`` JPQL.
  * Spring — renaming a stereotype component whose default bean name appears in a ``@Qualifier`` string.

Plus the honesty gate: a user's OWN ``@Service`` (a same-simple-name lookalike at a different FQN) does NOT trigger the
Spring review, and the pre-existing generic reflection/resource caveat is still emitted (the framework review augments,
never replaces, it).
"""

from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _decl_position(source: str, anchor: str, name: str) -> tuple[int, int]:
    """1-based (line, column) of ``name`` inside the first ``anchor`` (e.g. anchor='class Customer', name='Customer')."""
    flat_index = source.index(anchor) + anchor.index(name)
    prefix = source[:flat_index]
    line = prefix.count("\n") + 1
    column = flat_index - (prefix.rfind("\n") + 1) + 1
    return line, column


def _rename_workspace_edit(sidecar_jar: Path, project: Path, rel: str, name_anchor: str, name: str, new_name: str) -> dict:
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


def _rename_warnings(sidecar_jar: Path, project: Path, rel: str, name_anchor: str, name: str, new_name: str) -> list:
    return _rename_workspace_edit(sidecar_jar, project, rel, name_anchor, name, new_name)["warnings"]


def _jpa_stubs(project: Path) -> None:
    base = "src/main/java/jakarta/persistence"
    _write(project, f"{base}/Entity.java", "package jakarta.persistence;\npublic @interface Entity { String name() default \"\"; }\n")
    _write(
        project,
        f"{base}/NamedQuery.java",
        "package jakarta.persistence;\npublic @interface NamedQuery { String name(); String query(); }\n",
    )
    _write(
        project,
        f"{base}/NamedQueries.java",
        "package jakarta.persistence;\npublic @interface NamedQueries { NamedQuery[] value(); }\n",
    )


def _spring_stubs(project: Path) -> None:
    _write(
        project,
        "src/main/java/org/springframework/stereotype/Service.java",
        "package org.springframework.stereotype;\npublic @interface Service { String value() default \"\"; }\n",
    )
    _write(
        project,
        "src/main/java/org/springframework/beans/factory/annotation/Qualifier.java",
        "package org.springframework.beans.factory.annotation;\npublic @interface Qualifier { String value() default \"\"; }\n",
    )


def test_type_rename_surfaces_jpa_named_query_jpql_review(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "jpa_named_query"
    _jpa_stubs(project)
    rel = "src/main/java/com/acme/Customer.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import jakarta.persistence.Entity;\n"
        "import jakarta.persistence.NamedQuery;\n"
        "@Entity\n"
        '@NamedQuery(name = "Customer.findAll", query = "SELECT c FROM Customer c WHERE c.active = true")\n'
        "public class Customer {}\n",
    )

    warnings = _rename_warnings(sidecar_jar, project, rel, "class Customer", "Customer", "Client")

    jpa = [w for w in warnings if "JPA entity rename" in w]
    assert len(jpa) == 1, warnings
    assert "jakarta.persistence.NamedQuery" in jpa[0], jpa
    assert "Customer" in jpa[0] and "Client" in jpa[0], jpa
    assert "com/acme/Customer.java" in jpa[0], jpa
    # The generic reflection/resource caveat is still emitted (framework review augments, never replaces it).
    assert any("reflection" in w.lower() for w in warnings), warnings


def test_type_rename_surfaces_jpa_named_query_jpql_review_from_other_entity(sidecar_jar: Path, tmp_path: Path) -> None:
    # The JPQL that names the renamed entity lives on a DIFFERENT entity's @NamedQueries container — proving the scan is
    # project-wide and the @NamedQueries container FQN is recognized exactly.
    project = tmp_path / "jpa_cross_entity"
    _jpa_stubs(project)
    _write(
        project,
        "src/main/java/com/acme/Customer.java",
        "package com.acme;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Customer {}\n",
    )
    rel = "src/main/java/com/acme/Order.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import jakarta.persistence.Entity;\n"
        "import jakarta.persistence.NamedQueries;\n"
        "import jakarta.persistence.NamedQuery;\n"
        "@Entity\n"
        "@NamedQueries({\n"
        '  @NamedQuery(name = "Order.byCustomer", query = "SELECT o FROM Order o WHERE o.customer IN (SELECT c FROM Customer c)")\n'
        "})\n"
        "public class Order {}\n",
    )

    # Rename Customer; the breaking JPQL is on Order's @NamedQueries.
    warnings = _rename_warnings(
        sidecar_jar, project, "src/main/java/com/acme/Customer.java", "class Customer", "Customer", "Client"
    )

    jpa = [w for w in warnings if "JPA entity rename" in w]
    assert len(jpa) == 1, warnings
    assert "com/acme/Order.java" in jpa[0], jpa
    assert "NamedQueries" in jpa[0], jpa


def test_type_rename_surfaces_spring_bean_name_review(sidecar_jar: Path, tmp_path: Path) -> None:
    project = tmp_path / "spring_bean_name"
    _spring_stubs(project)
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import org.springframework.stereotype.Service;\n"
        "@Service\n"
        "public class CustomerService {}\n",
    )
    _write(
        project,
        "src/main/java/com/acme/Consumer.java",
        "package com.acme;\n"
        "import org.springframework.beans.factory.annotation.Qualifier;\n"
        "public class Consumer {\n"
        '  @Qualifier("customerService")\n'
        "  Object svc;\n"
        "}\n",
    )

    warnings = _rename_warnings(sidecar_jar, project, rel, "class CustomerService", "CustomerService", "ClientService")

    spring = [w for w in warnings if "Spring component rename" in w]
    assert len(spring) == 1, warnings
    assert "customerService" in spring[0] and "clientService" in spring[0], spring
    assert "org.springframework.beans.factory.annotation.Qualifier" in spring[0], spring
    assert "com/acme/Consumer.java" in spring[0], spring


def test_type_rename_lookalike_service_does_not_trigger_spring_review(sidecar_jar: Path, tmp_path: Path) -> None:
    # Honesty gate: a user's OWN @Service at a different FQN is not Spring's stereotype, so renaming it must NOT emit the
    # Spring bean-name review even though a @Qualifier-lookalike names the same string.
    project = tmp_path / "spring_lookalike"
    _write(
        project,
        "src/main/java/com/acme/anno/Service.java",
        "package com.acme.anno;\npublic @interface Service { String value() default \"\"; }\n",
    )
    _write(
        project,
        "src/main/java/com/acme/anno/Qualifier.java",
        "package com.acme.anno;\npublic @interface Qualifier { String value() default \"\"; }\n",
    )
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import com.acme.anno.Service;\n"
        "@Service\n"
        "public class CustomerService {}\n",
    )
    _write(
        project,
        "src/main/java/com/acme/Consumer.java",
        "package com.acme;\n"
        "import com.acme.anno.Qualifier;\n"
        "public class Consumer {\n"
        '  @Qualifier("customerService")\n'
        "  Object svc;\n"
        "}\n",
    )

    warnings = _rename_warnings(sidecar_jar, project, rel, "class CustomerService", "CustomerService", "ClientService")

    assert not any("Spring component rename" in w for w in warnings), warnings
    # The generic reflection caveat is still present for any type rename.
    assert any("reflection" in w.lower() for w in warnings), warnings


def test_type_rename_plain_entity_without_named_query_emits_no_jpa_review(sidecar_jar: Path, tmp_path: Path) -> None:
    # No-op honesty: an @Entity with no JPQL naming it anywhere produces NO JPA review warning (we never emit a vacuous
    # framework caveat) — only the generic reflection caveat.
    project = tmp_path / "jpa_no_query"
    _jpa_stubs(project)
    rel = "src/main/java/com/acme/Customer.java"
    _write(
        project,
        rel,
        "package com.acme;\nimport jakarta.persistence.Entity;\n@Entity\npublic class Customer {}\n",
    )

    warnings = _rename_warnings(sidecar_jar, project, rel, "class Customer", "Customer", "Client")

    assert not any("JPA entity rename" in w for w in warnings), warnings
    assert any("reflection" in w.lower() for w in warnings), warnings


def test_type_rename_rewrites_spring_bean_class_xml_and_keeps_bean_name_review(sidecar_jar: Path, tmp_path: Path) -> None:
    # Coexistence pin (F6 type-rename path): on a SINGLE stereotype rename the EXACT ``<bean class="...">`` FQN is
    # AUTO-REWRITTEN (HIGH confidence) in the same workspaceEdit that surfaces the review-only Spring bean-name warning.
    # Two distinct framework participations — auto-rewrite (exact FQN) and review (heuristic string) — must both fire and
    # not cannibalize each other: the XML class attribute is rewritten; the @Qualifier bean-name string is NOT rewritten,
    # only warned about.
    project = tmp_path / "spring_bean_class_and_name"
    _spring_stubs(project)
    rel = "src/main/java/com/acme/CustomerService.java"
    _write(
        project,
        rel,
        "package com.acme;\n"
        "import org.springframework.stereotype.Service;\n"
        "@Service\n"
        "public class CustomerService {}\n",
    )
    # EXACT FQN class reference (auto-rewritten) and a default-bean-name @Qualifier string (review-only).
    _write(project, "src/main/resources/applicationContext.xml", '<beans>\n  <bean class="com.acme.CustomerService"/>\n</beans>\n')
    _write(
        project,
        "src/main/java/com/acme/Consumer.java",
        "package com.acme;\n"
        "import org.springframework.beans.factory.annotation.Qualifier;\n"
        "public class Consumer {\n"
        '  @Qualifier("customerService")\n'
        "  Object svc;\n"
        "}\n",
    )

    workspace_edit = _rename_workspace_edit(sidecar_jar, project, rel, "class CustomerService", "CustomerService", "ClientService")

    # 1) The EXACT bean class= FQN is auto-rewritten at HIGH confidence.
    xml_change = next(
        (c for c in workspace_edit["changes"] if c["path"].replace("\\", "/").endswith("applicationContext.xml")),
        None,
    )
    assert xml_change is not None, workspace_edit
    assert len(xml_change["edits"]) == 1, xml_change
    assert xml_change["edits"][0]["newText"] == "com.acme.ClientService", xml_change
    assert xml_change["edits"][0]["kind"].startswith("RESOURCE_REFERENCE:HIGH"), xml_change

    # 2) The Spring bean-name string binding is surfaced as a review warning (NOT rewritten) in the SAME edit.
    spring = [w for w in workspace_edit["warnings"] if "Spring component rename" in w]
    assert len(spring) == 1, workspace_edit["warnings"]
    assert "customerService" in spring[0] and "clientService" in spring[0], spring
    # 3) Honesty: the @Qualifier bean-name string itself is never a text edit (no change group for Consumer.java's string).
    consumer_change = next(
        (c for c in workspace_edit["changes"] if c["path"].replace("\\", "/").endswith("Consumer.java")),
        None,
    )
    consumer_edit_texts = [e["newText"] for e in consumer_change["edits"]] if consumer_change else []
    assert "clientService" not in consumer_edit_texts, consumer_change
