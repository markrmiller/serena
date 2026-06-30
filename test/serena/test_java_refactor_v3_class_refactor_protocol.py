"""Live-sidecar coverage for the V3 ``classRefactor.*`` protocol (refactor-feature-plan-V3.md §8–§10).

These boot the real Java sidecar jar and drive the three class-shape refactorings end to end via
:class:`~serena.java_refactor_v3.class_refactor_client.ClassRefactorClient`. They prove that member resolution and the
conservative refusal lists are computed by javac inside the sidecar (not by a Python heuristic), that each accepted plan
returns a ``workspaceEdit`` (``changes`` + ``fileOperations``) which the sidecar's before/after javac validator has
already accepted (``diagnosticDeltaValidated: true``), and that the §8.4/§9.4/§10.3 refusals are surfaced with their
canonical refusal ``code``.

Capabilities exercised (mapping to the Phase 4 checklist):
    §8  test_extract_class_moves_field_and_method        — extract class happy path (field + forwarding method)
    §8.4 test_extract_class_refuses_super_dependency     — moved method depends on source ``super`` (refused)
    §9  test_extract_superclass_hoists_common_method     — extract superclass happy path (two siblings)
    §9.4 test_extract_superclass_refuses_existing_super  — a selected class already extends a non-Object superclass
    §10 test_replace_inheritance_forwards_methods        — replace inheritance with delegation happy path
    §10.3 test_replace_inheritance_refuses_generic_super — generic superclass is not representable as a delegate field
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _class_refactor(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[ClassRefactorClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield ClassRefactorClient(client)
    finally:
        client.shutdown()


def _changed_paths(result: dict) -> set[str]:
    return {change["path"] for change in result["workspaceEdit"]["changes"]}


def _created_paths(result: dict) -> set[str]:
    return {op["path"] for op in result["workspaceEdit"]["fileOperations"] if op.get("kind") == "create"}


def _new_texts(result: dict) -> str:
    """The concatenated replacement text of every edit — what the refactor actually writes into the sources."""
    return "".join(
        edit["newText"]
        for change in result["workspaceEdit"]["changes"]
        for edit in change.get("edits", [])
    )


def _created_text(result: dict) -> str:
    """The full source of every newly created file (the synthesized collaborator(s))."""
    return "".join(
        op.get("content", "")
        for op in result["workspaceEdit"]["fileOperations"]
        if op.get("kind") == "create"
    )


# ── §8 extract class: happy path ─────────────────────────────────────────────────────────────────────────────────


def test_extract_class_moves_field_and_method(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # rate (with initializer) and tax(double) form a self-contained collaborator; tax uses rate, label stays behind.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    private String label = \"p\";\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "    String label() { return label; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    # A brand-new collaborator file is created in the same package…
    created = _created_paths(result)
    assert any(path.endswith("com/acme/TaxCalc.java") for path in created), result
    # …and the source class is rewritten (delegate field + forwarding body).
    assert any(path.endswith("PriceService.java") for path in _changed_paths(result)), result


# ── §8.4 extract class: refuse a method that depends on source super ─────────────────────────────────────────────


def test_extract_class_refuses_super_dependency(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    protected int seed() { return 5; }\n}\n",
    )
    # compute() reaches into the source's superclass via super.seed(); §8.4 forbids moving it to a collaborator.
    _write(
        tmp_path,
        "src/main/java/com/acme/Derived.java",
        "package com.acme;\n"
        "public class Derived extends Base {\n"
        "    int compute() { return super.seed() + 1; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Derived.java",
            "Computer",
            ["method:compute()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_uses_super", result


# ── §8 (F10) extract class: constructor-injected collaborator dependencies ────────────────────────────────────────


def test_extract_class_injects_constructor_assigned_fields(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §8.1 OrderService: taxPolicy/discountPolicy have NO field initializer — they are assigned in the single
    # constructor from same-named parameters. Moving them with their consumers must (a) synthesize a generated
    # constructor on the collaborator that takes the same dependencies, and (b) rewrite the source constructor to
    # build the delegate as `new OrderPricing(taxPolicy, discountPolicy)` — not emit a dangling `new OrderPricing()`.
    _write(tmp_path, "src/main/java/com/acme/TaxPolicy.java", "package com.acme;\npublic class TaxPolicy {\n    int tax(int amount) { return amount / 10; }\n}\n")
    _write(tmp_path, "src/main/java/com/acme/DiscountPolicy.java", "package com.acme;\npublic class DiscountPolicy {\n    int discount(int amount) { return amount / 20; }\n}\n")
    _write(
        tmp_path,
        "src/main/java/com/acme/OrderService.java",
        "package com.acme;\n"
        "public class OrderService {\n"
        "    private final TaxPolicy taxPolicy;\n"
        "    private final DiscountPolicy discountPolicy;\n"
        "    public OrderService(TaxPolicy taxPolicy, DiscountPolicy discountPolicy) {\n"
        "        this.taxPolicy = taxPolicy;\n"
        "        this.discountPolicy = discountPolicy;\n"
        "    }\n"
        "    int calculateTax(int amount) { return taxPolicy.tax(amount); }\n"
        "    int calculateDiscount(int amount) { return discountPolicy.discount(amount); }\n"
        "    int calculateTotal(int amount) { return amount - calculateTax(amount) - calculateDiscount(amount); }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/OrderService.java",
            "OrderPricing",
            ["field:taxPolicy", "field:discountPolicy", "method:calculateTax(int)", "method:calculateDiscount(int)"],
        )

    assert result.get("accepted") is True, result
    # The whole composed edit (generated ctor + rewritten source ctor) really compiles before/after.
    assert result.get("diagnosticDeltaValidated") is True, result
    collaborator = _created_text(result)
    # The collaborator carries a generated constructor that takes the injected dependencies and stores them.
    assert "public OrderPricing(TaxPolicy taxPolicy, DiscountPolicy discountPolicy)" in collaborator, collaborator
    assert "this.taxPolicy = taxPolicy;" in collaborator, collaborator
    assert "this.discountPolicy = discountPolicy;" in collaborator, collaborator
    # The source constructor now builds the delegate by forwarding the same dependencies — never a no-arg `new`.
    source = _new_texts(result)
    assert "new OrderPricing(taxPolicy, discountPolicy)" in source, source


def test_extract_class_allows_selected_method_dependency(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Dependency closure is satisfied when BOTH the consumer and its callee are part of the moved set: compute() calls
    # helper(), and both are selected, so the collaborator is self-contained and the move is accepted.
    _write(
        tmp_path,
        "src/main/java/com/acme/Calc.java",
        "package com.acme;\n"
        "public class Calc {\n"
        "    int helper() { return 7; }\n"
        "    int compute() { return helper() * 2; }\n"
        "    int other() { return 1; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Calc.java",
            "Computer",
            ["method:helper()", "method:compute()"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("com/acme/Computer.java") for path in _created_paths(result)), result


def test_extract_class_passes_retained_field_as_constructor_parameter(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §8.3 step 4 PASS_AS_CONSTRUCTOR_PARAMETER: compute() reads source field `base` which is NOT in the moved set.
    # Rather than refusing, `base` is injected into the collaborator's constructor (held as a same-named field) and the
    # source builds the delegate forwarding `this.base`, so the moved body's bare `base` reference resolves locally.
    _write(
        tmp_path,
        "src/main/java/com/acme/Acc.java",
        "package com.acme;\n"
        "public class Acc {\n"
        "    private final int base;\n"
        "    public Acc(int base) { this.base = base; }\n"
        "    int compute() { return base * 2; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Acc.java",
            "Computer",
            ["method:compute()"],
        )

    assert result.get("accepted") is True, result
    # The whole composed edit really compiles before/after (the moved body still resolves `base`).
    assert result.get("diagnosticDeltaValidated") is True, result
    collaborator = _created_text(result)
    # The retained field is passed as a constructor parameter and held as a same-named collaborator field.
    assert "private final int base;" in collaborator, collaborator
    assert "public Computer(int base)" in collaborator, collaborator
    assert "this.base = base;" in collaborator, collaborator
    # The source builds the delegate forwarding its own retained field value.
    source = _new_texts(result)
    assert "new Computer(this.base)" in source, source


def test_extract_class_keeps_retained_method_as_delegate_call(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §8.3 step 4 KEEP_DELEGATE_CALL: compute() calls source method helper() which stays behind. Rather than refusing,
    # the collaborator takes the source instance as a back-reference (`owner`) and the moved body's `helper()` call is
    # rewritten to `owner.helper()`. A back-reference requires a single source constructor to thread `this` through.
    _write(
        tmp_path,
        "src/main/java/com/acme/Caller.java",
        "package com.acme;\n"
        "public class Caller {\n"
        "    private final int seed;\n"
        "    public Caller(int seed) { this.seed = seed; }\n"
        "    int helper() { return seed + 3; }\n"
        "    int compute() { return helper() * 4; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Caller.java",
            "Computer",
            ["method:compute()"],
        )

    assert result.get("accepted") is True, result
    # The composed edit compiles: the moved body reaches the retained method through the back-reference.
    assert result.get("diagnosticDeltaValidated") is True, result
    collaborator = _created_text(result)
    # The collaborator holds the source as `owner` and rewrites the retained call through it.
    assert "private final Caller owner;" in collaborator, collaborator
    assert "owner.helper()" in collaborator, collaborator
    # The source builds the delegate passing itself as the back-reference.
    source = _new_texts(result)
    assert "new Computer(this)" in source, source


def test_extract_class_refuses_retained_field_without_constructor(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §8.4 genuinely-unrepresentable: compute() reads retained field `base`, so it must be constructor-injected into the
    # collaborator — but the source declares no explicit constructor, so there is no analyzable constructor body to thread
    # `this.base` through and preserve the delegate's initialization order. This is an honest block (NOT a closure-policy
    # refusal that classification would dissolve); the SAME source gains a constructor in the success test above.
    _write(
        tmp_path,
        "src/main/java/com/acme/Acc.java",
        "package com.acme;\n"
        "public class Acc {\n"
        "    private int base = 10;\n"
        "    int compute() { return base * 2; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Acc.java",
            "Computer",
            ["method:compute()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_constructor_unanalyzable", result


def test_extract_class_refuses_multiple_constructors(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # An injected (initializer-less) field needs a constructor to assign it; with more than one source constructor the
    # injection is ambiguous, so the move is refused rather than guessing which constructor to mirror.
    _write(tmp_path, "src/main/java/com/acme/Dep.java", "package com.acme;\npublic class Dep {\n    int v() { return 1; }\n}\n")
    _write(
        tmp_path,
        "src/main/java/com/acme/Svc.java",
        "package com.acme;\n"
        "public class Svc {\n"
        "    private final Dep dep;\n"
        "    public Svc(Dep dep) { this.dep = dep; }\n"
        "    public Svc() { this.dep = null; }\n"
        "    int use() { return dep.v(); }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Svc.java",
            "Worker",
            ["field:dep", "method:use()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_multiple_constructors", result


def test_extract_class_refuses_non_parameter_constructor_init(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The injected field is assigned from a computed expression (seed * 2), not a plain constructor parameter, so the
    # collaborator's generated constructor cannot faithfully reproduce the initialization — refuse instead of guessing.
    _write(
        tmp_path,
        "src/main/java/com/acme/Seeded.java",
        "package com.acme;\n"
        "public class Seeded {\n"
        "    private final int dep;\n"
        "    public Seeded(int seed) { this.dep = seed * 2; }\n"
        "    int use() { return dep; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Seeded.java",
            "Worker",
            ["field:dep", "method:use()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_constructor_init_not_simple", result


# ── §9 extract superclass: happy path ────────────────────────────────────────────────────────────────────────────


def test_extract_superclass_hoists_common_method(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    for name in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} {{\n"
            f"    String describe() {{ return \"animal\"; }}\n"
            f"    int legs() {{ return 4; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["method:describe()"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    # The new abstract superclass is created…
    assert any(path.endswith("com/acme/Animal.java") for path in _created_paths(result)), result
    # …and both siblings are rewritten (describe() removed, `extends Animal` added).
    changed = _changed_paths(result)
    assert any(path.endswith("Dog.java") for path in changed), result
    assert any(path.endswith("Cat.java") for path in changed), result


# ── §9.4 extract superclass: refuse when a selected class already extends a non-Object superclass ────────────────


def test_extract_superclass_refuses_existing_super(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Vehicle.java", "package com.acme;\npublic class Vehicle {}\n")
    # Car already extends Vehicle; §9.4 refuses slotting a new superclass into an existing hierarchy unverified.
    _write(
        tmp_path,
        "src/main/java/com/acme/Car.java",
        "package com.acme;\npublic class Car extends Vehicle {\n    String describe() { return \"v\"; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Bike.java",
        "package com.acme;\npublic class Bike {\n    String describe() { return \"v\"; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Car.java", "src/main/java/com/acme/Bike.java"],
            "Describable",
            ["method:describe()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_existing_superclass", result


# ── §9 (F11) extract superclass: a single source class is a legal target (no two-sibling minimum) ────────────────


def test_extract_superclass_accepts_single_class(sidecar_jar: Path, sidecar_java_cmd: str, tmp_path: Path) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Robot.java",
        """package com.acme;
public class Robot {
    public String describe() { return "robot"; }
}
""",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Robot.java"],
            "Machine",
            ["method:describe()"],
        )

    assert result["accepted"] is True
    assert result["risk"] == "needs_review"
    touched = {change["path"] for change in result["workspaceEdit"]["changes"]}
    touched |= {op["path"] for op in result["workspaceEdit"]["fileOperations"]}
    assert "src/main/java/com/acme/Robot.java" in touched
    assert "src/main/java/com/acme/Machine.java" in touched

def test_extract_superclass_interposes_shared_superclass(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Circle and Square both already extend Shape. The new superclass is slotted *between* them and Shape: the generated
    # class extends Shape, and each sibling is rewritten to extend the interposed class.
    _write(tmp_path, "src/main/java/com/acme/Shape.java", "package com.acme;\npublic class Shape {}\n")
    for name in ("Circle", "Square"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} extends Shape {{\n"
            f"    String kind() {{ return \"shape\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Circle.java", "src/main/java/com/acme/Square.java"],
            "AbstractShape",
            ["method:kind()"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    # The generated superclass keeps the existing Shape parent…
    assert "extends Shape" in _created_text(result), result
    # …and both siblings now extend the interposed class.
    assert "extends AbstractShape" in _new_texts(result), result


# ── §9 (F11) extract superclass: preserve each subclass's own implements clause ──────────────────────────────────


def test_extract_superclass_preserves_implements(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Named.java", "package com.acme;\npublic interface Named { String name(); }\n")
    for cls in ("Apple", "Pear"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{cls}.java",
            f"package com.acme;\n"
            f"public class {cls} implements Named {{\n"
            f"    public String name() {{ return \"fruit\"; }}\n"
            f"    String describe() {{ return \"fruit\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Apple.java", "src/main/java/com/acme/Pear.java"],
            "Fruit",
            ["method:describe()"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    new_text = _new_texts(result)
    # The new base is swapped in while the implements clause survives verbatim.
    assert "extends Fruit" in new_text, result
    assert "implements Named" in new_text, result


# ── §9 (F11) extract superclass: propagate a constructor for an initializer-less hoisted field ───────────────────


def test_extract_superclass_propagates_constructor(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # `name` has no initializer — it is constructor-assigned in each sibling. Hoisting it generates a protected
    # superclass constructor that receives `name`, and each sibling constructor forwards it via super(name).
    for cls in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{cls}.java",
            f"package com.acme;\n"
            f"public class {cls} {{\n"
            f"    protected String name;\n"
            f"    public {cls}(String name) {{ this.name = name; }}\n"
            f"    String describe() {{ return name; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["field:name", "method:describe()"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    created = _created_text(result)
    # A generated protected constructor on the new superclass receives and assigns the hoisted field…
    assert "protected Animal(String name)" in created, result
    assert "this.name = name;" in created, result
    # …and each sibling constructor forwards through super(name).
    assert "super(name)" in _new_texts(result), result


# ── §9 (F11) extract superclass: refuse constructor propagation when a common superclass already exists ──────────


def test_extract_superclass_refuses_constructor_with_existing_super(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(tmp_path, "src/main/java/com/acme/Base.java", "package com.acme;\npublic class Base {}\n")
    for cls in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{cls}.java",
            f"package com.acme;\n"
            f"public class {cls} extends Base {{\n"
            f"    protected String name;\n"
            f"    public {cls}(String name) {{ this.name = name; }}\n"
            f"    String describe() {{ return name; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["field:name", "method:describe()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_constructor_with_existing_super", result


# ── §9 (F11) extract superclass: make_abstract hoists an abstract declaration, subclass keeps a concrete @Override ──


def test_extract_superclass_make_abstract_keeps_concrete_override(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # describe() differs per sibling, so it cannot be hoisted wholesale; make_abstract pulls up only an ABSTRACT
    # declaration and leaves each concrete body in place (now annotated @Override). area() (a field) stays put.
    _write(
        tmp_path,
        "src/main/java/com/acme/Dog.java",
        "package com.acme;\n"
        "public class Dog {\n"
        "    public String describe() { return \"dog\"; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Cat.java",
        "package com.acme;\n"
        "public class Cat {\n"
        "    public String describe() { return \"cat\"; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["method:describe()"],
            make_abstract=True,
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    # The superclass declares describe() abstract (signature + ';', no body) and is itself abstract.
    created = _created_text(result)
    assert "abstract class Animal" in created, result
    assert "abstract" in created and "describe();" in created, result
    assert "return" not in created, result  # the concrete bodies were NOT moved into the superclass
    # Each subclass KEEPS its concrete body (the @Override insertion is additive; the body is never removed).
    new_text = _new_texts(result)
    assert "@Override" in new_text, result


# ── §9 (F11) extract superclass: interface-alternative suggestion when the base would be state-free + all-abstract ──


def test_extract_superclass_suggests_interface_alternative(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The extracted base would hold no fields and only abstract methods — a pure contract. The planner accepts the
    # extraction but surfaces an interface_alternative_suggested warning so the caller can reconsider.
    for cls in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{cls}.java",
            f"package com.acme;\n"
            f"public class {cls} {{\n"
            f"    public String describe() {{ return \"{cls.lower()}\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Describable",
            ["method:describe()"],
            make_abstract=True,
        )

    assert result.get("accepted") is True, result
    warnings = result.get("warnings") or []
    assert any("interface_alternative_suggested" in str(w) for w in warnings), result


# ── §9 (F11) extract superclass: a hoisted-field base (carries state) does NOT suggest an interface ───────────────


def test_extract_superclass_field_pull_up_does_not_suggest_interface(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A base that pulls up a field holds state, so an interface is NOT an appropriate alternative — no suggestion.
    for cls in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{cls}.java",
            f"package com.acme;\n"
            f"public class {cls} {{\n"
            f"    protected int legs = 4;\n"
            f"    public String describe() {{ return \"{cls.lower()}\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["field:legs", "method:describe()"],
            make_abstract=True,
        )

    assert result.get("accepted") is True, result
    # The pulled-up field is present in the new base (it carries state).
    assert "int legs" in _created_text(result), result
    warnings = result.get("warnings") or []
    assert not any("interface_alternative_suggested" in str(w) for w in warnings), result


# ── §10 replace inheritance with delegation: happy path ──────────────────────────────────────────────────────────


def test_replace_inheritance_forwards_methods(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public int value() { return 42; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\npublic class Child extends Base {\n    public int other() { return 1; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", confirm_public_api_change=True
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result.get("superclass") == "com.acme.Base", result
    # The extends clause is dropped and a delegate + forwarder are inserted into Child.
    assert any(path.endswith("Child.java") for path in _changed_paths(result)), result


# ── §10.3 replace inheritance: refuse a generic superclass (not representable as a delegate field) ───────────────


def test_replace_inheritance_refuses_generic_super(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/Box.java",
        "package com.acme;\npublic class Box<T> {\n    public int size() { return 0; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/IntBox.java",
        "package com.acme;\npublic class IntBox extends Box<Integer> {\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation("src/main/java/com/acme/IntBox.java")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "replace_inheritance_generic_superclass", result


# ── §10.3 (R11) replace inheritance: an override that calls super is refused DIRECTLY, not via the javac backstop ──


def test_replace_inheritance_refuses_override_that_calls_super(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Child.value() overrides Base.value() and calls super.value(). Severing `extends Base` would retarget super.value()
    # at the delegate, but a plain Base instance dispatches its own methods internally — the override drops out of the
    # base's self-call path and behavior changes silently. §10.3 names this hazard; it must be refused with the specific
    # design-named code from the element/AST model (confirm_public_api_change=True clears the earlier public-API gate so
    # the override-super check is what produces the refusal, proving direct detection rather than the javac fallback).
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public int value() { return 42; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\n"
        "public class Child extends Base {\n"
        "    @Override public int value() { return super.value() + 1; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", confirm_public_api_change=True
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "replace_inheritance_override_calls_super", result


def test_replace_inheritance_allows_override_without_super_call(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A plain override that does NOT call super is fine: it carries its own implementation, so delegation preserves
    # behavior. This guards the R11 detector against over-refusing every override.
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public int value() { return 42; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\n"
        "public class Child extends Base {\n"
        "    @Override public int value() { return 7; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", confirm_public_api_change=True
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result


# ── §10 delegation depth: forward methods inherited TRANSITIVELY through the superclass chain ─────────────────────


def test_replace_inheritance_forwards_transitively_inherited_method(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Child extends Mid extends Grand. g() is inherited from the *grandparent*; before the depth fix only Mid's own m()
    # was forwarded and g() silently vanished from Child's public API. Both must now be forwarded onto the delegate.
    _write(
        tmp_path,
        "src/main/java/com/acme/Grand.java",
        "package com.acme;\npublic class Grand {\n    public int g() { return 1; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Mid.java",
        "package com.acme;\npublic class Mid extends Grand {\n    public int m() { return 2; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\npublic class Child extends Mid {\n    public int own() { return 3; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", confirm_public_api_change=True
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result.get("superclass") == "com.acme.Mid", result
    texts = _new_texts(result)
    # The delegate is named after the immediate base (decapitalised). The grandparent's g() and the parent's m() are
    # BOTH forwarded through it — proving the chain walk reached past the direct superclass.
    assert "mid.g()" in texts, texts
    assert "mid.m()" in texts, texts


def test_replace_inheritance_forwards_only_selected_members(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # `members` is a real filter, not a no-op: selecting only a() must forward a() and leave b() behind.
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public int a() { return 1; }\n    public int b() { return 2; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\npublic class Child extends Base {\n    public int own() { return 0; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", members=["method:a"], confirm_public_api_change=True
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    texts = _new_texts(result)
    assert "base.a()" in texts, texts
    assert "base.b()" not in texts, texts


def test_replace_inheritance_honors_custom_delegate_field_name(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # `delegate_field_name` is honored verbatim: the field and every forwarder reference the caller-chosen name.
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        "package com.acme;\npublic class Base {\n    public int value() { return 42; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        "package com.acme;\npublic class Child extends Base {\n    public int own() { return 0; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java", delegate_field_name="backing", confirm_public_api_change=True
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    texts = _new_texts(result)
    assert "backing.value()" in texts, texts
    assert "base.value()" not in texts, texts  # the default name is not used when one is supplied


# ── §8 extract class: leaveDelegateMethods and targetPackage are honored, not silent no-ops ──────────────────────


def test_extract_class_without_delegates_refuses_public_method(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Flipping leave_delegate_methods to False changes the outcome: a public method can no longer be silently removed
    # from the source's API, so the op is refused with the canonical code (proving the flag is consulted).
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    public double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
            leave_delegate_methods=False,
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_public_api_without_delegates", result


def test_extract_class_into_target_package(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # target_package places the collaborator in a different package and qualifies the delegate type accordingly.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    public double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
            target_package="com.acme.calc",
            confirm_public_api_change=True,
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result.get("newClass") == "com.acme.calc.TaxCalc", result
    assert any(path.endswith("com/acme/calc/TaxCalc.java") for path in _created_paths(result)), result


# ── §9 extract superclass: targetPackage is honored ─────────────────────────────────────────────────────────────


def test_extract_superclass_into_target_package(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    for name in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} {{\n"
            f"    public String describe() {{ return \"animal\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["method:describe()"],
            target_package="com.acme.base",
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result.get("superclass") == "com.acme.base.Animal", result


# ── §8 (F-EXTC) extract class: external-usage of a removed member is refused early ─────────────────────────────────


def test_extract_class_refuses_external_field_usage(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # `total` is package-private (no modifier) and is read by an EXTERNAL same-package class. A moved field is always
    # deleted from the source with no accessor left behind, so the external read would not compile. F-EXTC must refuse
    # early with a precise, member-attributed code instead of leaving it to the late generic javac delta.
    _write(
        tmp_path,
        "src/main/java/com/acme/Cart.java",
        "package com.acme;\n"
        "public class Cart {\n"
        "    int total = 0;\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Checkout.java",
        "package com.acme;\n"
        "public class Checkout {\n"
        "    int read(Cart cart) { return cart.total; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Cart.java",
            "Totals",
            ["field:total"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_external_usage", result
    # Nothing is written — a refused plan changes no files and creates no collaborator.
    assert result.get("changedFiles") == [], result
    if "workspaceEdit" in result:
        assert not any(path.endswith("Totals.java") for path in _created_paths(result)), result


def test_extract_class_refuses_external_field_usage_even_with_update_usages(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A moved FIELD read from outside has no behavior-preserving rewrite (no forwarding contract for a bare field access),
    # so it stays a refusal even under update_usages — the rewrite path is methods-only. This pins that update_usages does
    # not silently weaken the field-removal safety guard.
    _write(tmp_path, "src/main/java/com/acme/Cart.java", "package com.acme;\npublic class Cart {\n    int total = 0;\n}\n")
    _write(
        tmp_path,
        "src/main/java/com/acme/Checkout.java",
        "package com.acme;\npublic class Checkout {\n    int read(Cart cart) { return cart.total; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Cart.java",
            "Totals",
            ["field:total"],
            update_usages=True,
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_external_usage", result


def test_extract_class_refuses_external_nonpublic_method_removal(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # `helper()` is package-private (no modifier) and called by an EXTERNAL same-package class. With
    # leave_delegate_methods=False AND update_usages=False the method is removed from the source with no forwarding stub,
    # so the external call would not compile — refused early with the F-EXTC code (distinct from the public-API refusal).
    _write(
        tmp_path,
        "src/main/java/com/acme/Service.java",
        "package com.acme;\n"
        "public class Service {\n"
        "    int helper() { return 7; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Consumer.java",
        "package com.acme;\n"
        "public class Consumer {\n"
        "    int use(Service service) { return service.helper(); }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Service.java",
            "Helper",
            ["method:helper()"],
            leave_delegate_methods=False,
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_external_usage", result
    assert result["refusal"]["code"] != "extract_class_public_api_without_delegates", result


def test_extract_class_rewrites_external_method_usage_with_update_usages(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §8.3 step 8: with leave_delegate_methods=False AND update_usages=True the removed package-private `helper()` is NOT
    # refused. A public delegate accessor is generated on the source and the EXTERNAL caller `service.helper()` is
    # rewritten to `service.helper().helper()` (receiver -> delegate accessor -> moved method). The composed edit must
    # really compile before/after.
    _write(
        tmp_path,
        "src/main/java/com/acme/Service.java",
        "package com.acme;\n"
        "public class Service {\n"
        "    int helper() { return 7; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Consumer.java",
        "package com.acme;\n"
        "public class Consumer {\n"
        "    int use(Service service) { return service.helper(); }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Service.java",
            "Helper",
            ["method:helper()"],
            leave_delegate_methods=False,
            update_usages=True,
        )

    assert result.get("accepted") is True, result
    # The whole composed edit (removed method + generated accessor + rewritten external caller) compiles before/after.
    assert result.get("diagnosticDeltaValidated") is True, result
    # The external caller file is among the changed files (it was rewritten, not just the source).
    assert any(path.endswith("Consumer.java") for path in _changed_paths(result)), result
    # A public delegate accessor is generated on the source so external callers can reach the moved behavior.
    source = _new_texts(result)
    assert "public Helper helper()" in source, source
    # The external call site is routed through the delegate accessor.
    assert ".helper()" in source, source


def test_extract_class_allows_private_field_with_internal_reader(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Regression pin for F-EXTC's no-false-refusal guarantee: a PRIVATE field with only an in-type reader has no external
    # reference, so the new external-usage guard is a no-op and the extraction is still accepted. `rate` moves with its
    # sole consumer `tax`, which is the only reader.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
        )

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("com/acme/TaxCalc.java") for path in _created_paths(result)), result


# ── §8/§9 (R10) extract class / superclass: semantic name/file/type collision preflight ───────────────────────────
# The planner must surface a target name/file/type clash as an operation-specific structured refusal BEFORE producing
# an accepted plan — not let a generic applier file-create staging failure leak after the op already reports accepted.


def test_extract_class_refuses_target_type_collision(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A type named com.acme.TaxCalc already exists; extracting a NEW class TaxCalc would collide with it.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/TaxCalc.java",
        "package com.acme;\npublic class TaxCalc {\n    int unrelated() { return 1; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_target_type_exists", result


def test_extract_class_refuses_target_file_collision(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The target file path TaxCalc.java is already occupied by a file that declares a DIFFERENT (package-private) type,
    # so no com.acme.TaxCalc type exists to trip the type check — only the on-disk file collision does.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/TaxCalc.java",
        "package com.acme;\nclass TaxCalcNotes {\n    int unrelated() { return 1; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["field:rate", "method:tax(double)"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_target_file_exists", result


def test_extract_class_refuses_target_is_source(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Extracting into a class with the SAME fully-qualified name as the source would overwrite the source itself.
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        "package com.acme;\n"
        "public class PriceService {\n"
        "    private final double rate = 0.2;\n"
        "    double tax(double amount) { return amount * rate; }\n"
        "}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "PriceService",
            ["field:rate", "method:tax(double)"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_class_target_is_source", result


def test_extract_superclass_refuses_target_type_collision(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A type named com.acme.Animal already exists; hoisting into a NEW superclass Animal would collide with it.
    for name in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} {{\n"
            f"    String describe() {{ return \"animal\"; }}\n"
            f"}}\n",
        )
    _write(
        tmp_path,
        "src/main/java/com/acme/Animal.java",
        "package com.acme;\npublic class Animal {\n    int unrelated() { return 1; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["method:describe()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_target_type_exists", result


def test_extract_superclass_refuses_target_file_collision(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The target file Animal.java is occupied by a file declaring a DIFFERENT (package-private) type, so no
    # com.acme.Animal type exists to trip the type check — only the on-disk file collision does.
    for name in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} {{\n"
            f"    String describe() {{ return \"animal\"; }}\n"
            f"}}\n",
        )
    _write(
        tmp_path,
        "src/main/java/com/acme/Animal.java",
        "package com.acme;\nclass AnimalNotes {\n    int unrelated() { return 1; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Animal",
            ["method:describe()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_target_file_exists", result


def test_extract_superclass_refuses_target_is_selected_class(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # Naming the new superclass after one of the selected classes (same FQN) would overwrite that selected class.
    for name in ("Dog", "Cat"):
        _write(
            tmp_path,
            f"src/main/java/com/acme/{name}.java",
            f"package com.acme;\n"
            f"public class {name} {{\n"
            f"    String describe() {{ return \"animal\"; }}\n"
            f"}}\n",
        )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["src/main/java/com/acme/Dog.java", "src/main/java/com/acme/Cat.java"],
            "Dog",
            ["method:describe()"],
        )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_target_is_source", result


def test_extract_superclass_refuses_divergent_same_name_methods(sidecar_jar, tmp_path, sidecar_java_cmd):
    _write(
        tmp_path,
        "src/main/java/com/acme/A.java",
        """package com.acme;
public class A {
  public String name() { return "A"; }
}
""",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/B.java",
        """package com.acme;
public class B {
  public String name() { return "B"; }
}
""",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["com.acme.A", "com.acme.B"],
            "BaseName",
            ["name"],
            make_abstract=False,
        )

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "extract_superclass_member_not_equivalent", result


def test_extract_superclass_refuses_ambiguous_overloaded_selector(sidecar_jar, tmp_path, sidecar_java_cmd):
    _write(
        tmp_path,
        "src/main/java/com/acme/A.java",
        "package com.acme;\npublic class A {\n  public String name() { return \"same\"; }\n  public String name(String suffix) { return suffix; }\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/B.java",
        "package com.acme;\npublic class B {\n  public String name() { return \"same\"; }\n  public String name(String suffix) { return suffix; }\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, sidecar_java_cmd) as client:
        result = client.extract_superclass(
            ["com.acme.A", "com.acme.B"],
            "BaseName",
            ["name"],
        )

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "extract_superclass_member_ambiguous", result


def test_extract_class_refuses_moved_field_initializer_dependency(sidecar_jar, tmp_path, sidecar_java_cmd):
    _write(
        tmp_path,
        "src/main/java/com/acme/Source.java",
        """package com.acme;
public class Source {
  private int seed = 1;
  private int moved = seed + 1;
}
""",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/Source.java",
            "Extracted",
            ["moved"],
        )

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "extract_class_unselected_field_dependency", result


def test_extract_class_refuses_retained_field_initializer_dependency(sidecar_jar, tmp_path, sidecar_java_cmd):
    source = "src/main/java/com/acme/Source.java"
    _write(
        tmp_path,
        source,
        "package com.acme;\npublic class Source {\n  private int moved = 1;\n  private int retained = moved + 1;\n}\n",
    )
    with _class_refactor(sidecar_jar, tmp_path, sidecar_java_cmd) as client:
        result = client.extract_class(source, "Extracted", ["moved"])

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "extract_class_retained_field_dependency", result


def test_replace_inheritance_refuses_inherited_generic_method(sidecar_jar, tmp_path, sidecar_java_cmd):
    _write(
        tmp_path,
        "src/main/java/com/acme/Base.java",
        """package com.acme;
public class Base {
  public <T> T id(T value) { return value; }
}
""",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Child.java",
        """package com.acme;
public class Child extends Base {
}
""",
    )
    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.replace_inheritance_with_delegation(
            "src/main/java/com/acme/Child.java",
            confirm_public_api_change=True,
        )

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "replace_inheritance_generic_method", result


def test_extract_class_cross_package_qualifies_owner_back_reference(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _write(
        tmp_path,
        "src/main/java/com/acme/PriceService.java",
        """
package com.acme;

public class PriceService {
    public PriceService() {
    }

    public int helper(int cents) {
        return cents / 10;
    }

    public int tax(int cents) {
        return helper(cents);
    }

    public int total(int cents) {
        return tax(cents);
    }
}
""".strip(),
    )

    with _class_refactor(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.extract_class(
            "src/main/java/com/acme/PriceService.java",
            "TaxCalc",
            ["method:tax(int)"],
            target_package="com.acme.calc",
            confirm_public_api_change=True,
        )

    assert result.get("accepted"), result
    created = _created_text(result)
    assert "package com.acme.calc;" in created
    assert "private final com.acme.PriceService owner;" in created
    assert "TaxCalc(com.acme.PriceService owner)" in created
