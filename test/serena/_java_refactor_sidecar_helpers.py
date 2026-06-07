import json
import shutil
import subprocess
from pathlib import Path

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

CROSS_SOURCE_SET_CONFIG = json.dumps(
    {"buildToolMode": "explicit", "sourceRoots": ["src/main/java", "src/test/java"], "allowIncompleteAnalysis": True}
)


__all__ = [
    'CROSS_SOURCE_SET_CONFIG',
    'text_edits',
    'file_ops',
    'sidecar_jar',
    'maven_offline_repo',
    'maven_offline_config',
    'run_status',
    '_build_vendored_jar',
    'write_maven_offline_project',
    '_write_gradle_java_project',
    '_preview_rename',
    '_preview_safe_delete',
    '_preview_op',
    '_write_two_module_project',
    '_write_cross_source_set_project',
    '_write_demo_main',
    '_crafted_apply',
    '_plain_project',
    '_build_processor_jar',
    '_write_divergent_gradle_project',
    '_write_source_level_divergent_project',
    '_utf16_offset',
    '_write_generated_root_project',
    '_apply_edits_to_text',
]

@pytest.fixture(scope="module")
def sidecar_jar() -> Path:
    subprocess.run(["gradle", "-p", "java-refactor", "jar"], check=True, capture_output=True, text=True)
    return Path("java-refactor/build/libs/serena-java-refactor-0.1.0.jar")



@pytest.fixture(scope="session")
def maven_offline_repo(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A file-based local Maven repo warmed once with the stock extraction goals so offline extraction never downloads.

    Per-test extraction runs strictly offline (``-o``); this one-time setup populates the help/dependency/compiler
    plugins (and a vendored compile dependency) into the repo. A vendored ``demo:vendor-lib`` artifact is installed via
    ``install:install-file`` so the offline classpath test can resolve a real dependency without the network.
    """
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")

    base = tmp_path_factory.mktemp("maven-offline")
    repo = base / ".m2-repo"
    repo.mkdir()

    # Warm the extraction plugins by running both goals once (network allowed at setup only) on a throwaway project.
    warmup = base / "warmup"
    (warmup / "src/main/java/demo").mkdir(parents=True)
    # Declaring build-helper-maven-plugin here warms it into the offline repo so the additional-source-roots extraction
    # test (G005) can resolve the effective POM strictly offline.
    (warmup / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>warmup</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<build><plugins><plugin>"
        "<groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.4.0</version>"
        "</plugin></plugins></build></project>",
        encoding="utf-8",
    )
    (warmup / "src/main/java/demo/Warm.java").write_text("package demo; public class Warm {}\n", encoding="utf-8")
    repo_arg = f"-Dmaven.repo.local={repo}"
    subprocess.run(["mvn", "-q", "-B", "help:effective-pom", "-Doutput=eff.xml", repo_arg], cwd=warmup, check=True, capture_output=True, text=True)
    subprocess.run(
        ["mvn", "-q", "-B", "dependency:build-classpath", "-Dmdep.outputFile=cp.txt", "-DincludeScope=test", repo_arg],
        cwd=warmup,
        check=True,
        capture_output=True,
        text=True,
    )

    # Vendor a real dependency into the offline repo so the classpath extraction test resolves an external jar.
    vendor_jar = _build_vendored_jar(base, "vendor", "VendorLib", "public static int answer() { return 42; }")
    subprocess.run(
        [
            "mvn",
            "-q",
            "-B",
            "install:install-file",
            f"-Dfile={vendor_jar}",
            "-DgroupId=demo",
            "-DartifactId=vendor-lib",
            "-Dversion=1.0",
            "-Dpackaging=jar",
            repo_arg,
        ],
        cwd=warmup,
        check=True,
        capture_output=True,
        text=True,
    )
    return repo



@pytest.fixture
def maven_offline_config() -> str:
    """Sidecar configuration forcing strictly-offline Maven build-model extraction."""
    return json.dumps({"buildToolMode": "maven", "offline": True, "allowIncompleteAnalysis": True})



def run_status(sidecar_jar: Path, project_root: Path, configuration: str = "default", project_data_dir: Path | None = None) -> dict:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(project_root),
                configuration=configuration,
                project_data_dir=str(project_data_dir) if project_data_dir is not None else None,
            )
        )
        return json.loads(status.to_json())
    finally:
        client.shutdown()



def _build_vendored_jar(tmp_dir: Path, package: str, class_name: str, body: str) -> Path:
    """Compiles a tiny single-class jar with javac+jar (no network) for offline dependency-resolution tests."""
    src_dir = tmp_dir / "vendor_src" / package
    src_dir.mkdir(parents=True, exist_ok=True)
    (src_dir / f"{class_name}.java").write_text(f"package {package}; public class {class_name} {{ {body} }}\n", encoding="utf-8")
    classes_dir = tmp_dir / "vendor_classes"
    classes_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-d", str(classes_dir), str(src_dir / f"{class_name}.java")], check=True, capture_output=True, text=True)
    jar_path = tmp_dir / f"{class_name.lower()}.jar"
    subprocess.run(["jar", "cf", str(jar_path), "-C", str(classes_dir), "."], check=True, capture_output=True, text=True)
    return jar_path



def write_maven_offline_project(project_root: Path, repo: Path | None = None) -> None:
    """Pins maven.repo.local to the warmed offline repo via .mvn/maven.config so extraction never hits the network."""
    if repo is not None:
        mvn_dir = project_root / ".mvn"
        mvn_dir.mkdir(parents=True, exist_ok=True)
        (mvn_dir / "maven.config").write_text(f"-Dmaven.repo.local={repo}\n", encoding="utf-8")



def _write_gradle_java_project(project_root: Path) -> tuple[Path, Path]:
    (project_root / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (project_root / "build.gradle.kts").write_text(
        'plugins { id("java") }\njava { sourceCompatibility = JavaVersion.VERSION_17 }\n', encoding="utf-8"
    )
    main_src = project_root / "src/main/java/example"
    test_src = project_root / "src/test/java/example"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    return main_src, test_src



def text_edits(workspace_edit: dict) -> list[dict]:
    """Flattens the V1 grouped ``changes[]`` into the flat per-edit view tests assert on.

    The V1 wire shape groups edits by file: ``changes[] = [{path, oldSha256, edits:[{startOffset, endOffset, newText,
    kind}]}]``. This returns one flat dict per edit carrying ``relativePath`` (the change's ``path``), ``startOffset``,
    ``endOffset``, ``replacement`` (the edit's ``newText``), and ``kind``, in file/edit order.
    """
    flat: list[dict] = []
    for change in workspace_edit.get("changes", []) or []:
        path = change.get("path")
        for edit in change.get("edits", []) or []:
            flat.append(
                {
                    "relativePath": path,
                    "startOffset": edit.get("startOffset"),
                    "endOffset": edit.get("endOffset"),
                    "replacement": edit.get("newText"),
                    "kind": edit.get("kind"),
                }
            )
    return flat


def file_ops(workspace_edit: dict) -> list[dict]:
    """Flattens V1 ``fileOperations[]`` to the flat view tests assert on.

    A rename's V1 ``oldPath``/``newPath`` are surfaced as ``relativePath``/``newRelativePath``; a create/delete's
    ``path`` is surfaced as ``relativePath`` (with ``content`` preserved for create).
    """
    ops: list[dict] = []
    for op in workspace_edit.get("fileOperations", []) or []:
        if op.get("kind") == "rename":
            ops.append({"kind": "rename", "relativePath": op.get("oldPath"), "newRelativePath": op.get("newPath")})
        else:
            ops.append({"kind": op.get("kind"), "relativePath": op.get("path"), "content": op.get("content")})
    return ops


def _preview_rename(sidecar_jar: Path, project_root: Path, relative_path: str, line: int, column: int, new_name: str) -> dict:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        return client.preview("semanticRename", {"relativePath": relative_path, "line": line, "column": column, "newName": new_name})
    finally:
        client.shutdown()



def _preview_safe_delete(sidecar_jar: Path, project_root: Path, relative_path: str, line: int, column: int, allow_public_api: bool = False) -> dict:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        return client.preview(
            "safeDelete", {"relativePath": relative_path, "line": line, "column": column, "allowPublicApi": allow_public_api}
        )
    finally:
        client.shutdown()



def _preview_op(sidecar_jar: Path, project_root: Path, operation: str, params: dict) -> dict:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        return client.preview(operation, params)
    finally:
        client.shutdown()



def _write_two_module_project(tmp_path: Path, mod_b_app: str) -> None:
    src = tmp_path / "src/main/java"
    (src / "modA/coreapi").mkdir(parents=True)
    (src / "modA/internal").mkdir(parents=True)
    (src / "modB/app").mkdir(parents=True)
    (src / "modA/module-info.java").write_text("module modA { exports coreapi; }\n", encoding="utf-8")
    (src / "modA/coreapi/Api.java").write_text(
        "package coreapi; public class Api { public static int v() { return 1; } }\n", encoding="utf-8"
    )
    (src / "modA/internal/Secret.java").write_text(
        "package internal; public class Secret { public static int s() { return 2; } }\n", encoding="utf-8"
    )
    (src / "modB/module-info.java").write_text("module modB { requires modA; }\n", encoding="utf-8")
    (src / "modB/app/App.java").write_text(mod_b_app, encoding="utf-8")



# Explicit config listing both source roots keeps cross-source-set operation tests hermetic (no build-tool extraction)
# while still exercising the union of main+test roots that SemanticIndex builds.
CROSS_SOURCE_SET_CONFIG = json.dumps(
    {"buildToolMode": "explicit", "sourceRoots": ["src/main/java", "src/test/java"], "allowIncompleteAnalysis": True}
)


def _write_cross_source_set_project(tmp_path: Path) -> None:
    """Creates a project whose test source set references a package-private main symbol (no build file: explicit config)."""
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    # The method is package-private so safe delete reaches the reference check rather than the public-API refusal.
    (main_src / "Service.java").write_text(
        "package demo;\npublic class Service {\n    int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (test_src / "ServiceTest.java").write_text(
        "package demo;\nclass ServiceTest {\n    int run() { return new Service().value(); }\n}\n", encoding="utf-8"
    )



def _write_demo_main(tmp_path: Path) -> Path:
    """Writes a minimal compiling demo project and returns the source file."""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text("package demo;\nclass Main {\n    int value = 1;\n}\n", encoding="utf-8")
    return source



def _crafted_apply(client, replacement: str, source: Path):
    """Returns an apply_refactor stub yielding a single character-offset workspace edit with the given replacement."""
    from serena.java_refactor.workspace_edit import sha256_bytes

    old_hash = sha256_bytes(source.read_bytes())
    workspace_edit = {
        "changes": [
            {
                "path": "src/main/java/demo/Main.java",
                "oldSha256": old_hash,
                "edits": [
                    {
                        "startOffset": 0,
                        "endOffset": len(source.read_text(encoding="utf-8")),
                        "newText": replacement,
                        "kind": "REPLACE",
                    }
                ],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }
    return lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit}



def _plain_project(tmp_path: Path) -> None:
    """Writes a single-file conventional Java project (no build tool) used by the annotation-processing-mode tests."""
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")



def _build_processor_jar(tmp_dir: Path) -> Path:
    """Compiles a tiny no-op annotation processor into a jar (with its META-INF/services entry) for offline tests."""
    src_dir = tmp_dir / "proc_src" / "proc"
    src_dir.mkdir(parents=True, exist_ok=True)
    (src_dir / "NoopProcessor.java").write_text(
        "package proc;\n"
        "import java.util.Set;\n"
        "import javax.annotation.processing.*;\n"
        "import javax.lang.model.element.TypeElement;\n"
        '@SupportedAnnotationTypes("*")\n'
        "public class NoopProcessor extends AbstractProcessor {\n"
        "    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) { return false; }\n"
        "}\n",
        encoding="utf-8",
    )
    classes_dir = tmp_dir / "proc_classes"
    services_dir = classes_dir / "META-INF" / "services"
    services_dir.mkdir(parents=True, exist_ok=True)
    (services_dir / "javax.annotation.processing.Processor").write_text("proc.NoopProcessor\n", encoding="utf-8")
    subprocess.run(["javac", "-d", str(classes_dir), str(src_dir / "NoopProcessor.java")], check=True, capture_output=True, text=True)
    jar_path = tmp_dir / "proc.jar"
    subprocess.run(["jar", "cf", str(jar_path), "-C", str(classes_dir), "."], check=True, capture_output=True, text=True)
    return jar_path



# --- G013: cross-source-set validation under divergent classpaths / source levels (real Gradle, offline) ---------------
#
# A test source set typically references main symbols, yet main's compiled output is usually absent (nothing built), so
# validating each source set in isolation would emit spurious "cannot find symbol" errors and roll back valid edits. The
# fix adds the other source sets' source roots to -sourcepath with -implicit:none so cross-set references resolve against
# source while each file's diagnostics are still produced under its OWN source set's options. These tests pin both
# directions: no false-reject (valid edits apply) and no false-accept (genuine breaks are still reported / rolled back).


def _write_divergent_gradle_project(tmp_path: Path) -> None:
    """A real Gradle project whose test source set has a test-only vendored dep AND references a main symbol."""
    vendor_jar = _build_vendored_jar(tmp_path, "vendor", "TestKit", "public static int help() { return 7; }")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "divergent"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\ndependencies {{ testImplementation(files("{vendor_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Service.java").write_text(
        "package demo;\npublic class Service {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (test_src / "ServiceTest.java").write_text(
        "package demo;\nimport vendor.TestKit;\n"
        "class ServiceTest {\n    int run() { return new Service().value() + TestKit.help(); }\n}\n",
        encoding="utf-8",
    )



def _write_source_level_divergent_project(tmp_path: Path, main_uses_textblock: bool) -> None:
    """Gradle project: main compiled at release 11, test at release 17; one of them uses a 17-only text block."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "levels"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.named<JavaCompile>("compileJava") { options.release.set(11) }\n'
        'tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    main_value = '"""\nhi"""' if main_uses_textblock else '"x"'
    (main_src / "Service.java").write_text(
        f"package demo;\npublic class Service {{\n    public String value() {{ return {main_value}; }}\n}}\n",
        encoding="utf-8",
    )
    test_tail = ' + """\nhi"""' if not main_uses_textblock else ""
    (test_src / "ServiceTest.java").write_text(
        f"package demo;\nclass ServiceTest {{\n    String run() {{ return new Service().value(){test_tail}; }}\n}}\n",
        encoding="utf-8",
    )



def _apply_edits_to_text(source: str, edits: list[dict]) -> str:
    """Applies a flattened ``text_edits(...)`` list to a source string, splicing high offsets first so earlier offsets
    stay valid. Offsets are UTF-16 code units; for the ASCII fixtures these tests use they coincide with Python indices.
    """
    for edit in sorted(edits, key=lambda e: e["startOffset"], reverse=True):
        source = source[: edit["startOffset"]] + (edit["replacement"] or "") + source[edit["endOffset"] :]
    return source


def _utf16_offset(text: str, code_point_index: int) -> int:
    """The UTF-16 code-unit offset for a Python code-point index (the sidecar emits char offsets in UTF-16 units)."""
    return len(text[:code_point_index].encode("utf-16-le")) // 2



def _write_generated_root_project(tmp_path: Path, generated_java: str) -> None:
    """A Gradle project with a compiled (non-build/) generated source root containing the given Gen.java contents."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'java { sourceCompatibility = JavaVersion.VERSION_17 }\n'
        'sourceSets { main { java { srcDir("src/generated/java") } } }\n',
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/demo").mkdir(parents=True)
    (tmp_path / "src/main/java/demo/Api.java").write_text(
        "package demo; public class Api { public void run() {} }\n", encoding="utf-8"
    )
    gen = tmp_path / "src/generated/java/demo"
    gen.mkdir(parents=True)
    (gen / "Gen.java").write_text(generated_java, encoding="utf-8")
