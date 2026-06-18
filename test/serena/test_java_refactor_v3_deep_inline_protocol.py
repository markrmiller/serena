"""Live-sidecar coverage for the V3 ``inlineRefactor.*`` protocol (refactor-feature-plan-V3.md §11).

These boot the real Java sidecar jar and drive the generalized (multi-statement) inline method end to end via
:class:`~serena.java_refactor_v3.inline_refactor_client.InlineRefactorClient`. They prove that the straight-line body
classification, call-site resolution, parameter substitution and the conservative refusal lists are computed by javac
inside the sidecar — not by a Python heuristic — that each accepted plan returns a ``workspaceEdit`` (``changes`` +
``fileOperations``) which the sidecar's before/after javac validator has already accepted
(``diagnosticDeltaValidated: true``), and that the §11 refusals are surfaced with their canonical refusal ``code``.

Capabilities exercised:
    §11   test_deep_inline_void_multi_statement       — inline a straight-line private method into a statement call site
    §11   test_deep_inline_refuses_not_private        — only private methods are inlinable (refused)
    §11   test_deep_inline_refuses_no_call_sites      — a private method with no callers cannot be inlined (refused)
    §11   test_deep_inline_refuses_recursion          — a self-calling method is refused (refused)
    §11.1 test_deep_inline_refuses_checked_exception_mismatch — a body that throws a checked exception unhandled at the
                                                                call site is refused pre-flight (refused)
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.inline_refactor_client import InlineRefactorClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _inline(sidecar_jar: Path, project_root: Path, java_command: str = "java") -> Iterator[InlineRefactorClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        yield InlineRefactorClient(client)
    finally:
        client.shutdown()


def _changed_paths(result: dict) -> set[str]:
    return {change["path"] for change in result["workspaceEdit"]["changes"]}


# ── §11 generalized inline: happy path (straight-line void body inlined into a statement) ─────────────────────────


def test_deep_inline_void_multi_statement(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # log() is a private straight-line method (one local decl + one expression statement) called as a statement.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    private void log(String msg) {\n"
        "        String prefix = \"[x] \";\n"
        "        System.out.println(prefix + msg);\n"
        "    }\n"
        "    void run() {\n"
        "        log(\"hi\");\n"
        "    }\n"
        "}\n",
    )
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3, delete_method=True)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("Main.java") for path in _changed_paths(result)), result


# ── §11 generalized inline: refuse a non-private method ──────────────────────────────────────────────────────────


def test_deep_inline_refuses_not_private(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # log() is package-private; a non-private method may have call sites outside this compilation unit, so it is refused.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    void log(String msg) {\n"
        "        System.out.println(msg);\n"
        "    }\n"
        "    void run() {\n"
        "        log(\"hi\");\n"
        "    }\n"
        "}\n",
    )
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "not_private", result


# ── §11 generalized inline: refuse a method with no call sites ───────────────────────────────────────────────────


def test_deep_inline_refuses_no_call_sites(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # log() is private but never invoked; there is nothing to inline into.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    private void log(String msg) {\n"
        "        System.out.println(msg);\n"
        "    }\n"
        "}\n",
    )
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "no_call_sites", result


# ── §11 generalized inline: refuse a recursive method ────────────────────────────────────────────────────────────


def test_deep_inline_refuses_recursion(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # fact() calls itself; inlining a recursive method cannot terminate, so §11 refuses it.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "public class Main {\n"
        "    private int fact(int n) {\n"
        "        int r = n * fact(n - 1);\n"
        "        return r;\n"
        "    }\n"
        "    int run() {\n"
        "        return fact(5);\n"
        "    }\n"
        "}\n",
    )
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "recursive_method", result


# ── §11.1 generalized inline: refuse a checked-exception mismatch ─────────────────────────────────────────────────


def test_deep_inline_refuses_checked_exception_mismatch(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # parse() is a private straight-line method (one expression statement) declaring `throws IOException`; inlining it
    # would move the IOException-throwing call into run(), whose enclosing method neither catches nor declares
    # IOException. §11.1 ("no checked exception mismatch") requires this to be refused PRE-FLIGHT with the documented
    # refusal code, rather than only surfacing as a post-transform javac error.
    _write(
        tmp_path,
        "src/main/java/com/acme/Main.java",
        "package com.acme;\n"
        "import java.io.IOException;\n"
        "import java.nio.file.Files;\n"
        "import java.nio.file.Path;\n"
        "public class Main {\n"
        "    private void parse(String name) throws IOException {\n"
        "        Files.readString(Path.of(name));\n"
        "    }\n"
        "    void run() {\n"
        "        parse(\"x\");\n"
        "    }\n"
        "}\n",
    )
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 6)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "checked_exception_mismatch", result


# ── §11 max_call_sites: a tight cap refuses a large blast radius; at/above the count it proceeds ──────────────────


def _two_call_site_source() -> str:
    # `tag` is a private straight-line method invoked at TWO distinct statement call sites; the call-site count is 2.
    return (
        "package com.acme;\n"
        "public class Main {\n"
        "    private void tag(String msg) {\n"
        "        System.out.println(\"[x] \" + msg);\n"
        "    }\n"
        "    void run() {\n"
        "        tag(\"a\");\n"
        "        tag(\"b\");\n"
        "    }\n"
        "}\n"
    )


def test_deep_inline_max_call_sites_below_count_refuses(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # max_call_sites=1 is below the actual 2 call sites: the operation must REFUSE rather than rewrite a larger blast
    # radius than the caller authorized. This proves the cap is threaded all the way to the sidecar planner, not ignored.
    _write(tmp_path, "src/main/java/com/acme/Main.java", _two_call_site_source())
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3, max_call_sites=1)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "deep_inline_too_many_call_sites", result


def test_deep_inline_max_call_sites_at_count_proceeds(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # The SAME source with max_call_sites=2 (== the actual count) proceeds and produces a validated edit, proving the
    # cap is a real boundary (refuse below, accept at/above) rather than a blanket block.
    _write(tmp_path, "src/main/java/com/acme/Main.java", _two_call_site_source())
    with _inline(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as client:
        result = client.deep_inline_method("src/main/java/com/acme/Main.java", 3, max_call_sites=2)

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert any(path.endswith("Main.java") for path in _changed_paths(result)), result
