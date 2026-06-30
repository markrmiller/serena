from serena.java_refactor.manager import JavaRefactorManager
from serena.tools.java_refactor_v3_tools import JavaMoveSourceRootTool


class _CapturingMoveSourceRootManager:
    def __init__(self):
        self.kwargs = None

    def move_source_root(self, *args, **kwargs):
        self.kwargs = kwargs
        return {"applied": False, "preview": {"touchedFiles": []}}


def test_move_source_root_tool_blocks_review_required_by_default():
    manager = _CapturingMoveSourceRootManager()
    tool = object.__new__(JavaMoveSourceRootTool)
    tool._get_manager = lambda: manager

    tool.apply("src/main/java", "src/main/java11", preview=True)

    assert manager.kwargs["allow_review_required"] is False


def test_move_source_root_tool_forwards_review_approval():
    manager = _CapturingMoveSourceRootManager()
    tool = object.__new__(JavaMoveSourceRootTool)
    tool._get_manager = lambda: manager

    tool.apply(
        "src/main/java",
        "src/main/java11",
        allow_review_required=True,
        preview=True,
    )

    assert manager.kwargs["allow_review_required"] is True


def test_move_source_root_manager_blocks_review_required_by_default():
    captured = {}

    def fake_preview_or_apply_refactor(**kwargs):
        captured.update(kwargs)
        return {"ok": True}

    manager = object.__new__(JavaRefactorManager)
    manager._preview_or_apply_refactor = fake_preview_or_apply_refactor

    manager.move_source_root("src/main/java", "src/main/java11", apply=False)

    assert captured["params"]["allowReviewRequired"] is False
    assert captured["allow_review_required"] is False


def test_move_source_root_manager_forwards_review_approval_to_backend_params():
    captured = {}

    def fake_preview_or_apply_refactor(**kwargs):
        captured.update(kwargs)
        return {"ok": True}

    manager = object.__new__(JavaRefactorManager)
    manager._preview_or_apply_refactor = fake_preview_or_apply_refactor

    manager.move_source_root(
        "src/main/java",
        "src/main/java11",
        allow_review_required=True,
        apply=False,
    )

    assert captured["params"]["allowReviewRequired"] is True
    assert captured["allow_review_required"] is True
