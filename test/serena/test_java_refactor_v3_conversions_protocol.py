"""Live-sidecar coverage for the V3 ``conversions.*`` protocol (refactor-feature-plan-V3.md §12–§13).

These boot the real Java sidecar jar and drive the two lambda/anonymous-class conversions end to end via
:class:`~serena.java_refactor_v3.conversions_client.ConversionsClient`. They prove that the functional-interface and
single-call analyses (and the conservative refusal lists) are computed by javac inside the sidecar — not by a Python
heuristic — that each accepted plan returns a ``workspaceEdit`` (``changes`` + ``fileOperations``) which the sidecar's
before/after javac validator has already accepted (``diagnosticDeltaValidated: true``), and that the §12/§13 refusals are
surfaced with their canonical refusal ``code``.

Capabilities exercised:
    §12   test_anonymous_to_lambda_runnable              — anonymous functional-interface class → lambda (happy path)
    §12.4 test_anonymous_to_lambda_refuses_field         — anonymous class declares state (refused)
    §12.4 test_anonymous_to_lambda_refuses_non_functional — target is not a single-abstract-method interface (refused)
    §13   test_lambda_to_static_method_reference         — lambda forwarding its parameter → method reference (happy path)
    §13.3 test_lambda_to_method_reference_refuses_compound — lambda body is not a single invocation (refused)
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.conversions_client import ConversionsClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _conversions(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[ConversionsClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield ConversionsClient(client)
    finally:
        client.shutdown()


def _changed_paths(result: dict) -> set[str]:
    return {change["path"] for change in result["workspaceEdit"]["changes"]}


# ── §12 anonymous class → lambda: happy path ─────────────────────────────────────────────────────────────────────


def test_anonymous_to_lambda_runnable(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # A bare functional-interface (Runnable) anonymous class with a single statement body converts cleanly.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    public Runnable make() {\n"
        "        return new Runnable() {\n"
        "            public void run() {\n"
        "                System.out.println(1);\n"
        "            }\n"
        "        };\n"
        "    }\n"
        "}\n",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.anonymous_to_lambda("src/main/java/com/acme/Main.java", 4)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("Main.java") for path in _changed_paths(result)), result


# ── §12.4 anonymous class → lambda: refuse declared state ────────────────────────────────────────────────────────


def test_anonymous_to_lambda_refuses_field(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The anonymous class carries a field; §12.4 forbids converting a class with its own state to a lambda.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    public Runnable make() {\n"
        "        return new Runnable() {\n"
        "            private int count = 0;\n"
        "            public void run() { count++; }\n"
        "        };\n"
        "    }\n"
        "}\n",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.anonymous_to_lambda("src/main/java/com/acme/Main.java", 4)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "anon_declares_field", result


# ── §12.4 anonymous class → lambda: refuse a non-functional interface ────────────────────────────────────────────


def test_anonymous_to_lambda_refuses_non_functional(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # TwoMethods has two abstract methods, so it is not a functional interface and cannot become a lambda.
    _write(
        tmp_path,
        "src/main/java/com/acme/TwoMethods.java",
        "package com.acme;\ninterface TwoMethods {\n    void a();\n    void b();\n}\n",
    )
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    public TwoMethods make() {\n"
        "        return new TwoMethods() {\n"
        "            public void a() {}\n"
        "            public void b() {}\n"
        "        };\n"
        "    }\n"
        "}\n",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.anonymous_to_lambda("src/main/java/com/acme/Main.java", 4)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] in {"anon_not_functional_interface", "anon_multiple_abstract_methods"}, result


# ── §13 lambda → method reference: happy path ────────────────────────────────────────────────────────────────────


def test_lambda_to_static_method_reference(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # `s -> Integer.parseInt(s)` forwards its single parameter untransformed → `Integer::parseInt`.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "import java.util.function.Function;\n"
        "public class Main {\n"
        "    public Function<String, Integer> make() {\n"
        "        return s -> Integer.parseInt(s);\n"
        "    }\n"
        "}\n",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.lambda_to_method_reference("src/main/java/com/acme/Main.java", 5)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("Main.java") for path in _changed_paths(result)), result


# ── §13.3 lambda → method reference: refuse a compound (non single-call) body ────────────────────────────────────


def test_lambda_to_method_reference_refuses_compound(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # The body is `Integer.parseInt(s) + 1`, not a bare invocation, so §13.3 refuses the method-reference rewrite.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "import java.util.function.Function;\n"
        "public class Main {\n"
        "    public Function<String, Integer> make() {\n"
        "        return s -> Integer.parseInt(s) + 1;\n"
        "    }\n"
        "}\n",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.lambda_to_method_reference("src/main/java/com/acme/Main.java", 5)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] in {"lambda_not_single_call", "lambda_unsupported_shape"}, result


def test_lambda_to_method_reference_refuses_bound_call_receiver(tmp_path, sidecar_jar, sidecar_java_cmd):
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        """package com.acme;
import java.util.function.Function;
class Main {
  Helper current() { return new Helper(); }
  void run() {
    Function<String, String> f = s -> current().clean(s);
  }
  static class Helper {
    String clean(String value) { return value.trim(); }
  }
}
""",
    )
    with _conversions(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.lambda_to_method_reference("src/main/java/com/acme/Main.java", 6)

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "lambda_unsupported_shape", result
