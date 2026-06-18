import os
from pathlib import Path

import pytest

from test.serena._java_refactor_sidecar_helpers import _resolve_sidecar_java


@pytest.fixture(scope="session", autouse=True)
def _pin_modern_jdk_on_path():
    """Put a Java >= 17 launcher first on PATH for the whole session.

    The sidecar jar is built with the Gradle Java 17 toolchain (class-file version 61); a launcher JDK older than 17
    raises ``UnsupportedClassVersionError`` when loading it. Sidecar tests that go through ``JavaRefactorManager`` or
    construct ``JavaRefactorClient(sidecar_jar)`` without an explicit ``java_command`` resolve the ambient ``java``, and
    the fixtures' direct ``javac``/``jar`` subprocess calls resolve the ambient toolchain. On hosts whose default
    ``java`` predates 17 those resolve to a JRE that cannot run the jar. Pinning the same modern JDK the explicit helpers
    already select keeps the whole suite consistent without altering any test's assertions or behaviour.
    """
    java_path = Path(_resolve_sidecar_java())
    if not java_path.is_file():
        yield
        return
    bin_dir = java_path.parent
    java_home = bin_dir.parent
    old_path = os.environ.get("PATH", "")
    old_java_home = os.environ.get("JAVA_HOME")
    os.environ["PATH"] = f"{bin_dir}{os.pathsep}{old_path}"
    os.environ["JAVA_HOME"] = str(java_home)
    try:
        yield
    finally:
        os.environ["PATH"] = old_path
        if old_java_home is None:
            os.environ.pop("JAVA_HOME", None)
        else:
            os.environ["JAVA_HOME"] = old_java_home
