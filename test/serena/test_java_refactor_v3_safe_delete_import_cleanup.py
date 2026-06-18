"""F9 (refactor-feature-plan-V3.md §7 cleanup pipeline — import cleanup half): a propagating safe delete must strip the
imports of a deleted type from every surviving file, or the composed edit will not compile and the (otherwise safe)
deletion is refused.

The reachability graph models USAGES, not import statements, so a file that carries a stale single-type import of a
now-deleted type — without otherwise using it — keeps that type deletable, yet the leftover import becomes a
``cannot find symbol`` error. These tests drive the LIVE sidecar through ``manager.propagate_safe_delete`` and prove:

(1) a genuinely-orphaned type with a stale same-package import in a surviving file is deleted, the import is removed, and
    the real before/after javac bridge accepts the edit (``diagnosticDeltaValidated`` true);
(2) honesty: an import of a DIFFERENT, surviving type in the same file is left untouched;
(3) honesty: when the importing file actually USES the type (a real usage in the body, not just the import line), the
    import strip does NOT mask that usage — the leftover ``new Orphan()`` still fails to compile, so the javac bridge
    refuses the deletion with ``new_compiler_errors`` and nothing is written. The cleanup can never hide a real use.
"""

from pathlib import Path

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


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


def test_safe_delete_strips_dangling_import_and_compiles(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "import_cleanup"
    j = "src/main/java/com/acme/app"
    _write(project, f"{j}/Main.java", "package com.acme.app;\npublic class Main {\n  public static void main(String[] a) { new Consumer(); }\n}\n")
    # Consumer is kept alive by Main. It carries a stale, redundant same-package import of Orphan (a leftover an IDE move
    # would produce) but never uses Orphan — so Orphan stays deletable, and the import would dangle after the delete.
    _write(
        project,
        f"{j}/Consumer.java",
        "package com.acme.app;\nimport com.acme.app.Orphan;\nimport java.util.List;\n"
        "public class Consumer {\n  java.util.List<String> names = new java.util.ArrayList<>();\n  List<String> view() { return names; }\n}\n",
    )
    _write(project, f"{j}/Orphan.java", "package com.acme.app;\nclass Orphan {}\n")

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Orphan"], apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("applied") is True, result
    # The edit really compiled after both the whole-file delete AND the import strip — proven by the javac bridge.
    assert result.get("diagnosticDeltaValidated") is True, result
    assert not (project / f"{j}/Orphan.java").exists(), "orphan file should be deleted"

    consumer = (project / f"{j}/Consumer.java").read_text(encoding="utf-8")
    assert "import com.acme.app.Orphan;" not in consumer, consumer
    # Honesty: the import of a different, surviving type is left intact.
    assert "import java.util.List;" in consumer, consumer
    assert "List<String> view()" in consumer, consumer


def test_safe_delete_does_not_mask_real_usage_and_refuses(sidecar_jar: Path, tmp_path: Path, monkeypatch) -> None:
    # Honesty gate: a forced package-private seed that is still USED is admitted to the plan (it is not an API boundary),
    # but the deletion WOULD break compilation. The import strip removes only the import line — the body usage
    # ``new Orphan()`` remains — so the before/after javac bridge still sees ``cannot find symbol`` and refuses with
    # ``new_compiler_errors``, writing nothing. The cleanup never hides a real use to push a breaking delete through.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    project = tmp_path / "import_in_use"
    j = "src/main/java/com/acme/app"
    _write(project, f"{j}/Main.java", "package com.acme.app;\npublic class Main {\n  public static void main(String[] a) { new Consumer(); }\n}\n")
    _write(
        project,
        f"{j}/Consumer.java",
        "package com.acme.app;\nimport com.acme.app.Orphan;\npublic class Consumer {\n  Orphan held = new Orphan();\n}\n",
    )
    _write(project, f"{j}/Orphan.java", "package com.acme.app;\nclass Orphan {}\n")

    manager = _manager(project)
    try:
        result = manager.propagate_safe_delete(["com.acme.app.Orphan"], apply=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result.get("applied") is False, result
    assert result.get("diagnosticDeltaValidated") is False, result
    assert result["refusal"]["code"] == "new_compiler_errors", result
    # Nothing was written: the still-used type and the importing file both survive untouched.
    assert (project / f"{j}/Orphan.java").exists()
    assert "import com.acme.app.Orphan;" in (project / f"{j}/Consumer.java").read_text(encoding="utf-8")
