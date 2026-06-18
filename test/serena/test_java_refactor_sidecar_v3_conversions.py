"""Sidecar-backed tests for the V3 anonymous/lambda conversions (G009).

These drive the LIVE Java refactoring sidecar (built from source by the module-scoped ``sidecar_jar`` fixture) through
the manager's ``convert_anonymous_to_lambda`` / ``convert_lambda_to_method_reference`` methods. Unlike the pure-Python
unit tests in ``test_java_refactor_v3_conversions.py`` (which exercise the planners and the transactional applier in
isolation), these prove the JAVAC VALIDATION BRIDGE: each Python-planned edit is routed through the sidecar's generic
``validateEdit`` so an accepted result carries a REAL before/after diagnostic delta (``diagnosticDeltaValidated`` true),
and an edit that would break compilation is REFUSED with newly-introduced compiler errors and writes nothing.

Each project is generated in a tmp dir with an EXPLICIT single-source-root configuration (no build tool) so the model is
hermetic, and ``allowIncompleteAnalysis`` keeps the harness tolerant of a classpath-less conventional layout while still
diffing newly-introduced compiler errors against the pre-edit baseline.
"""

from pathlib import Path

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


# --- G009 anonymous class -> lambda -----------------------------------------------------------------------------------

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


def test_sidecar_convert_anonymous_to_lambda_preview_validates(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # An accepted anonymous->lambda preview passes the real javac bridge (diagnosticDeltaValidated true) and writes NOTHING.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "anon_ok"
    _write(project, f"{JDIR}/Main.java", _RUNNABLE)

    manager = _manager(project)
    try:
        result = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 4, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "convertAnonymousToLambda", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Main.java" in touched, touched
    # Preview must not touch disk: the anonymous class is still present.
    assert "new Runnable()" in (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")


def test_sidecar_convert_anonymous_to_lambda_apply_rewrites(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "anon_apply"
    _write(project, f"{JDIR}/Main.java", _RUNNABLE)

    manager = _manager(project)
    try:
        result = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 4, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    main = (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")
    assert "new Runnable()" not in main
    assert "() -> java.lang.System.out.println(1);" in main


def test_sidecar_convert_anonymous_to_lambda_refuses_state(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Planner-level refusal: an anonymous instance that declares a field cannot become a stateless lambda.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "anon_refused"
    _write(
        project,
        f"{JDIR}/Main.java",
        "package com.acme.app;\npublic class Main {\n"
        "    public Runnable make() {\n"
        "        return new Runnable() {\n"
        "            private int count = 0;\n"
        "            public void run() { count++; }\n"
        "        };\n"
        "    }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 4, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "anon_declares_field", result


# §12.3 step 6: an anonymous class passed to an OVERLOADED method is an ambiguous target, so the converted lambda must
# retain its explicit parameter types — dropping them could change (or break) overload resolution. Here ``use`` is
# overloaded on two distinct functional interfaces; the anonymous ``Consumer<String>`` argument must become a lambda that
# still pins the parameter type ``(String s) -> ...`` so it keeps binding to the ``Consumer`` overload.
_OVERLOADED_TARGET = (
    "package com.acme.app;\n"
    "import java.util.function.Consumer;\n"
    "import java.util.function.Function;\n"
    "public class Main {\n"
    "    void use(Consumer<String> c) {}\n"
    "    void use(Function<String, Integer> f) {}\n"
    "    public void go() {\n"
    "        use(new Consumer<String>() {\n"
    "            public void accept(String s) {\n"
    "                java.lang.System.out.println(s);\n"
    "            }\n"
    "        });\n"
    "    }\n"
    "}\n"
)


def test_sidecar_convert_anonymous_to_lambda_retains_param_types_when_ambiguous(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # §12.3 step 6 "keep types if ambiguity exists": the anonymous Consumer argument to the overloaded ``use`` is an
    # ambiguous lambda target, so the rewrite keeps the explicit parameter type ``(String s)`` and still compiles.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "anon_ambiguous"
    _write(project, f"{JDIR}/Main.java", _OVERLOADED_TARGET)

    manager = _manager(project)
    try:
        # Anonymous class starts on the ``use(new Consumer<String>() {`` line (line 8, 1-based).
        result = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 8, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    main = (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")
    assert "new Consumer<String>()" not in main
    # Explicit parameter type retained (and an explicitly-typed lambda is always parenthesized).
    assert "(String s) -> java.lang.System.out.println(s)" in main


_UNAMBIGUOUS_PARAM = (
    "package com.acme.app;\n"
    "import java.util.function.Consumer;\n"
    "public class Main {\n"
    "    public Consumer<String> c() {\n"
    "        return new Consumer<String>() {\n"
    "            public void accept(String s) {\n"
    "                java.lang.System.out.println(s);\n"
    "            }\n"
    "        };\n"
    "    }\n"
    "}\n"
)


def test_sidecar_convert_anonymous_to_lambda_drops_param_types_when_unambiguous(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # §12.3 step 6 "omit parameter types when inferable": with a single unambiguous target (a return type), the lambda
    # stays clean and type-less — a single implicitly-typed parameter is not even parenthesized.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "anon_unambiguous"
    _write(project, f"{JDIR}/Main.java", _UNAMBIGUOUS_PARAM)

    manager = _manager(project)
    try:
        result = manager.convert_anonymous_to_lambda(f"{JDIR}/Main.java", 5, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    main = (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")
    assert "new Consumer<String>()" not in main
    assert "s -> java.lang.System.out.println(s)" in main
    assert "(String s)" not in main


# --- G009 lambda -> method reference ----------------------------------------------------------------------------------

_LAMBDA = (
    "package com.acme.app;\n"
    "public class Main {\n"
    "    public java.util.function.Function<String, Integer> f() {\n"
    "        return s -> Integer.parseInt(s);\n"
    "    }\n"
    "}\n"
)


def test_sidecar_convert_lambda_to_method_reference_preview_validates(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "lambda_ok"
    _write(project, f"{JDIR}/Main.java", _LAMBDA)

    manager = _manager(project)
    try:
        result = manager.convert_lambda_to_method_reference(f"{JDIR}/Main.java", 4, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert result["operation"] == "convertLambdaToMethodReference", result
    touched = set(result["preview"]["touchedFiles"])
    assert f"{JDIR}/Main.java" in touched, touched
    assert "Integer.parseInt(s)" in (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")


def test_sidecar_convert_lambda_to_method_reference_apply_rewrites(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "lambda_apply"
    _write(project, f"{JDIR}/Main.java", _LAMBDA)

    manager = _manager(project)
    try:
        result = manager.convert_lambda_to_method_reference(f"{JDIR}/Main.java", 4, apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    main = (project / f"{JDIR}/Main.java").read_text(encoding="utf-8")
    assert "return Integer::parseInt;" in main


def test_sidecar_convert_lambda_to_method_reference_refuses_block_body(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    # Planner-level refusal: a multi-statement lambda body is not a single call expression (a single-statement block
    # that is itself one call IS convertible, so the refusal needs two statements).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "lambda_refused"
    _write(
        project,
        f"{JDIR}/Main.java",
        "package com.acme.app;\npublic class Main {\n"
        "    public java.util.function.Consumer<Object> f() {\n"
        "        return x -> { java.lang.System.out.println(x); java.lang.System.out.println(x); };\n"
        "    }\n"
        "}\n",
    )

    manager = _manager(project)
    try:
        result = manager.convert_lambda_to_method_reference(f"{JDIR}/Main.java", 4, apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "lambda_not_single_call", result
