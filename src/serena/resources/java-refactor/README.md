This directory is the wheel-resource location for the optional Java refactoring sidecar jar.

Packaged wheels may provide `serena-java-refactor.jar` here. Development checkouts normally use `java-refactor/build/libs/serena-java-refactor*.jar` or `SERENA_JAVA_REFACTOR_JAR`.

The committed `serena-java-refactor.jar` is generated from the `java-refactor/` Java source and must be kept in sync. Regenerate it with the Gradle wrapper (preferred, pins Gradle 8.4 for reproducible output); this also refreshes the committed source fingerprint (`serena-java-refactor.jar.sha256`):

```
cd java-refactor && ./gradlew syncResourceJar
```

Staleness is prevented two ways:

- `serena-java-refactor.jar.sha256` is a deterministic fingerprint of the `java-refactor/` source tree (sources + build scripts). The non-skippable `test_bundled_sidecar_jar_fingerprint_matches_source` test recomputes it from source and fails — in CI, with no JDK/Gradle required — if the committed jar/fingerprint drifts from source.
- The wheel build hook (`hatch_build.py`) rebuilds the jar from source when a JDK + Gradle are available and **fails the build** if that rebuild fails (no stale-jar fallback); when no build toolchain is available it instead verifies the committed fingerprint matches source and fails hard on a mismatch. The `test_bundled_resource_jar_matches_fresh_build` test additionally diffs the committed jar against a fresh reproducible build byte-for-byte when a toolchain is present.
