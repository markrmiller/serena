"""Sidecar-backed tests for the V3 extract / delegation / inline edit operations (G006 / G007 / G008).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the manager's ``extract_class`` / ``extract_superclass`` / ``replace_inheritance_with_delegation`` / ``deep_inline_method``
methods. Each capability is planned entirely inside the sidecar (javac's ``Trees``/``Elements`` model is authoritative for
member resolution and the conservative refusal lists); the manager is a thin forwarder that routes an accepted sidecar
edit through the central javac validation bridge so an accepted result carries a REAL before/after diagnostic delta
(``diagnosticDeltaValidated`` true) and is applied transactionally with post-validation rollback.

The contract is the sidecar's native one (refactor-feature-plan-V3.md §8–§11): ``relativePath`` (+ ``line``/``column``
for inline), ``"field:<name>"`` / ``"method:<name>(<types>)"`` member selectors, and a list of sibling paths for
extract-superclass. Each project is generated in a tmp dir with an EXPLICIT single-source-root configuration (no build
tool) so the model is hermetic, and ``allow_incomplete_analysis`` keeps the harness tolerant of a classpath-less
conventional layout while still diffing newly-introduced compiler errors against the pre-edit baseline.
"""

from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import sidecar_jar  # noqa: F401

JDIR = "src/main/java/com/acme/app"


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _manager(project_root: Path) -> JavaRefactorManager:
    return JavaRefactorManager(
        str(project_root),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            enabled=True,
            build_tool_mode="explicit",
            source_roots=["src/main/java"],
            allow_incomplete_analysis=True,
        ),
    )


# --- G006 extract class -----------------------------------------------------------------------------------------------

# total carries an initializer (extract-class refuses moving a field that would require rewriting every constructor).
_CART = (
    "package com.acme.app;\n"
    "public class Cart {\n"
    "    private int total = 0;\n"
    "    private String label = \"\";\n"
    "    public void addToTotal(int price) { total += price; }\n"
    "    public int currentTotal() { return total; }\n"
    "    public String label() { return label; }\n"
    "}\n"
)

_CART_MEMBERS = ["field:total", "method:addToTotal(int)", "method:currentTotal()"]


def test_sidecar_extract_class_preview_validates(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # An accepted extract-class preview moves the cohesive cluster into a new helper, passes the real javac bridge
    # (diagnosticDeltaValidated true), and writes NOTHING.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_class_ok"
    _write(project, f"{JDIR}/Cart.java", _CART)

    manager = _manager(project)
    try:
        result = manager.extract_class(f"{JDIR}/Cart.java", "Totals", _CART_MEMBERS, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "extractClass", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Cart.java" in touched, touched
    assert f"{JDIR}/Totals.java" in touched, touched
    # Preview must not touch disk.
    assert not (project / f"{JDIR}/Totals.java").exists()


def test_sidecar_extract_class_apply_writes_helper(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Apply path: the javac-validated edit is committed transactionally; the new helper exists and post-validation passed.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_class_apply"
    _write(project, f"{JDIR}/Cart.java", _CART)

    manager = _manager(project)
    try:
        result = manager.extract_class(f"{JDIR}/Cart.java", "Totals", _CART_MEMBERS, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert (project / f"{JDIR}/Totals.java").exists()
    cart = (project / f"{JDIR}/Cart.java").read_text(encoding="utf-8")
    assert "total += price;" not in cart  # relocated into the new helper; Cart keeps a forwarder


def test_sidecar_extract_class_passes_planner_refusals_through(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Compiler-backed refusals (unknown member, no members, unresolved source type) pass through verbatim BEFORE the
    # javac bridge, writing nothing.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_class_refusals"
    _write(project, f"{JDIR}/Cart.java", _CART)

    manager = _manager(project)
    try:
        unknown = manager.extract_class(f"{JDIR}/Cart.java", "Totals", ["field:nope"], apply=False)
        empty = manager.extract_class(f"{JDIR}/Cart.java", "Totals", [], apply=False)
        # A relativePath that is not in the Java project model is a malformed request (hard error), not a soft refusal.
        with pytest.raises(RuntimeError, match="not in the Java project model"):
            manager.extract_class(f"{JDIR}/DoesNotExist.java", "Totals", ["field:total"], apply=False)
    finally:
        manager.shutdown()

    assert unknown.get("accepted") is False, unknown
    assert unknown["refusal"]["code"] == "member_not_found", unknown
    assert empty.get("accepted") is False, empty
    assert empty["refusal"]["code"] == "no_members", empty


# --- G006 extract superclass (multi-sibling) --------------------------------------------------------------------------
#
# CONTRACT CHANGE (Design Y): extract-superclass adopts the sidecar's native multi-sibling semantics — it hoists a member
# COMMON to two or more sibling classes into a new shared abstract superclass. (The former single-class agent contract is
# gone; a lone class has no common member to hoist.)

_CIRCLE = (
    "package com.acme.app;\n"
    "public class Circle {\n"
    "    public String describe() { return \"circle\"; }\n"
    "    public double area() { return 3.14; }\n"
    "}\n"
)
_SQUARE = (
    "package com.acme.app;\n"
    "public class Square {\n"
    "    public String describe() { return \"square\"; }\n"
    "    public double side() { return 1.0; }\n"
    "}\n"
)

_SIBLINGS = [f"{JDIR}/Circle.java", f"{JDIR}/Square.java"]


def test_sidecar_extract_superclass_preview_validates(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_super_ok"
    _write(project, f"{JDIR}/Circle.java", _CIRCLE)
    _write(project, f"{JDIR}/Square.java", _SQUARE)

    manager = _manager(project)
    try:
        result = manager.extract_superclass(_SIBLINGS, "Shape", ["method:describe()"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "extractSuperclass", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Shape.java" in touched, touched
    assert not (project / f"{JDIR}/Shape.java").exists()


def test_sidecar_extract_superclass_apply_inserts_extends(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_super_apply"
    _write(project, f"{JDIR}/Circle.java", _CIRCLE)
    _write(project, f"{JDIR}/Square.java", _SQUARE)

    manager = _manager(project)
    try:
        result = manager.extract_superclass(_SIBLINGS, "Shape", ["method:describe()"], apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert (project / f"{JDIR}/Shape.java").exists()
    circle = (project / f"{JDIR}/Circle.java").read_text(encoding="utf-8")
    assert "extends Shape {" in circle


def test_sidecar_extract_superclass_refuses_existing_superclass(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Compiler-backed refusal: single inheritance forbids inserting a superclass on a sibling that already extends one.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_super_refused"
    _write(project, f"{JDIR}/Base.java", "package com.acme.app;\npublic class Base {}\n")
    _write(
        project,
        f"{JDIR}/Circle.java",
        "package com.acme.app;\npublic class Circle extends Base {\n"
        "    public String describe() { return \"circle\"; }\n}\n",
    )
    _write(project, f"{JDIR}/Square.java", _SQUARE)

    manager = _manager(project)
    try:
        result = manager.extract_superclass(_SIBLINGS, "Shape", ["method:describe()"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "extract_superclass_existing_superclass", result


# --- G007 replace inheritance with delegation -------------------------------------------------------------------------

_PARENT = (
    "package com.acme.app;\n"
    "public class Animal {\n"
    "    public String speak() { return \"...\"; }\n"
    "    public int legs() { return 4; }\n"
    "}\n"
)


def test_sidecar_replace_inheritance_preview_validates(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_ok"
    _write(project, f"{JDIR}/Animal.java", _PARENT)
    _write(
        project,
        f"{JDIR}/Dog.java",
        "package com.acme.app;\npublic class Dog extends Animal {\n    public String fetch() { return \"ball\"; }\n}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Dog.java", confirm_public_api_change=True, apply=False
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "replaceInheritanceWithDelegation", result
    assert result.get("superclass") == "com.acme.app.Animal", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Dog.java" in touched, touched


def test_sidecar_replace_inheritance_apply_rewrites_to_composition(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_apply"
    _write(project, f"{JDIR}/Animal.java", _PARENT)
    _write(
        project,
        f"{JDIR}/Dog.java",
        "package com.acme.app;\npublic class Dog extends Animal {\n    public String fetch() { return \"ball\"; }\n}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Dog.java", confirm_public_api_change=True, apply=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    dog = (project / f"{JDIR}/Dog.java").read_text(encoding="utf-8")
    assert "extends Animal" not in dog
    # Animal is in the same package as Dog, so the forwarder/field reference is the simple name (no FQN, no import).
    assert "new Animal()" in dog, dog  # former superclass is now held behind a delegate field
    assert "com.acme.app.Animal" not in dog, dog


def test_sidecar_replace_inheritance_refuses_no_superclass(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Compiler-backed refusal: a type with no non-Object superclass has nothing to convert.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_refused"
    _write(
        project,
        f"{JDIR}/Lonely.java",
        "package com.acme.app;\npublic class Lonely {\n    public int n() { return 1; }\n}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(f"{JDIR}/Lonely.java", apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "replace_inheritance_no_superclass", result


# §10.2 step 6: a base whose only constructor takes arguments is no longer refused — the subclass's explicit
# super(args) chaining is translated into delegate construction, and the delegate field drops its inline initializer.
_ENGINE = (
    "package com.acme.app;\n"
    "public class Engine {\n"
    "    private final int power;\n"
    "    public Engine(int power) { this.power = power; }\n"
    "    public int power() { return power; }\n"
    "}\n"
)


def test_sidecar_replace_inheritance_adapts_base_constructor_args(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_ctor_args"
    _write(project, f"{JDIR}/Engine.java", _ENGINE)
    _write(
        project,
        f"{JDIR}/Car.java",
        "package com.acme.app;\n"
        "public class Car extends Engine {\n"
        "    public Car() { super(100); }\n"
        "    public String describe() { return \"car\"; }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Car.java", confirm_public_api_change=True, apply=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    car = (project / f"{JDIR}/Car.java").read_text(encoding="utf-8")
    assert "extends Engine" not in car
    # delegate field carries no inline initializer; construction moves into the (former super) constructor body.
    # Engine is in the same package as Car, so its reference is the simple name (no FQN).
    assert "private final Engine engine;" in car, car
    assert "this.engine = new Engine(100);" in car, car
    assert "com.acme.app.Engine" not in car, car
    assert "super(100)" not in car, car


# §10.2 step 7: a super.method(...) call from an ordinary (non-overriding) subclass method is redirected to the delegate
# field. The §10.3 hazard -- an @Override that calls super on the SAME base method -- is refused instead (delegation
# cannot preserve the base's self-call dispatch) and is covered by
# test_replace_inheritance_refuses_override_that_calls_super. Here `shout()` does not override Greeter, so the safe
# super-redirect path applies.
_GREETER = (
    "package com.acme.app;\n"
    "public class Greeter {\n"
    "    public String hello() { return \"hi\"; }\n"
    "    public int count() { return 1; }\n"
    "}\n"
)


def test_sidecar_replace_inheritance_rewrites_super_method_call(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_super_call"
    _write(project, f"{JDIR}/Greeter.java", _GREETER)
    _write(
        project,
        f"{JDIR}/Loud.java",
        "package com.acme.app;\n"
        "public class Loud extends Greeter {\n"
        "    public String shout() { return super.hello() + \"!\"; }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Loud.java", confirm_public_api_change=True, apply=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    loud = (project / f"{JDIR}/Loud.java").read_text(encoding="utf-8")
    assert "extends Greeter" not in loud
    assert "super.hello()" not in loud, loud
    assert "greeter.hello()" in loud, loud  # super receiver rewritten to the delegate field
    # hello() is forwarded to the delegate exactly once (synthesized forwarder, not duplicated)
    assert loud.count("public String hello()") == 1, loud


# §10.3 public-API control: severing `extends Base` drops Base from the subclass's public API. The planner refuses by
# default and only proceeds once the caller confirms the public-API change.
def test_sidecar_replace_inheritance_blocks_public_api_change_by_default(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_api_block"
    _write(project, f"{JDIR}/Animal.java", _PARENT)
    _write(
        project,
        f"{JDIR}/Dog.java",
        "package com.acme.app;\npublic class Dog extends Animal {\n    public String fetch() { return \"ball\"; }\n}\n",
    )

    manager = _manager(project)
    try:
        # No confirm flag -> the default §10.3 behaviour refuses without writing.
        result = manager.replace_inheritance_with_delegation(f"{JDIR}/Dog.java", apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "replace_inheritance_public_api_change", result
    # the source is untouched on a refusal
    dog = (project / f"{JDIR}/Dog.java").read_text(encoding="utf-8")
    assert "extends Animal" in dog, dog


# §10.2: a co-located `implements` clause must be PRESERVED — only the `extends` relationship is severed.
_FLYER = (
    "package com.acme.app;\n"
    "public interface Flyer {\n"
    "    String soar();\n"
    "}\n"
)


def test_sidecar_replace_inheritance_preserves_implements_clause(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_implements"
    _write(project, f"{JDIR}/Animal.java", _PARENT)
    _write(project, f"{JDIR}/Flyer.java", _FLYER)
    _write(
        project,
        f"{JDIR}/Bird.java",
        "package com.acme.app;\n"
        "public class Bird extends Animal implements Flyer {\n"
        "    public String soar() { return \"up\"; }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Bird.java", confirm_public_api_change=True, apply=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    bird = (project / f"{JDIR}/Bird.java").read_text(encoding="utf-8")
    assert "extends Animal" not in bird, bird  # the extends relationship is severed
    assert "implements Flyer" in bird, bird  # the implements clause is preserved
    assert "new Animal()" in bird, bird  # former superclass held behind a delegate field


# Deliverable (2): a forwarder referencing a CROSS-PACKAGE superclass uses the simple type name AND adds an import line,
# rather than emitting a fully-qualified name inline.
_VEHICLE = (
    "package com.acme.base;\n"
    "public class Vehicle {\n"
    "    public String name() { return \"v\"; }\n"
    "    public int wheels() { return 4; }\n"
    "}\n"
)


def test_sidecar_replace_inheritance_cross_package_uses_import(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_cross_pkg"
    _write(project, "src/main/java/com/acme/base/Vehicle.java", _VEHICLE)
    # The subclass extends the cross-package base by its fully-qualified name and carries NO import for it, so the
    # planner must ADD the import line itself when it rewrites the forwarders/field to the simple name.
    _write(
        project,
        f"{JDIR}/Truck.java",
        "package com.acme.app;\n"
        "public class Truck extends com.acme.base.Vehicle {\n"
        "    public String haul() { return \"cargo\"; }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Truck.java", confirm_public_api_change=True, apply=True
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    truck = (project / f"{JDIR}/Truck.java").read_text(encoding="utf-8")
    assert "extends Vehicle" not in truck, truck
    # the cross-package superclass is referenced by its simple name (an import already covers it), never fully qualified
    assert "private final Vehicle vehicle = new Vehicle();" in truck, truck
    assert "com.acme.base.Vehicle()" not in truck, truck
    assert "import com.acme.base.Vehicle;" in truck, truck


# Deliverable (4) hazard: the subclass depends on a PROTECTED superclass member that a delegate instance cannot expose
# soundly, so the operation is refused with a specific code rather than silently producing uncompilable output.
_GUARDED = (
    "package com.acme.app;\n"
    "public class Guarded {\n"
    "    protected int secret() { return 42; }\n"
    "    public int open() { return 0; }\n"
    "}\n"
)


def test_sidecar_replace_inheritance_refuses_protected_member_dependency(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "delegation_protected"
    _write(project, f"{JDIR}/Guarded.java", _GUARDED)
    _write(
        project,
        f"{JDIR}/User.java",
        "package com.acme.app;\n"
        "public class User extends Guarded {\n"
        "    public int reveal() { return secret(); }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.replace_inheritance_with_delegation(
            f"{JDIR}/User.java", confirm_public_api_change=True, apply=False
        )
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "replace_inheritance_protected_member_dependency", result


# --- G008 deep inline method ------------------------------------------------------------------------------------------

# V3's generalized inline targets a private straight-line method whose call site is a STANDALONE statement (a nested
# expression call has no block-insertion point). log() is declared on line 3 (1-based) and called as a statement.
_LOGGER = (
    "package com.acme.app;\n"
    "public class Logger {\n"
    "    private void log(String msg) {\n"
    "        String prefix = \"[x] \";\n"
    "        System.out.println(prefix + msg);\n"
    "    }\n"
    "    void run() {\n"
    "        log(\"hi\");\n"
    "    }\n"
    "}\n"
)


def test_sidecar_deep_inline_method_preview_validates(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "inline_ok"
    _write(project, f"{JDIR}/Logger.java", _LOGGER)

    manager = _manager(project)
    try:
        result = manager.deep_inline_method(f"{JDIR}/Logger.java", 3, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "deepInlineMethod", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Logger.java" in touched, touched
    # Preview must not touch disk: the private method is still present.
    assert "private void log" in (project / f"{JDIR}/Logger.java").read_text(encoding="utf-8")


def test_sidecar_deep_inline_method_apply_inlines_and_deletes(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "inline_apply"
    _write(project, f"{JDIR}/Logger.java", _LOGGER)

    manager = _manager(project)
    try:
        result = manager.deep_inline_method(f"{JDIR}/Logger.java", 3, delete_method=True, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    logger = (project / f"{JDIR}/Logger.java").read_text(encoding="utf-8")
    assert "System.out.println" in logger  # the body was inlined into run()
    assert "private void log" not in logger  # declaration deleted


def test_sidecar_deep_inline_method_refuses_non_private(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Compiler-backed refusal: only a private, non-abstract, non-native method may be inlined.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "inline_refused"
    _write(
        project,
        f"{JDIR}/Calc.java",
        "package com.acme.app;\npublic class Calc {\n"
        "    public int run(int n) { return dbl(n); }\n"
        "    public int dbl(int x) { return x + x; }\n}\n",
    )

    manager = _manager(project)
    try:
        result = manager.deep_inline_method(f"{JDIR}/Calc.java", 4, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "not_private", result


# --- compiler-backed public-API protection ----------------------------------------------------------------------------
#
# GUARDRAIL-1 NOTE: under V2 this scenario was caught by the manager's javac BRIDGE (new_compiler_errors) because the
# Python planner only checked in-file callers. Under V3 the sidecar plans with javac and refuses EARLIER and STRICTLY
# safer: dropping the forwarders (leave_delegate_methods=False) for a PUBLIC moved method is refused at the
# compiler-backed planner with extract_class_public_api_without_delegates — before any breaking edit is ever constructed.
# The protected invariant (an external caller is never silently broken; nothing is written) is preserved.


def test_sidecar_extract_class_refuses_dropping_delegates_for_public_api(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "extract_class_breaks"
    _write(project, f"{JDIR}/Cart.java", _CART)
    # An external caller depends on Cart.addToTotal / Cart.currentTotal staying on Cart.
    _write(
        project,
        f"{JDIR}/Checkout.java",
        "package com.acme.app;\npublic class Checkout {\n"
        "    public int total(Cart cart) { cart.addToTotal(5); return cart.currentTotal(); }\n}\n",
    )

    manager = _manager(project)
    try:
        preview = manager.extract_class(
            f"{JDIR}/Cart.java", "Totals", _CART_MEMBERS, leave_delegate_methods=False, apply=False
        )
        applied = manager.extract_class(
            f"{JDIR}/Cart.java", "Totals", _CART_MEMBERS, leave_delegate_methods=False, apply=True
        )
    finally:
        manager.shutdown()

    # Preview: the compiler-backed planner refuses up front; nothing is written.
    assert preview.get("accepted") is False, preview
    assert preview.get("applied") is False, preview
    assert preview["refusal"]["code"] == "extract_class_public_api_without_delegates", preview
    # Apply: the same planner refusal fails closed; nothing is written (no new helper, original untouched).
    assert applied.get("accepted") is False, applied
    assert applied.get("applied") is False, applied
    assert applied["refusal"]["code"] == "extract_class_public_api_without_delegates", applied
    assert not (project / f"{JDIR}/Totals.java").exists()
    assert "public void addToTotal(int price)" in (project / f"{JDIR}/Cart.java").read_text(encoding="utf-8")
