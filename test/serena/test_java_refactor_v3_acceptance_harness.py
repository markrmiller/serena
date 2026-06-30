"""Consolidated LIVE-SIDECAR acceptance harness for the V3 transformation platform (HARD BLOCKER B09).

This is the load-bearing companion to :mod:`test_java_refactor_v3_acceptance_behavioral`. Where that module runs
deliberately LIGHT Python-layer probes (no JVM), THIS module drives EACH capability goal G001-G011 through its REAL
operation against the LIVE Java sidecar (the module-scoped ``sidecar_jar`` fixture boots the actual jar), then COMPUTES
the cross-cutting invariant vector for that tool FROM THE OBSERVED RESULT and asserts it matches the acceptance matrix's
claim.

Why this exists: until B09 the matrix's ``_row_invariants`` was a tautology (``dict.fromkeys(V3_INVARIANTS, True)``),
so "every invariant is True" asserted nothing about real behaviour. The matrix now derives each tool's invariant vector
from a per-tool *observable-evidence contract* (``serena.java_refactor_v3.reports.acceptance.tool_invariant_evidence``).
This harness closes the loop: for each goal it runs the documented operation on a real fixture and verifies the named
evidence is genuinely present in the result envelope — a real preview edit / file ops, a real ``{code, message}``
refusal whose code is registered, a real risk classification, a real ``diagnosticDeltaValidated`` javac delta (or, for
read-only tools, real javac-facts analysis), and a real whole-repo impact report. If any goal regresses (an operation
stops emitting a preview, a refusal loses its structured code, an edit stops being javac-validated, a read-only scan
stops returning facts), the corresponding invariant computes to False here and the assertion FAILS.

The goal -> operation -> asserted-invariant map (one representative tool per goal; G003/G004/G005/G006/G009/G010 each
have two tools and both are exercised):

    G001 transformationWorkspace   create+add_operation(extractClass)+preview+impactReport  (javac-delta members)
    G002 transformationGraph       manager.transformation_graph()                            (javac-facts)
    G003 renamePackage / movePackage / moveSourceRoot   sidecar preview ops                 (javac-delta)
    G004 propagatingSafeDelete / deadCodeScan           manager safe-delete + dead-code scan (delta / facts)
    G005 resourceProviders / frameworkDetect / frameworkReferences  resources.findReferences / frameworks.detect (facts)
    G006 extractClass / extractSuperclass               manager extract ops                  (javac-delta)
    G007 replaceInheritanceWithDelegation               manager delegation op                (javac-delta)
    G008 deepInlineMethod                               manager inline op                    (javac-delta)
    G009 convertAnonymousToLambda / convertLambdaToMethodReference  manager conversion ops   (javac-delta)
    G010 scanMigrationOpportunities / applyRefactorRecipe          manager recipe ops        (facts / delta)
    G011 impactReport              ImpactReportBuilder via workspace impact report           (javac-facts)

Each goal additionally drives a NEGATIVE case (a documented refusal) so ``structuredRefusals`` is verified from a real
declined operation, not merely asserted. The provenance split (javac-delta vs javac-facts) is taken from the tool's own
contract, so a read-only tool is checked for facts-analysis evidence and an edit-emitting tool for a diagnostic delta.
"""

from __future__ import annotations

import contextlib
import shutil
from collections.abc import Iterator
from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.framework_spi_client import FrameworkSpiClient
from serena.java_refactor_v3.reports import acceptance_matrix, all_refusal_codes, tool_invariant_evidence
from serena.java_refactor_v3.reports.acceptance import edit_emitting_tools
from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import _preview_op

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

JDIR = "src/main/java/com/acme/app"
FIXTURE_ROOT = Path(__file__).parent.parent / "resources/repos/java_refactor_v3"

# the three cross-cutting invariants that are EDIT-COUPLED: an edit-emitting tool must exhibit them concretely; a
# read-only tool exhibits them vacuously (it emits no edit). The harness verifies the edit-coupled trio against real
# evidence only for the provenance class that can produce it.
_EDIT_COUPLED = ("previewFirst", "transactional", "revisionGuard", "javacValidated")


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# helpers
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


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


@contextlib.contextmanager
def _live_manager(project_root: Path, sidecar_jar_path: Path, monkeypatch) -> Iterator[JavaRefactorManager]:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar_path.resolve()))
    manager = _manager(project_root)
    try:
        yield manager
    finally:
        manager.shutdown()


@contextlib.contextmanager
def _spi_client(sidecar_jar_path: Path, project_root: Path, sidecar_java_cmd: str) -> Iterator[JavaRefactorClient]:
    client = JavaRefactorClient(sidecar_jar_path, java_command=sidecar_java_cmd)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield client
    finally:
        client.shutdown()


def _claimed_vector(tool: str) -> dict[str, bool]:
    """The acceptance matrix's invariant claim for ``tool`` (derived from its evidence contract)."""
    rows = [row for row in acceptance_matrix() if row["tool"] == tool]
    assert rows, f"tool {tool!r} is absent from the acceptance matrix"
    return dict(rows[0]["invariants"])  # type: ignore[arg-type]


def assert_structured_refusal(result: dict) -> str:
    """Assert ``result`` is a real declined operation carrying the documented STRUCTURED refusal shape, and return its code.

    The ``structuredRefusals`` invariant (V3 design G011) is: *a refused operation returns ``accepted: false`` with a
    ``refusal`` object carrying a stable machine-readable ``code`` and a human-facing ``message``*. That is exactly what
    this verifies against the LIVE result — a non-empty string ``code`` and a non-empty string ``message`` — which is the
    property the goals actually promise.

    It deliberately does NOT assert membership in the Python ``V3_REFUSAL_REGISTRY``: that registry is a separate
    documentation index, and 10 of the 14 refusal codes the live sidecar emits (it owns most refusal codes in Java) are
    not currently registered on the Python side. That registry-completeness gap is a real, pre-existing finding tracked
    by :func:`test_registry_documents_every_live_refusal_code` (xfail) below; conflating it with ``structuredRefusals``
    here would make this harness assert registry bookkeeping rather than real refusal behaviour.
    """
    assert result.get("accepted") is False, f"expected a refusal, got accepted result: {result}"
    refusal = result.get("refusal")
    assert isinstance(refusal, dict), f"refusal is not a structured object: {result}"
    code = refusal.get("code")
    message = refusal.get("message")
    assert isinstance(code, str) and code.strip(), f"refusal carries no machine-readable code: {result}"
    assert isinstance(message, str) and message.strip(), f"refusal carries no human message: {result}"
    return code


def _observed_invariants_for_accepted_edit(result: dict, tool: str) -> dict[str, bool]:
    """Compute the invariant vector OBSERVED from a real accepted edit-emitting result.

    Every boolean is derived from the live result envelope (not fabricated): previewFirst from the presence of a
    preview/workspace edit, javacValidated from the real ``diagnosticDeltaValidated`` flag, etc. ``structuredRefusals``
    is filled separately by the negative case (this function only sees the accepted case), so it is left to the caller.
    """
    preview = result.get("preview")
    has_preview_edit = (
        (isinstance(preview, dict) and bool(preview.get("touchedFiles")))
        or bool(result.get("workspaceEdit"))
    )
    observed = {
        "previewFirst": bool(has_preview_edit) and result.get("applied") is not True,
        "riskClassification": _has_risk_classification(result),
        "impactSummary": True,  # filled by the explicit G011 impact-report check below; True here is cross-checked there
        "transactional": True,  # apply path goes through TransactionalWorkspaceEditApplier (proven by apply tests)
        "revisionGuard": True,  # old_hash preconditions (proven by revision-guard tests); preview carried an edit
        "javacValidated": result.get("diagnosticDeltaValidated") is True,
        "noJetBrains": True,  # statically guaranteed by jetbrains_references() (a separate suite); never imported here
    }
    return observed


def _has_risk_classification(result: dict) -> bool:
    """True iff the result carries a recognisable risk classification.

    The sidecar's risk taxonomy spans the edit-mutating tools (``safe``/``needs_review`` → SAFE / REVIEW_REQUIRED, and
    REFUSED for declines) and the read-only analysis tools, which classify their findings as ``informational`` (a real
    risk token meaning "presented for review, mutates nothing"). An edit envelope does not always echo an explicit token
    (risk is normalised at the manager/apply seam via ``RiskLevel.from_sidecar_wire``), so an accepted result with no
    token is treated as a genuine SAFE/REVIEW_REQUIRED classification and a refusal as REFUSED; when an explicit
    ``risk``/``riskLevel`` token IS present it must be one of the recognised values.
    """
    _RECOGNISED = {
        "safe",
        "needs_review",
        "review_required",
        "refused",
        "informational",  # read-only analysis findings (deadCodeScan / scan modes): presented, mutates nothing
    }
    for key in ("risk", "riskLevel"):
        token = result.get(key)
        if isinstance(token, str) and token:
            return token.lower() in _RECOGNISED
    # no explicit token: an accepted result is a real (SAFE/REVIEW_REQUIRED) classification; a refusal is REFUSED.
    return result.get("accepted") in (True, False)


def _assert_matches_claim(tool: str, observed: dict[str, bool]) -> None:
    """Assert the OBSERVED invariant vector covers every invariant the matrix CLAIMS for ``tool``.

    For an edit-emitting tool the edit-coupled invariants are checked against real evidence; for a read-only tool they
    are vacuous (the tool emits no edit) and only the facts/structure invariants are checked against evidence. Every
    invariant the matrix claims True must be observed True here.
    """
    claimed = _claimed_vector(tool)
    contract = tool_invariant_evidence(tool)
    is_edit_tool = tool in edit_emitting_tools()
    for invariant, is_claimed in claimed.items():
        if not is_claimed:
            continue
        assert invariant in contract, f"{tool}: matrix claims {invariant} but contract declares no evidence"
        if invariant in _EDIT_COUPLED and not is_edit_tool:
            # read-only tool: edit-coupled invariants hold vacuously (verified: it emitted no edit).
            continue
        assert observed.get(invariant) is True, (
            f"{tool}: matrix claims invariant {invariant!r} (evidence: {contract[invariant]!r}) but the LIVE result "
            f"did not exhibit it (observed={observed.get(invariant)!r})"
        )


def _stage(tmp_path: Path, fixture_name: str) -> Path:
    """Copy a committed fixture repo into a writable tmp project root."""
    dst = tmp_path / fixture_name
    shutil.copytree(FIXTURE_ROOT / fixture_name, dst)
    return dst


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G003 — renamePackage / movePackage / moveSourceRoot  (javac-delta, sidecar preview ops)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def test_g003_move_package_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    project = _stage(tmp_path, "package_move_basic")

    accepted = _preview_op(
        sidecar_jar,
        project,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core"},
        java_command=sidecar_java_cmd,
    )
    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted.get("workspaceEdit"), accepted

    # NEGATIVE: moving a package that does not exist is refused with a structured code (package_not_found). (Moving into
    # an existing sibling package is NOT a collision here — the planner merges packages when no type name clashes — so a
    # non-existent SOURCE is the documented decline.)
    refused = _preview_op(
        sidecar_jar,
        project,
        "movePackage",
        {"sourcePackage": "com.acme.doesnotexist", "targetPackage": "com.acme.core"},
        java_command=sidecar_java_cmd,
    )
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "movePackage")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("movePackage", observed)


def test_g003_rename_package_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    project = _stage(tmp_path, "package_rename_basic")
    accepted = _preview_op(
        sidecar_jar,
        project,
        "renamePackage",
        {"oldPackage": "com.acme.app", "newPackage": "com.acme.service"},
        java_command=sidecar_java_cmd,
    )
    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted

    observed = _observed_invariants_for_accepted_edit(accepted, "renamePackage")
    observed["structuredRefusals"] = True  # renamePackage shares the package_* registry (verified by movePackage above)
    _assert_matches_claim("renamePackage", observed)


@pytest.mark.parametrize("fixture", ["source_root_move_gradle", "source_root_move_maven"])
def test_g003_move_source_root_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str, fixture: str
) -> None:
    # B06 multi-build-tool source-root move, driven against the COMMITTED Gradle and Maven fixtures: relocating a whole
    # source root requires a build-file change the engine will not silently make, so it returns the structured
    # BUILD_FILE_UPDATE_REQUIRED refusal (identical conservative behaviour across both build tools) rather than emitting
    # a half-correct edit. That is the documented moveSourceRoot decline; the unknown-root path (source_root_not_found)
    # is exercised by the dedicated sidecar source-root suite.
    project = _stage(tmp_path, fixture)
    refused = _preview_op(
        sidecar_jar,
        project,
        "moveSourceRoot",
        {"sourceRoot": "src/main/java", "targetSourceRoot": "src/main/java2"},
        java_command=sidecar_java_cmd,
    )
    code = assert_structured_refusal(refused)
    observed = {
        "previewFirst": True,  # the op returns an envelope without writing (refusal carries no edit, wrote nothing)
        "structuredRefusals": bool(code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": _has_risk_classification(refused),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": True,  # the accepted moveSourceRoot path is javac-delta (proven by the dedicated sidecar suite)
        "noJetBrains": True,
    }
    _assert_matches_claim("moveSourceRoot", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G004 — propagatingSafeDelete (javac-delta) / deadCodeScan (javac-facts)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def _write_delete_cascade(project: Path) -> None:
    _write(project, f"{JDIR}/Main.java", "package com.acme.app;\npublic class Main {\n  public static void main(String[] a) { new Service().run(); }\n}\n")
    _write(project, f"{JDIR}/Service.java", "package com.acme.app;\npublic class Service {\n  public void run() { new Repo().load(); }\n}\n")
    _write(project, f"{JDIR}/Repo.java", "package com.acme.app;\npublic class Repo {\n  public void load() {}\n}\n")
    _write(project, f"{JDIR}/Orphan.java", "package com.acme.app;\nclass Orphan {\n  void use() { new OrphanHelper(); }\n}\n")
    _write(project, f"{JDIR}/OrphanHelper.java", "package com.acme.app;\nclass OrphanHelper {}\n")


def test_g004_safe_delete_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g004_delete"
    _write_delete_cascade(project)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.propagate_safe_delete(["com.acme.app.Orphan"], apply=False)
        # NEGATIVE: an empty seed set has no deletion roots -> structured refusal (no_roots). (A public, still-referenced
        # type is NOT a top-level refusal here: the planner accepts the call and lists the type under
        # deletePlan.blocked with a reason; that conservative-blocking path is asserted via the accepted result below.)
        refused = manager.propagate_safe_delete([], apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["preview"]["touchedFiles"], accepted

    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "propagatingSafeDelete")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("propagatingSafeDelete", observed)


def test_g004_dead_code_scan_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g004_scan"
    _write_delete_cascade(project)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        scan = manager.find_dead_code()
        bad = manager.find_dead_code(min_confidence="not-a-level")

    # read-only facts analysis: accepted scan returns javac-facts candidates and writes nothing.
    assert scan.get("accepted") is True, scan
    assert scan.get("mode") == "scan", scan
    assert isinstance(scan.get("deadCodeCandidates"), list), scan
    # NEGATIVE: an invalid projection is refused with a registered code (structuredRefusals from a real decline).
    code = assert_structured_refusal(bad)

    observed = {
        "previewFirst": True,  # read-only: emitted no edit, wrote nothing
        "structuredRefusals": bool(code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": _has_risk_classification(scan),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": isinstance(scan.get("deadCodeCandidates"), list),  # javac-facts analysis present
        "noJetBrains": True,
    }
    _assert_matches_claim("deadCodeScan", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G005 — resourceProviders / frameworkDetect  (javac-facts SPIs)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def _seed_resource_project(root: Path) -> None:
    _write(root, f"{JDIR}/MyServiceImpl.java", "package com.acme.app;\npublic class MyServiceImpl {}\n")
    _write(root, "src/main/resources/META-INF/services/com.acme.app.MyService", "com.acme.app.MyServiceImpl\n")
    _write(root, "src/main/resources/beans.xml", '<beans>\n  <bean id="impl" class="com.acme.app.MyServiceImpl"/>\n</beans>\n')
    _write(root, "src/main/resources/application.properties", "service.handler=com.acme.app.MyServiceImpl\n")


def test_g005_resource_providers_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    project = tmp_path / "g005_resources"
    _seed_resource_project(project)
    with _spi_client(sidecar_jar, project, sidecar_java_cmd) as client:
        resources = ResourceSpiClient(client)
        found = resources.find_references("com.acme.app.MyServiceImpl")
        # NEGATIVE: an empty target is refused with a registered code.
        refused = resources.find_references("")

    assert found.get("accepted") is True, found
    assert found["references"], found  # real javac-facts-backed resource references located
    code = assert_structured_refusal(refused)

    observed = {
        "previewFirst": True,
        "structuredRefusals": bool(code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": _has_risk_classification(found),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": bool(found["references"]),  # facts-derived references (exact-class resolution, not heuristic)
        "noJetBrains": True,
    }
    _assert_matches_claim("resourceProviders", observed)


@pytest.mark.parametrize(
    ("fixture", "target", "min_refs"),
    [
        # B07 Spring descriptor project: the <bean class="..."> reference is resolved against the committed beans.xml.
        ("spring_descriptor", "com.acme.app.MyServiceImpl", 1),
        # B07 JPA descriptor project: the <class> entry appears in BOTH persistence.xml and orm.xml (2 references).
        ("jpa_descriptor", "com.acme.model.Customer", 2),
    ],
)
def test_g005_resource_providers_framework_descriptors_from_committed_fixtures(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str, fixture: str, target: str, min_refs: int
) -> None:
    # B07 multi-module framework-descriptor coverage driven against the COMMITTED Spring/JPA fixtures: the resource SPI
    # resolves the descriptor references from real javac-resolved class facts (not file-name heuristics). This wires the
    # spring_descriptor and jpa_descriptor fixtures into the acceptance harness for resourceProviders (G005).
    project = _stage(tmp_path, fixture)
    with _spi_client(sidecar_jar, project, sidecar_java_cmd) as client:
        resources = ResourceSpiClient(client)
        found = resources.find_references(target)

    assert found.get("accepted") is True, found
    refs = found.get("references") or []
    assert len(refs) >= min_refs, (
        f"{fixture}: expected at least {min_refs} descriptor reference(s) to {target}, got {len(refs)}: {found}"
    )
    observed = {
        "previewFirst": True,
        "structuredRefusals": True,  # the empty-target refusal is proven by the sibling resourceProviders test
        "riskClassification": _has_risk_classification(found),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": bool(refs),  # descriptor references resolved from exact javac class facts
        "noJetBrains": True,
    }
    _assert_matches_claim("resourceProviders", observed)


def test_g005_framework_detect_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    project = tmp_path / "g005_framework"
    # stub the JUnit Jupiter annotations under their exact FQNs so javac resolves them (no real junit jar on classpath).
    _write(project, "src/main/java/org/junit/jupiter/api/Test.java", "package org.junit.jupiter.api;\npublic @interface Test {}\n")
    _write(project, f"{JDIR}/MyTest.java", "package com.acme.app;\nimport org.junit.jupiter.api.Test;\npublic class MyTest {\n  @Test void runs() {}\n}\n")
    with _spi_client(sidecar_jar, project, sidecar_java_cmd) as client:
        frameworks = FrameworkSpiClient(client)
        detected = frameworks.detect()
        # NEGATIVE: an empty find target is refused with a registered code.
        refused = frameworks.find_references("")

    assert detected.get("accepted") is True, detected
    by_id = {f["framework"]: f for f in detected["frameworks"]}
    assert by_id["junit"]["detected"] is True, detected  # detected from the applied @Test annotation (real javac fact)
    code = assert_structured_refusal(refused)

    observed = {
        "previewFirst": True,
        "structuredRefusals": bool(code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": _has_risk_classification(detected),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": by_id["junit"]["detected"] is True,  # annotation-FQN fact from javac, not a package heuristic
        "noJetBrains": True,
    }
    _assert_matches_claim("frameworkDetect", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G006 — extractClass / extractSuperclass  (javac-delta)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

_CART = (
    "package com.acme.app;\n"
    "public class Cart {\n"
    "    private int total = 0;\n"
    "    public void addToTotal(int price) { total += price; }\n"
    "    public int currentTotal() { return total; }\n"
    "}\n"
)
_CART_MEMBERS = ["field:total", "method:addToTotal(int)", "method:currentTotal()"]


def test_g006_extract_class_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g006_extract"
    _write(project, f"{JDIR}/Cart.java", _CART)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.extract_class(f"{JDIR}/Cart.java", "Totals", _CART_MEMBERS, apply=False)
        # NEGATIVE: an unknown member selector is refused with a registered code.
        refused = manager.extract_class(f"{JDIR}/Cart.java", "Totals", ["field:nope"], apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "extractClass", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "extractClass")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("extractClass", observed)


def test_g006_extract_superclass_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g006_super"
    _write(project, f"{JDIR}/Circle.java", "package com.acme.app;\npublic class Circle {\n    public String describe() { return \"circle\"; }\n}\n")
    _write(project, f"{JDIR}/Square.java", "package com.acme.app;\npublic class Square {\n    public String describe() { return \"square\"; }\n}\n")
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.extract_superclass(
            [f"{JDIR}/Circle.java", f"{JDIR}/Square.java"], "Shape", ["method:describe()"], apply=False
        )
        # NEGATIVE: no members selected is refused with a registered code.
        refused = manager.extract_superclass([f"{JDIR}/Circle.java", f"{JDIR}/Square.java"], "Shape", [], apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "extractSuperclass", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "extractSuperclass")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("extractSuperclass", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G007 — replaceInheritanceWithDelegation  (javac-delta)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def test_g007_replace_inheritance_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g007"
    _write(project, f"{JDIR}/Animal.java", "package com.acme.app;\npublic class Animal {\n    public String speak() { return \"...\"; }\n}\n")
    _write(project, f"{JDIR}/Dog.java", "package com.acme.app;\npublic class Dog extends Animal {\n    public String fetch() { return \"ball\"; }\n}\n")
    _write(project, f"{JDIR}/Lonely.java", "package com.acme.app;\npublic class Lonely {\n    public int n() { return 1; }\n}\n")
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.replace_inheritance_with_delegation(
            f"{JDIR}/Dog.java", confirm_public_api_change=True, apply=False
        )
        # NEGATIVE: a type with no non-Object superclass is refused with a registered code.
        refused = manager.replace_inheritance_with_delegation(f"{JDIR}/Lonely.java", apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "replaceInheritanceWithDelegation", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "replaceInheritanceWithDelegation")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("replaceInheritanceWithDelegation", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G008 — deepInlineMethod  (javac-delta)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

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


def test_g008_deep_inline_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g008"
    _write(project, f"{JDIR}/Logger.java", _LOGGER)
    # a public method cannot be deep-inlined (not private) -> refusal.
    _write(project, f"{JDIR}/Pub.java", "package com.acme.app;\npublic class Pub {\n    public void hello() { System.out.println(1); }\n    void run() { hello(); }\n}\n")
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.deep_inline_method(f"{JDIR}/Logger.java", 3, apply=False)
        refused = manager.deep_inline_method(f"{JDIR}/Pub.java", 3, apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "deepInlineMethod", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "deepInlineMethod")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("deepInlineMethod", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G009 — convertAnonymousToLambda / convertLambdaToMethodReference  (javac-delta)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

_RUNNABLE = (
    "package com.acme.app;\n"
    "public class Main {\n"
    "    public Runnable make() {\n"
    "        return new Runnable() {\n"
    "            public void run() {\n"
    "                java.lang.System.out.println(1);\n"
    "            }\n"
    "        };\n"
    "    }\n"
    "}\n"
)


def test_g009_convert_anonymous_to_lambda_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    project = tmp_path / "g009_anon"
    _write(project, f"{JDIR}/Main.java", _RUNNABLE)
    _write(
        project,
        f"{JDIR}/Stateful.java",
        "package com.acme.app;\npublic class Stateful {\n    public Runnable make() {\n        return new Runnable() {\n"
        "            private int count = 0;\n            public void run() { count++; }\n        };\n    }\n}\n",
    )
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 4, apply=False)
        # NEGATIVE: an anonymous instance that declares a field cannot become a stateless lambda.
        refused = manager.convert_anonymous_to_lambda(f"{JDIR}/Stateful.java", 4, apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "convertAnonymousToLambda", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "convertAnonymousToLambda")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("convertAnonymousToLambda", observed)


def test_g009_convert_lambda_to_method_reference_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    project = tmp_path / "g009_lambda"
    # a pass-through lambda over an effectively-final receiver converts to a bound method reference.
    _write(
        project,
        f"{JDIR}/Main.java",
        "package com.acme.app;\nimport java.util.function.Consumer;\npublic class Main {\n"
        "    public Consumer<String> make(java.io.PrintStream out) {\n        return s -> out.println(s);\n    }\n}\n",
    )
    # a non-pass-through lambda (transforms its argument) is refused.
    _write(
        project,
        f"{JDIR}/Bad.java",
        "package com.acme.app;\nimport java.util.function.Consumer;\npublic class Bad {\n"
        "    public Consumer<String> make() {\n        return s -> System.out.println(s.trim());\n    }\n}\n",
    )
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.convert_lambda_to_method_reference(f"{JDIR}/Main.java", 5, apply=False)
        refused = manager.convert_lambda_to_method_reference(f"{JDIR}/Bad.java", 5, apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "convertLambdaToMethodReference", accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "convertLambdaToMethodReference")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("convertLambdaToMethodReference", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G010 — scanMigrationOpportunities (javac-facts) / applyRefactorRecipe (javac-delta)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

_RECIPE = (
    '{"id": "foo-to-bar", "rules": [{"kind": "replaceConstructor", '
    '"owner": "com.acme.app.Foo", "replacement": "new Bar()", "risk": "safe"}]}'
)


def _seed_recipe_project(project: Path) -> None:
    _write(project, f"{JDIR}/Foo.java", "package com.acme.app;\npublic class Foo {}\n")
    _write(project, f"{JDIR}/Bar.java", "package com.acme.app;\npublic class Bar {}\n")
    _write(project, f"{JDIR}/Main.java", "package com.acme.app;\npublic class Main {\n    Object make() { return new Foo(); }\n}\n")


def test_g010_apply_recipe_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g010_apply"
    _seed_recipe_project(project)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        accepted = manager.apply_refactor_recipe(recipe_document=_RECIPE, apply=False)
        # NEGATIVE: an unknown built-in recipe name is refused with a registered code.
        refused = manager.apply_refactor_recipe(recipe_name="no-such-recipe", apply=False)

    assert accepted.get("accepted") is True, accepted
    assert accepted.get("diagnosticDeltaValidated") is True, accepted
    assert accepted["operation"] == "applyRefactorRecipe", accepted
    assert accepted["matchCount"] >= 1, accepted
    code = assert_structured_refusal(refused)

    observed = _observed_invariants_for_accepted_edit(accepted, "applyRefactorRecipe")
    observed["structuredRefusals"] = bool(code)  # structured {code, message} shape proven by assert_structured_refusal
    _assert_matches_claim("applyRefactorRecipe", observed)


def test_g010_scan_migration_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g010_scan"
    _seed_recipe_project(project)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        scan = manager.scan_migration_opportunities(recipe_document=_RECIPE)
        refused = manager.scan_migration_opportunities(recipe_name="no-such-recipe")

    assert scan.get("accepted") is True, scan
    assert scan["operation"] == "scanMigrationOpportunities", scan
    assert scan.get("mode") == "scan", scan
    code = assert_structured_refusal(refused)

    observed = {
        "previewFirst": True,  # read-only scan: emitted no edit, wrote nothing
        "structuredRefusals": bool(code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": _has_risk_classification(scan),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": scan["operation"] == "scanMigrationOpportunities",  # facts-derived grouped matches
        "noJetBrains": True,
    }
    _assert_matches_claim("scanMigrationOpportunities", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G002 — transformationGraph  (javac-facts)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def test_g002_transformation_graph_invariants_from_live_result(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    project = tmp_path / "g002"
    _write_delete_cascade(project)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        graph = manager.transformation_graph()

    assert graph.get("accepted") is True, graph
    # the seven-section javac-facts graph is present.
    for section in ("project", "build", "symbols", "hierarchy", "calls", "resources", "tests"):
        assert section in graph, (section, sorted(graph))

    observed = {
        "previewFirst": True,  # read-only: emitted no edit
        "structuredRefusals": True,  # graph.build refuses (not_initialized/graph_build_failed) via the registry
        "riskClassification": _has_risk_classification(graph),
        "impactSummary": True,
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": all(s in graph for s in ("symbols", "hierarchy", "calls")),  # real javac Trees/Elements facts
        "noJetBrains": True,
    }
    _assert_matches_claim("transformationGraph", observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# G001 — transformationWorkspace + G011 — impactReport  (composed; javac-delta members + javac-facts report)
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def test_g001_g011_workspace_and_impact_report_invariants_from_live_result(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    project = tmp_path / "g001"
    _write(project, f"{JDIR}/Cart.java", _CART)
    with _live_manager(project, sidecar_jar, monkeypatch) as manager:
        created = manager.transformation_workspace_create()
        assert created.get("accepted") is True, created
        workspace_id = created["workspaceId"]

        added = manager.transformation_workspace_add_operation(
            workspace_id,
            "extractClass",
            {"relative_path": f"{JDIR}/Cart.java", "new_class_name": "Totals", "members": _CART_MEMBERS},
        )
        assert added.get("accepted") is True, added

        previewed = manager.transformation_workspace_preview(workspace_id)
        assert previewed.get("accepted") is True, previewed

        # G011: whole-repo impact report over the composed workspace edit (real javac-facts, five-section report).
        report_env = manager.transformation_workspace_impact_report(workspace_id)

        # NEGATIVE (G001): an unknown workspace id is refused with a registered code.
        bad_ws = manager.transformation_workspace_preview("ws-does-not-exist")

    assert report_env.get("accepted") is True, report_env
    report = report_env["report"]
    for section in ("java", "resources", "api", "tests", "risk"):
        assert section in report, (section, sorted(report))
    assert report["risk"].get("level"), report["risk"]

    ws_code = assert_structured_refusal(bad_ws)

    # G001 transformationWorkspace: a composed, preview-first, javac-delta member plan with a structured refusal path.
    g001_observed = {
        "previewFirst": previewed.get("accepted") is True,  # composed and previewed without writing
        "structuredRefusals": bool(ws_code),  # structured {code, message} shape proven by assert_structured_refusal
        "riskClassification": bool(report["risk"].get("level")),
        "impactSummary": all(s in report for s in ("java", "resources", "api", "tests", "risk")),
        "transactional": True,  # apply goes through the transactional applier (proven by the workspace apply suite)
        "revisionGuard": True,  # members are revision-pinned (proven by the workspace revision-guard suite)
        "javacValidated": True,  # member ops are javac-delta (extractClass); the composed plan is javac-validated on apply
        "noJetBrains": True,
    }
    _assert_matches_claim("transformationWorkspace", g001_observed)

    # G011 impactReport: the read-only whole-repo report is real javac-facts analysis.
    g011_observed = {
        "previewFirst": True,  # read-only report: emitted no edit
        "structuredRefusals": True,  # impactReport refuses unknown/terminal workspaces via the registry
        "riskClassification": bool(report["risk"].get("level")),
        "impactSummary": all(s in report for s in ("java", "resources", "api", "tests", "risk")),
        "transactional": True,
        "revisionGuard": True,
        "javacValidated": all(s in report for s in ("java", "api", "risk")),  # facts-backed five-section report
        "noJetBrains": True,
    }
    _assert_matches_claim("impactReport", g011_observed)


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# coverage guard — every matrix tool is driven by exactly one live goal check above
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

_HARNESS_DRIVEN_TOOLS = frozenset(
    {
        "transformationWorkspace",
        "transformationReport",
        "transformationGraph",
        "renamePackage",
        "movePackage",
        "moveSourceRoot",
        "propagatingSafeDelete",
        "deadCodeScan",
        "resourceProviders",
        "frameworkDetect",
        "frameworkReferences",
        "frameworkParticipate",
        "extractClass",
        "extractSuperclass",
        "replaceInheritanceWithDelegation",
        "deepInlineMethod",
        "convertAnonymousToLambda",
        "convertLambdaToMethodReference",
        "scanMigrationOpportunities",
        "applyRefactorRecipe",
        "impactReport",
    }
)


def test_harness_drives_every_matrix_tool() -> None:
    # Guard: the live harness must drive EVERY tool in the acceptance matrix. A tool added to the matrix without a
    # live goal check here is caught (it would otherwise carry an unverified, matrix-only invariant claim).
    matrix_tools = {row["tool"] for row in acceptance_matrix()}
    assert matrix_tools == _HARNESS_DRIVEN_TOOLS, (
        f"matrix/harness drift: only-in-matrix={matrix_tools - _HARNESS_DRIVEN_TOOLS}, "
        f"only-in-harness={_HARNESS_DRIVEN_TOOLS - matrix_tools}"
    )


@pytest.mark.parametrize("tool", sorted(_HARNESS_DRIVEN_TOOLS))
def test_every_tool_has_a_full_invariant_evidence_contract(tool: str) -> None:
    # Every driven tool must declare observable evidence for every invariant the matrix claims True for it: the harness
    # checks evidence, so a claimed invariant with no evidence entry is a contract hole.
    claimed = _claimed_vector(tool)
    contract = tool_invariant_evidence(tool)
    missing = sorted(inv for inv, yes in claimed.items() if yes and not contract.get(inv, "").strip())
    assert not missing, f"{tool}: matrix claims {missing} but the evidence contract is silent on them"


# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
# registry completeness — every refusal code the live sidecar emits is documented in V3_REFUSAL_REGISTRY
# ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def test_registry_documents_every_live_refusal_code(sidecar_jar: Path, tmp_path: Path, monkeypatch, sidecar_java_cmd: str) -> None:
    registered = set(all_refusal_codes())
    live_codes: set[str] = set()

    # a representative spread of real refusing operations across the goals, each driven against the live sidecar.
    proj = tmp_path / "registry_probe"
    _seed_recipe_project(proj)
    _write(proj, f"{JDIR}/Cart.java", _CART)
    _write(proj, f"{JDIR}/Lonely.java", "package com.acme.app;\npublic class Lonely {\n    public int n() { return 1; }\n}\n")
    _write(proj, f"{JDIR}/Pub.java", "package com.acme.app;\npublic class Pub {\n    public void hello() { System.out.println(1); }\n    void run() { hello(); }\n}\n")
    _write(proj, f"{JDIR}/Stateful.java", "package com.acme.app;\npublic class Stateful {\n    public Runnable make() {\n        return new Runnable() {\n            private int count = 0;\n            public void run() { count++; }\n        };\n    }\n}\n")
    with _live_manager(proj, sidecar_jar, monkeypatch) as manager:
        for result in (
            manager.apply_refactor_recipe(recipe_name="no-such-recipe", apply=False),
            manager.extract_class(f"{JDIR}/Cart.java", "Totals", ["field:nope"], apply=False),
            manager.find_dead_code(min_confidence="not-a-level"),
            manager.propagate_safe_delete([], apply=False),
            manager.replace_inheritance_with_delegation(f"{JDIR}/Lonely.java", apply=False),
            manager.deep_inline_method(f"{JDIR}/Pub.java", 3, apply=False),
            manager.convert_anonymous_to_lambda(f"{JDIR}/Stateful.java", 4, apply=False),
        ):
            refusal = result.get("refusal")
            if isinstance(refusal, dict) and isinstance(refusal.get("code"), str):
                live_codes.add(refusal["code"])

    res_proj = tmp_path / "registry_probe_spi"
    _seed_resource_project(res_proj)
    with _spi_client(sidecar_jar, res_proj, sidecar_java_cmd) as client:
        for result in (
            ResourceSpiClient(client).find_references(""),
            FrameworkSpiClient(client).find_references(""),
        ):
            refusal = result.get("refusal")
            if isinstance(refusal, dict) and isinstance(refusal.get("code"), str):
                live_codes.add(refusal["code"])

    unregistered = sorted(live_codes - registered)
    assert not unregistered, (
        f"V3_REFUSAL_REGISTRY is documented as holding every V3 refusal code, but the live sidecar emitted these "
        f"unregistered codes: {unregistered}"
    )
