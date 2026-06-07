"""Hatch build hook that bundles the Java refactoring sidecar jar built from current source.

This makes wheel packaging deterministic and stale-proof:

* When a JDK + Gradle are available, the sidecar jar is rebuilt from `java-refactor/` source and copied into
  `src/serena/resources/java-refactor/`, and the committed source fingerprint is refreshed. If that rebuild fails the
  build FAILS HARD — a stale checked-in jar is never an acceptable fallback.
* When no build toolchain is available (e.g. building a wheel from an sdist on a machine without Java), the hook
  verifies the committed source fingerprint matches the current Java source. A match proves the checked-in jar
  corresponds to source; a mismatch (or missing fingerprint) FAILS HARD rather than shipping unverifiable bytecode.

Either way the wheel can never ship a sidecar jar that is stale relative to the committed Java source.
"""

import importlib.util
import os
import shutil
import subprocess
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface


def _load_fingerprint_module():
    """Loads the sidecar fingerprint helper by file path.

    Imported as a standalone module rather than via ``serena.java_refactor`` so the heavy package __init__ (sensai/LSP
    imports) is not required in the build environment; the helper depends only on the standard library.
    """
    module_path = Path(__file__).parent / "src" / "serena" / "java_refactor" / "_sidecar_fingerprint.py"
    spec = importlib.util.spec_from_file_location("_serena_sidecar_fingerprint", module_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_fingerprint = _load_fingerprint_module()
compute_source_fingerprint = _fingerprint.compute_source_fingerprint
compute_jar_digest = _fingerprint.compute_jar_digest
read_committed_fingerprint = _fingerprint.read_committed_fingerprint
write_fingerprint = _fingerprint.write_fingerprint

RESOURCE_JAR = Path("src/serena/resources/java-refactor/serena-java-refactor.jar")


def _gradle_wrapper(root: Path) -> Path | None:
    """Returns the Gradle wrapper executable in `java-refactor/` if present and runnable, else None."""
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    wrapper = root / "java-refactor" / wrapper_name
    if not wrapper.exists():
        return None
    # On POSIX the wrapper must be executable; gradlew.bat is invoked via the interpreter on Windows.
    if os.name != "nt" and not os.access(wrapper, os.X_OK):
        return None
    return wrapper


class JavaRefactorJarBuildHook(BuildHookInterface):
    PLUGIN_NAME = "java-refactor-jar"

    def initialize(self, version: str, build_data: dict) -> None:
        root = Path(self.root)
        resource_jar = root / RESOURCE_JAR
        sidecar_source = root / "java-refactor" / "build.gradle.kts"
        java = shutil.which("java")

        if sidecar_source.exists() and java:
            # Toolchain present: rebuild the jar from source (guaranteeing freshness), then refresh the committed
            # fingerprint. A rebuild FAILURE is fatal — never silently ship a possibly-stale checked-in jar.
            wrapper = _gradle_wrapper(root)
            gradle = shutil.which("gradle")
            if wrapper is not None:
                command, cwd = [str(wrapper), "syncResourceJar"], root / "java-refactor"
            elif gradle is not None:
                command, cwd = [gradle, "-p", "java-refactor", "syncResourceJar"], root
            else:
                command, cwd = None, root

            if command is not None:
                try:
                    subprocess.run(command, cwd=cwd, check=True, capture_output=True, text=True)
                except subprocess.CalledProcessError as e:
                    raise RuntimeError(
                        "java-refactor: gradle syncResourceJar failed; refusing to package a possibly-stale sidecar "
                        "jar.\n" + (e.stderr or e.stdout or "")
                    ) from e
                write_fingerprint(root)
                self.app.display_info("java-refactor: bundled sidecar jar rebuilt from source and fingerprint refreshed")
            else:
                # Java exists but no Gradle to rebuild with: fall through to fingerprint verification below.
                self._verify_fingerprint(root)
        elif sidecar_source.exists():
            # No JDK to rebuild with (e.g. wheel-from-sdist on a Java-less machine): the committed jar may only ship if
            # its source fingerprint still matches the current Java source.
            self._verify_fingerprint(root)

        if not resource_jar.exists():
            raise FileNotFoundError(
                f"Bundled Java refactor sidecar jar is missing: {RESOURCE_JAR}. "
                "Build it with `gradle -p java-refactor syncResourceJar` before packaging."
            )

    def _verify_fingerprint(self, root: Path) -> None:
        """Fails hard unless the committed fingerprint matches BOTH the current Java source and the committed jar bytes."""
        committed = read_committed_fingerprint(root)
        if committed is None:
            raise RuntimeError(
                "java-refactor: the sidecar fingerprint is missing, so the bundled jar cannot be verified against "
                "source. Rebuild it with `gradle -p java-refactor syncResourceJar` (which also refreshes the "
                "fingerprint) before packaging."
            )
        current_source = compute_source_fingerprint(root)
        current_jar = compute_jar_digest(root)
        if committed.get("source") != current_source:
            raise RuntimeError(
                "java-refactor: the bundled sidecar jar is stale relative to the Java source (source fingerprint "
                f"{current_source} != committed {committed.get('source')}). Rebuild it with "
                "`gradle -p java-refactor syncResourceJar`."
            )
        if committed.get("jar") != current_jar:
            raise RuntimeError(
                "java-refactor: the committed sidecar jar bytes do not match the committed fingerprint "
                f"(jar digest {current_jar} != committed {committed.get('jar')}). Rebuild it with "
                "`gradle -p java-refactor syncResourceJar`."
            )
        self.app.display_info("java-refactor: verified bundled sidecar jar fingerprint matches source and jar bytes")
