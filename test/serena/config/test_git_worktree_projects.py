import logging
import shutil
import subprocess
from pathlib import Path

import pytest

from serena.agent import SerenaAgent
from serena.config.context_mode import SerenaAgentContext
from serena.config.serena_config import LanguageBackend, ProjectConfig, SerenaConfig, SerenaPaths
from serena.tools import ListDirTool
from solidlsp.ls_config import Language


def _run_git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(cwd), *args], check=True, capture_output=True, text=True)


@pytest.fixture
def git_repo_with_worktree(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Path, Path]:
    serena_home = tmp_path / "serena-home"
    monkeypatch.setenv("SERENA_HOME", str(serena_home))
    SerenaPaths().serena_user_home_dir = str(serena_home)

    main_root = tmp_path / "repo"
    worktree_root = tmp_path / "agent-worktree"
    main_root.mkdir()
    _run_git(main_root, "init")
    _run_git(main_root, "config", "user.email", "test@example.com")
    _run_git(main_root, "config", "user.name", "Test User")
    (main_root / "main.py").write_text("def main() -> None:\n    pass\n", encoding="utf-8")
    _run_git(main_root, "add", "main.py")
    _run_git(main_root, "commit", "-m", "initial")
    _run_git(main_root, "worktree", "add", "-b", "agent", str(worktree_root))

    return main_root, worktree_root


def _make_config() -> SerenaConfig:
    return SerenaConfig(
        gui_log_window=False,
        web_dashboard=False,
        log_level=logging.ERROR,
        language_backend=LanguageBackend.JETBRAINS,
    )


def _write_project_config(project_root: Path, config: SerenaConfig) -> Path:
    ProjectConfig.autogenerate(project_root, config, languages=[Language.PYTHON], save_to_disk=True)
    return project_root / ".serena" / ProjectConfig.SERENA_PROJECT_FILE


def test_worktree_uses_shared_config_but_keeps_worktree_as_project_root(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    shared_project_yml = _write_project_config(main_root, config)

    project = config.get_project(str(worktree_root), autoregister=True)

    assert project is not None
    assert Path(project.project_root) == worktree_root
    assert Path(project.path_to_project_yml()) == shared_project_yml
    assert Path(project.path_to_serena_data_folder()) == shared_project_yml.parent
    assert Path(project.path_to_runtime_serena_data_folder()) != Path(project.path_to_serena_data_folder())
    assert str(Path(project.path_to_runtime_serena_data_folder())).startswith(SerenaPaths().serena_user_home_dir)


@pytest.mark.parametrize("with_shared_config", [False, True])
def test_transient_worktree_is_not_saved_to_global_config(git_repo_with_worktree: tuple[Path, Path], with_shared_config: bool) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    if with_shared_config:
        _write_project_config(main_root, config)

    project = config.add_project_from_path(worktree_root)

    assert Path(project.project_root) == worktree_root
    assert str(worktree_root) not in config.project_paths
    assert not (worktree_root / ".serena" / ProjectConfig.SERENA_PROJECT_FILE).exists()


def test_single_project_claude_code_can_activate_same_repo_worktree_only(git_repo_with_worktree: tuple[Path, Path], tmp_path: Path) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    _write_project_config(main_root, config)
    context = SerenaAgentContext(name="claude-code", prompt="", single_project=True, allow_project_activation=True)
    agent = SerenaAgent(project=str(main_root), serena_config=config, context=context)
    try:
        assert agent.tool_is_exposed("activate_project")

        assert agent.activate_project_from_path_or_name(str(worktree_root))
        assert Path(agent.get_active_project_or_raise().project_root) == worktree_root

        other_root = tmp_path / "other-repo"
        other_root.mkdir()
        _run_git(other_root, "init")
        with pytest.raises(ValueError, match="restricted to worktrees of the startup git repository"):
            agent.activate_project_from_path_or_name(str(other_root))
    finally:
        agent.on_shutdown(timeout=5)


def test_removed_active_worktree_disables_lsp_tools(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    _write_project_config(main_root, config)
    context = SerenaAgentContext(name="claude-code", prompt="", single_project=True, allow_project_activation=True)
    agent = SerenaAgent(project=str(main_root), serena_config=config, context=context)
    try:
        agent.activate_project_from_path_or_name(str(worktree_root))
        shutil.rmtree(worktree_root)

        with pytest.raises(ValueError, match="active project root no longer exists"):
            agent.get_active_project_or_raise()
        assert agent.get_active_project() is None
    finally:
        agent.on_shutdown(timeout=5)


def test_ignored_nested_worktree_path_reports_activation_guidance(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, _worktree_root = git_repo_with_worktree
    nested_worktree_root = main_root / ".claude" / "worktrees" / "agent" / "repo"
    (main_root / ".gitignore").write_text(".claude/\n", encoding="utf-8")
    _run_git(main_root, "add", ".gitignore")
    _run_git(main_root, "commit", "-m", "ignore claude worktrees")
    _run_git(main_root, "worktree", "add", "-b", "nested-agent", str(nested_worktree_root))

    config = _make_config()
    _write_project_config(main_root, config)
    project = config.get_project(str(main_root), autoregister=True)

    assert project is not None
    nested_relative_path = ".claude/worktrees/agent/repo/main.py"
    with pytest.raises(ValueError) as exc_info:
        project.validate_relative_path(nested_relative_path, require_not_ignored=True)

    message = str(exc_info.value)
    assert "This path is inside a Claude Code worktree, but Serena is active on the main checkout" in message
    assert "Use the Serena instance running in that worktree, or activate the worktree root" in message
    assert "Do not edit worktree files through .claude/worktrees/... from the main project" in message
    assert str(nested_worktree_root) in message


def test_claude_nested_worktree_is_transient_and_uses_shared_project_config(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, _worktree_root = git_repo_with_worktree
    nested_worktree_root = main_root / ".claude" / "worktrees" / "teammate" / "repo"
    (main_root / ".gitignore").write_text(".claude/worktrees/\n", encoding="utf-8")
    _run_git(main_root, "add", ".gitignore")
    _run_git(main_root, "commit", "-m", "ignore claude worktrees")
    _run_git(main_root, "worktree", "add", "-b", "teammate", str(nested_worktree_root))

    config = _make_config()
    shared_project_yml = _write_project_config(main_root, config)

    project = config.get_project(str(nested_worktree_root), autoregister=True)

    assert project is not None
    assert Path(project.project_root) == nested_worktree_root
    assert Path(project.path_to_project_yml()) == shared_project_yml
    assert Path(project.path_to_serena_data_folder()) == shared_project_yml.parent
    assert Path(project.path_to_runtime_serena_data_folder()) != Path(project.path_to_serena_data_folder())
    assert str(nested_worktree_root) not in config.project_paths
    assert not (nested_worktree_root / ".serena" / ProjectConfig.SERENA_PROJECT_FILE).exists()


def test_tool_results_carry_linked_worktree_notice(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    _write_project_config(main_root, config)
    context = SerenaAgentContext(name="claude-code", prompt="", single_project=True, allow_project_activation=True)
    agent = SerenaAgent(project=str(main_root), serena_config=config, context=context)
    try:
        list_dir_tool = agent.get_tool(ListDirTool)

        # the main checkout is active: results must not carry a worktree notice
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False)
        assert "linked git worktree" not in result

        # a linked worktree is active: every result must name the active root and the remedy
        agent.activate_project_from_path_or_name(str(worktree_root))
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False)
        assert "linked git worktree" in result
        assert str(worktree_root) in result
        assert "activate_project" in result
    finally:
        agent.on_shutdown(timeout=5)


def test_caller_cwd_auto_reroots_to_sibling_worktree(git_repo_with_worktree: tuple[Path, Path]) -> None:
    main_root, worktree_root = git_repo_with_worktree
    config = _make_config()
    _write_project_config(main_root, config)
    context = SerenaAgentContext(name="claude-code", prompt="", single_project=True, allow_project_activation=True)
    agent = SerenaAgent(project=str(main_root), serena_config=config, context=context)
    try:
        list_dir_tool = agent.get_tool(ListDirTool)

        # a caller working in a sibling worktree re-roots the active project automatically
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False, caller_cwd=str(worktree_root))
        assert Path(agent.get_active_project_or_raise().project_root) == worktree_root
        assert "Auto-activated the caller's git worktree" in result
        assert str(worktree_root) in result

        # a caller working in the already-active worktree leaves the project untouched
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False, caller_cwd=str(worktree_root))
        assert "Auto-activated" not in result

        # a caller working in the main checkout re-roots back
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False, caller_cwd=str(main_root))
        assert Path(agent.get_active_project_or_raise().project_root) == main_root
        assert "Auto-activated the caller's git worktree" in result
    finally:
        agent.on_shutdown(timeout=5)


def test_caller_cwd_outside_repository_family_does_not_reroot(git_repo_with_worktree: tuple[Path, Path], tmp_path: Path) -> None:
    main_root, _worktree_root = git_repo_with_worktree
    config = _make_config()
    _write_project_config(main_root, config)
    context = SerenaAgentContext(name="claude-code", prompt="", single_project=True, allow_project_activation=True)
    agent = SerenaAgent(project=str(main_root), serena_config=config, context=context)
    try:
        list_dir_tool = agent.get_tool(ListDirTool)

        # a caller in an unrelated git repository must not re-root the active project
        other_root = tmp_path / "other-repo"
        other_root.mkdir()
        _run_git(other_root, "init")
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False, caller_cwd=str(other_root))
        assert Path(agent.get_active_project_or_raise().project_root) == main_root
        assert "Auto-activated" not in result

        # a caller in a plain non-git directory must not re-root either
        plain_dir = tmp_path / "plain"
        plain_dir.mkdir()
        result = list_dir_tool.apply_ex(relative_path=".", recursive=False, caller_cwd=str(plain_dir))
        assert Path(agent.get_active_project_or_raise().project_root) == main_root
        assert "Auto-activated" not in result
    finally:
        agent.on_shutdown(timeout=5)


def test_tool_arg_metadata_accepts_caller_cwd() -> None:
    metadata = ListDirTool.get_apply_fn_metadata_from_cls()

    # the argument model must accept and pass through the optional caller_cwd parameter
    assert ListDirTool.CALLER_CWD_PARAM_NAME in metadata.arg_model.model_fields
    validated = metadata.arg_model.model_validate({"relative_path": ".", "recursive": False, "caller_cwd": "/some/where"})
    dumped = validated.model_dump_one_level()
    assert dumped["caller_cwd"] == "/some/where"

    # the parameter must be optional and present in the advertised JSON schema
    validated_without = metadata.arg_model.model_validate({"relative_path": ".", "recursive": False})
    assert validated_without.model_dump_one_level()["caller_cwd"] is None
    assert "caller_cwd" in metadata.arg_model.model_json_schema()["properties"]
