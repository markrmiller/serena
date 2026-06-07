package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Coarse in-process cache for {@link BuildModelExtractor} output. Extraction spawns a Gradle daemon / Maven JVM and
 * takes seconds, so it must not run on every {@code status}/{@code preview}/{@code apply}. The cache key is the content
 * hash of only the build files (poms, build scripts, settings, gradle.properties, wrappers) plus the build kind and the
 * offline flag; source {@code .java} edits do not change the build model, so they reuse the cached classpath while the
 * separate {@link ProjectModelCache} re-runs javac.
 */
public final class ExtractionCache implements ProjectModelDiscoverer.ExtractionProvider {
    // Root-only files (wrappers, maven.config) that are not covered by the recursive build-file walk. Subproject build
    // files, version catalogs, and buildSrc are added recursively via ProjectModelDiscoverer.collectBuildFiles (M5).
    private static final List<String> ROOT_BUILD_FILES = List.of(
            "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd", ".mvn/maven.config"
    );

    private final BuildModelExtractor extractor;
    private String cachedKey;
    private BuildModelExtractor.Result cachedResult;

    public ExtractionCache() {
        this(new BuildModelExtractor());
    }

    ExtractionCache(BuildModelExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public BuildModelExtractor.Result extract(ProjectModelDiscoverer.BuildKind buildKind, Path projectRoot, ProjectModelDiscoverer.DiscoveryConfig config) {
        String key = keyFor(buildKind, projectRoot, config.offline(), config.jdtlsSettings());
        if (key.equals(cachedKey) && cachedResult != null) {
            return cachedResult;
        }
        BuildModelExtractor.Result result = buildKind == ProjectModelDiscoverer.BuildKind.GRADLE
                ? extractor.extractGradle(projectRoot, config.offline(), config.jdtlsSettings())
                : extractor.extractMaven(projectRoot, config.offline(), null, config.jdtlsSettings());
        // Only cache successful extractions; a transient failure (e.g. a daemon hiccup) should be retried next call.
        if (!result.isFailure()) {
            cachedKey = key;
            cachedResult = result;
        }
        return result;
    }

    private static String keyFor(ProjectModelDiscoverer.BuildKind buildKind, Path projectRoot, boolean offline, BuildModelExtractor.JdtlsSettings jdtls) {
        try {
            // A NUL byte delimiter separates every field/entry so that adjacent values cannot run together and collide
            // (e.g. ("src/foo", GRADLE) must not hash the same as ("src/fooGRADLE", ...)). NUL cannot occur in a path,
            // build-kind name, or the offline marker, so it is an unambiguous separator while remaining deterministic.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] separator = new byte[] {0};
            digest.update(projectRoot.toAbsolutePath().normalize().toString().getBytes());
            digest.update(separator);
            digest.update(buildKind.name().getBytes());
            digest.update(separator);
            digest.update((offline ? "offline" : "online").getBytes());
            digest.update(separator);
            // JDTLS-derived build-tool settings change dependency resolution (settings.xml mirrors/credentials, Gradle
            // user home/JDK, wrapper preference), so a change in any of them must invalidate the cached extraction.
            for (String value : List.of(
                    String.valueOf(jdtls.mavenUserSettings()),
                    String.valueOf(jdtls.gradleUserHome()),
                    String.valueOf(jdtls.gradleJavaHome()),
                    String.valueOf(jdtls.gradleWrapperEnabled()))) {
                digest.update(value.getBytes());
                digest.update(separator);
            }
            for (String name : ROOT_BUILD_FILES) {
                Path file = projectRoot.resolve(name);
                digest.update(name.getBytes());
                digest.update(separator);
                if (Files.isRegularFile(file)) {
                    digest.update(Files.readAllBytes(file));
                } else {
                    digest.update("<absent>".getBytes());
                }
                digest.update(separator);
            }
            // Recursively hash every build file (subproject build.gradle/pom.xml, gradle.properties, version catalogs,
            // buildSrc) so a dependency change in any subproject invalidates the cached extraction (M5). Keyed by
            // project-relative path so the hash is stable across machines.
            Path root = projectRoot.toAbsolutePath().normalize();
            for (Path file : ProjectModelDiscoverer.collectBuildFiles(root)) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes());
                digest.update(separator);
                if (Files.isRegularFile(file)) {
                    digest.update(Files.readAllBytes(file));
                } else {
                    digest.update("<absent>".getBytes());
                }
                digest.update(separator);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            // A key that cannot be computed must never collide with a real one, so force a cache miss.
            return "uncacheable:" + System.nanoTime();
        }
    }
}
