from pathlib import Path

from serena.java_refactor.manager import JavaRefactorManager


def _manager(tmp_path: Path) -> JavaRefactorManager:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    manager._project_root = tmp_path
    return manager


def test_build_tool_validation_plan_scopes_maven_to_affected_modules(tmp_path: Path) -> None:
    manager = _manager(tmp_path)
    project_model = {
        "sourceSets": [
            {"sourceRoots": ["services/order/src/main/java", "services/order/src/test/java"]},
            {"sourceRoots": ["libs/common/src/main/java"]},
        ]
    }

    plan = manager._build_tool_validation_plan("maven", project_model)

    assert plan is not None
    assert plan["scope"] == "affectedModules"
    assert plan["modules"] == ["libs/common", "services/order"]
    assert plan["compile"][:3] == ["mvn", "-q", "-B"]
    assert plan["compile"][3:6] == ["-pl", "libs/common,services/order", "-am"]
    assert plan["compile"][-2:] == ["compile", "test-compile"]
    assert plan["test"][3:6] == ["-pl", "libs/common,services/order", "-am"]
    assert plan["test"][-1] == "test"


def test_build_tool_validation_plan_scopes_gradle_to_affected_projects(tmp_path: Path) -> None:
    manager = _manager(tmp_path)
    project_model = {
        "sourceSets": [
            {"sourceRoots": ["services/order/src/main/java"]},
            {"sourceRoots": ["libs/common/src/test/java"]},
        ]
    }

    plan = manager._build_tool_validation_plan("gradle", project_model)

    assert plan is not None
    assert plan["scope"] == "affectedProjects"
    assert plan["modules"] == ["libs/common", "services/order"]
    assert plan["compile"][-4:] == [
        ":libs:common:compileJava",
        ":libs:common:compileTestJava",
        ":services:order:compileJava",
        ":services:order:compileTestJava",
    ]
    assert plan["test"][-2:] == [":libs:common:test", ":services:order:test"]


def test_build_tool_validation_plan_uses_explicit_root_fallback_when_no_module_scope(tmp_path: Path) -> None:
    manager = _manager(tmp_path)

    maven = manager._build_tool_validation_plan("maven", {"sourceSets": [{"sourceRoots": []}]})
    gradle = manager._build_tool_validation_plan("gradle", {})

    assert maven is not None
    assert maven["scope"] == "rootFallback"
    assert maven["modules"] == ["."]
    assert "-pl" not in maven["compile"]
    assert gradle is not None
    assert gradle["scope"] == "rootFallback"
    assert gradle["modules"] == ["."]
    assert gradle["compile"][-2:] == ["compileJava", "compileTestJava"]

def test_build_tool_validation_plan_filters_to_touched_module(tmp_path: Path) -> None:
    manager = _manager(tmp_path)
    project_model = {
        "sourceSets": [
            {"sourceRoots": ["services/order/src/main/java"], "resourceRoots": ["services/order/src/main/resources"]},
            {"sourceRoots": ["libs/common/src/main/java"]},
        ]
    }

    plan = manager._build_tool_validation_plan(
        "maven", project_model, affected_paths=["services/order/src/main/resources/beans.xml"]
    )

    assert plan is not None
    assert plan["scope"] == "affectedModules"
    assert plan["modules"] == ["services/order"]
    assert plan["compile"][3:6] == ["-pl", "services/order", "-am"]
