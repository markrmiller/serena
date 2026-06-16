package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the project's own build tool (Gradle via a bundled init script, Maven via stock goals) to extract a real
 * build model: per-module source sets with resolved compile classpaths, generated roots, and compiler settings. The
 * extractor adds no runtime dependencies to the sidecar jar; Gradle JSON is parsed with {@link Json} and the Maven
 * effective-POM with the JDK's built-in DOM parser.
 *
 * <p>On any failure (tool missing, non-zero exit, unparseable output) the returned {@link Result} carries an error
 * rather than a model, so callers can fail closed or fall back per configuration.</p>
 */
public final class BuildModelExtractor {
    private static final String INIT_SCRIPT_RESOURCE = "/gradle/serena-model.init.gradle";
    // Separate compile- and test-scope classpaths so the main source set is compiled against compile-scope
    // dependencies only and never resolves test-only libraries (e.g. JUnit). The test source set uses the
    // test-scope classpath, which is a superset of compile scope.
    // Scope discriminators used in the per-module classpath file names written under an out-of-tree temp directory.
    private static final String MAVEN_COMPILE_SCOPE = "compile";
    private static final String MAVEN_TEST_SCOPE = "test";
    private static final int TIMEOUT_SECONDS = 180;

    record Result(BuildModel model, String error, String warning) {
        static Result ok(BuildModel model) {
            return new Result(model, null, null);
        }

        static Result ok(BuildModel model, String warning) {
            return new Result(model, null, warning);
        }

        static Result failure(String error) {
            return new Result(null, error, null);
        }

        boolean isFailure() {
            return error != null;
        }
    }

    Result extractGradle(Path projectRoot, boolean offline) {
        return extractGradle(projectRoot, offline, JdtlsSettings.NONE);
    }

    /**
     * Extracts a Gradle build model, honoring the optional JDTLS-derived settings (Gradle user home, Gradle JDK, and
     * wrapper preference) so the sidecar's extraction mirrors how Serena's Java language server resolves the project.
     */
    Result extractGradle(Path projectRoot, boolean offline, JdtlsSettings jdtls) {
        Path launcher = resolveGradleLauncher(projectRoot, jdtls);
        if (launcher == null) {
            return Result.failure("Gradle build-model extraction failed: no ./gradlew or gradle executable was found. "
                    + fallbackHint());
        }
        Path initScript = null;
        Path modelOutDir = null;
        Path projectCacheDir = null;
        try {
            initScript = writeInitScript();
            // Each project writes its own file into this directory (see serena-model.init.gradle), so a multi-project
            // build where several subprojects have Java sources does not have later projects overwrite earlier ones.
            modelOutDir = Files.createTempDirectory("serena-model");
            // Discovery must not modify the project (Phase 1 contract). Gradle otherwise writes its project-local cache
            // into <projectRoot>/.gradle; --project-cache-dir relocates that state to a throwaway temp directory so the
            // project tree is left byte-for-byte unchanged. (The shared Gradle user home / daemon live outside the tree.)
            projectCacheDir = Files.createTempDirectory("serena-gradle-cache");
            List<String> command = new ArrayList<>();
            command.add(launcher.toString());
            command.add("-q");
            command.add("--project-cache-dir");
            command.add(projectCacheDir.toAbsolutePath().toString());
            command.add("--init-script");
            command.add(initScript.toString());
            command.add("dumpSerenaModel");
            command.add("-Dserena.model.outDir=" + modelOutDir.toAbsolutePath());
            if (offline) {
                command.add("--offline");
            }
            // Reuse Serena's Java LS Gradle settings (project-model plan section 3 step 5) when supplied: a custom Gradle
            // user home isolates the daemon/cache, and a Gradle JDK pins the toolchain the wrapper/distribution runs on.
            if (jdtls.gradleUserHome() != null) {
                command.add("--gradle-user-home");
                command.add(jdtls.gradleUserHome());
            }
            if (jdtls.gradleJavaHome() != null) {
                command.add("-Dorg.gradle.java.home=" + jdtls.gradleJavaHome());
            }
            ProcessResult process = run(command, projectRoot);
            if (process.exitCode() != 0) {
                return Result.failure("Gradle build-model extraction failed (exit " + process.exitCode() + "): "
                        + tail(process.output()) + ". " + fallbackHint());
            }
            return Result.ok(parseGradleModel(mergeGradleModuleEntries(modelOutDir)));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Result.failure("Gradle build-model extraction failed: " + e.getMessage() + ". " + fallbackHint());
        } catch (RuntimeException e) {
            return Result.failure("Gradle build-model extraction produced unparseable output: " + e.getMessage()
                    + ". " + fallbackHint());
        } finally {
            deleteQuietly(initScript);
            deleteDirectoryQuietly(modelOutDir);
            deleteDirectoryQuietly(projectCacheDir);
        }
    }

    /**
     * Concatenates the {@code modules} entries from every per-project model file the init script wrote into
     * {@code modelOutDir} into one combined list. Each Gradle project writes its own file (keyed by project path) so
     * that, in a multi-project build, no project's source sets overwrite another's; merging here reassembles them.
     */
    private static List<Object> mergeGradleModuleEntries(Path modelOutDir) throws IOException {
        List<Object> merged = new ArrayList<>();
        if (Files.isDirectory(modelOutDir)) {
            List<Path> files;
            try (var stream = Files.list(modelOutDir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
            for (Path file : files) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Object> root = Json.parseObject(json);
                if (root.get("modules") instanceof List<?> moduleList) {
                    merged.addAll(moduleList);
                }
            }
        }
        return merged;
    }

    Result extractMaven(Path projectRoot, boolean offline, Path localRepo) {
        return extractMaven(projectRoot, offline, localRepo, JdtlsSettings.NONE);
    }

    /**
     * Extracts a Maven build model, honoring the optional JDTLS-derived Maven user settings.xml (project-model plan
     * section 3 step 5) by passing {@code -s <settings>} to both Maven invocations so credentials/mirrors/profiles match
     * how Serena's Java language server resolves the project.
     */
    Result extractMaven(Path projectRoot, boolean offline, Path localRepo, JdtlsSettings jdtls) {
        return extractMaven(projectRoot, offline, localRepo, jdtls, List.of());
    }

    Result extractMaven(Path projectRoot, boolean offline, Path localRepo, JdtlsSettings jdtls, List<String> profiles) {
        Path launcher = resolveLauncher(projectRoot, "mvnw", "mvnw.cmd", "mvn");
        if (launcher == null) {
            return Result.failure("Maven build-model extraction failed: no ./mvnw or mvn executable was found. "
                    + fallbackHint());
        }
        Path effectivePom = null;
        Path classpathDir = null;
        try {
            effectivePom = Files.createTempFile("serena-effective-pom", ".xml");
            // Phase 1 acceptance requires build-model discovery to never modify the project tree. dependency:build-classpath
            // resolves mdep.outputFile relative to each reactor module's basedir, so a relative name would write files
            // alongside the project's sources. We instead pass an ABSOLUTE outputFile under an out-of-tree temp directory,
            // embedding Maven's ${project.groupId}/${project.artifactId} expressions so each reactor module writes its own
            // (correctly distinct) classpath file there. Nothing is ever written inside the project root.
            classpathDir = Files.createTempDirectory("serena-maven-cp");

            List<String> pomCommand = mavenCommand(launcher, offline, localRepo, jdtls, profiles,
                    "help:effective-pom", "-Doutput=" + effectivePom.toAbsolutePath());
            ProcessResult pomProcess = run(pomCommand, projectRoot);
            if (pomProcess.exitCode() != 0) {
                return Result.failure("Maven effective-pom extraction failed (exit " + pomProcess.exitCode() + "): "
                        + tail(pomProcess.output()) + ". " + fallbackHint());
            }

            // Resolve compile and test scopes separately so main and test source sets get the correct (and correctly
            // distinct) classpaths. A non-zero build-classpath exit does NOT abort extraction: in an unbuilt multi-module
            // reactor a module that references a sibling which is not yet installed/compiled cannot resolve that sibling's
            // jar offline, but its sibling is modeled as a source dependency (its source root feeds -sourcepath). What we
            // must NOT do (G003) is silently treat a possibly-incomplete classpath as proven: an unresolved EXTERNAL
            // (non-reactor) dependency can leave javac clean on the edited file while corrupting semantic planning
            // elsewhere. So we record which scopes failed to resolve and let parseMavenModel mark exactly the affected
            // source sets classpath-UNPROVEN, which flows into the same apply-refusal gate as javac diagnostics.
            List<String> classpathWarnings = new ArrayList<>();
            Set<String> unresolvedScopes = new LinkedHashSet<>();
            String compileWarning = runBuildClasspath(launcher, offline, localRepo, jdtls, profiles, projectRoot, classpathDir,
                    MAVEN_COMPILE_SCOPE);
            if (compileWarning != null) {
                classpathWarnings.add(compileWarning);
                unresolvedScopes.add(MAVEN_COMPILE_SCOPE);
            }
            String testWarning = runBuildClasspath(launcher, offline, localRepo, jdtls, profiles, projectRoot, classpathDir,
                    MAVEN_TEST_SCOPE);
            if (testWarning != null) {
                classpathWarnings.add(testWarning);
                unresolvedScopes.add(MAVEN_TEST_SCOPE);
            }

            return Result.ok(parseMavenModel(projectRoot, effectivePom, classpathDir, unresolvedScopes),
                    classpathWarnings.isEmpty() ? null : String.join(" ", classpathWarnings));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Result.failure("Maven build-model extraction failed: " + e.getMessage() + ". " + fallbackHint());
        } catch (RuntimeException e) {
            return Result.failure("Maven build-model extraction produced unparseable output: " + e.getMessage()
                    + ". " + fallbackHint());
        } finally {
            deleteQuietly(effectivePom);
            deleteDirectoryQuietly(classpathDir);
        }
    }

    /**
     * Runs {@code dependency:build-classpath} for a single scope. Each reactor module writes its classpath to an
     * absolute, per-module file under {@code classpathDir} (outside the project tree) via Maven's
     * {@code ${project.groupId}}/{@code ${project.artifactId}} interpolation. Returns a failure {@link Result} on a
     * non-zero exit, or {@code null} on success.
     */
    private static String runBuildClasspath(Path launcher, boolean offline, Path localRepo, JdtlsSettings jdtls,
            List<String> profiles, Path projectRoot, Path classpathDir, String scope) throws IOException, InterruptedException {
        // The ${...} expressions are interpolated by Maven per module (NOT the shell — commands run via ProcessBuilder),
        // yielding one file per reactor module under the out-of-tree directory.
        String outputFile = classpathDir.toAbsolutePath()
                + java.io.File.separator + "cp-${project.groupId}__${project.artifactId}-" + scope + ".txt";
        // --fail-never lets every reactor module attempt its classpath resolution even if one fails (e.g. an unbuilt
        // sibling that cannot be resolved offline); the modules that succeed still write their classpath files, and a
        // module whose classpath is missing simply gets an empty classpath (readMavenClasspath), relying on the
        // source-dependency edge for sibling symbols. Returns a warning string on non-zero exit, or null on success.
        List<String> command = mavenCommand(launcher, offline, localRepo, jdtls, profiles,
                "dependency:build-classpath", "--fail-never", "-Dmdep.outputFile=" + outputFile, "-DincludeScope=" + scope);
        ProcessResult process = run(command, projectRoot);
        if (process.exitCode() != 0) {
            return "Maven build-classpath extraction (" + scope + " scope) had unresolved entries (exit "
                    + process.exitCode() + "); affected modules' dependency classpaths may be incomplete and sibling "
                    + "reactor modules are resolved from source where possible. Detail: " + tail(process.output());
        }
        return null;
    }

    /** Per-module classpath file name matching the {@code ${project.groupId}__${project.artifactId}} pattern Maven wrote. */
    private static String mavenClasspathFileName(String groupId, String artifactId, String scope) {
        return "cp-" + groupId + "__" + artifactId + "-" + scope + ".txt";
    }

    private static List<String> mavenCommand(Path launcher, boolean offline, Path localRepo, JdtlsSettings jdtls, List<String> profiles, String... goals) {
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.add("-q");
        command.add("-B");
        for (String profile : profiles) {
            String trimmed = profile == null ? "" : profile.trim();
            if (!trimmed.isEmpty()) {
                command.add("-P" + trimmed);
            }
        }
        for (String goal : goals) {
            command.add(goal);
        }
        if (offline) {
            command.add("-o");
        }
        if (localRepo != null) {
            command.add("-Dmaven.repo.local=" + localRepo.toAbsolutePath());
        }
        // Reuse Serena's Java LS Maven settings.xml (project-model plan section 3 step 5) so mirrors/credentials/profiles
        // that affect dependency resolution match the language server's view of the project.
        if (jdtls.mavenUserSettings() != null) {
            command.add("-s");
            command.add(jdtls.mavenUserSettings());
        }
        return command;
    }

    // --- Gradle parsing -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static BuildModel parseGradleModel(List<Object> moduleList) {
        // Group source-set entries by project path so each Gradle subproject becomes one BuildModel.Module.
        Map<String, List<BuildModel.ModelSourceSet>> byProject = new LinkedHashMap<>();
        for (Object moduleObj : moduleList) {
            if (!(moduleObj instanceof Map<?, ?> entry)) {
                continue;
            }
            Map<String, Object> module = (Map<String, Object>) entry;
            String project = stringValue(module.get("project"), ":");
            BuildModel.ModelSourceSet sourceSet = new BuildModel.ModelSourceSet(
                    stringValue(module.get("sourceSet"), "main"),
                    stringList(module.get("srcDirs")),
                    stringList(module.get("generatedRoots")),
                    stringList(module.get("outputDirs")),
                    stringList(module.get("classpath")),
                    stringList(module.get("modulePath")),
                    stringList(module.get("annotationProcessorPath")),
                    nullableString(module.get("release")),
                    nullableString(module.get("source")),
                    nullableString(module.get("target")),
                    nullableString(module.get("encoding")),
                    stringList(module.get("dependsOnProjects")),
                    stringList(module.get("compilerArgs")),
                    // G004: the init script sets classpathUnproven=true for a source set whose compile classpath could not
                    // be resolved (an unresolvable dependency, an offline included-build substitution, ...). The init
                    // script ALWAYS emits this field for every Gradle source set, so an absent key only occurs for
                    // non-Gradle/legacy model payloads; absent/false therefore means proven and keeps classpathProven=true.
                    !boolValue(module.get("classpathUnproven"))
            );
            byProject.computeIfAbsent(project, key -> new ArrayList<>()).add(sourceSet);
        }
        List<BuildModel.Module> modules = new ArrayList<>();
        for (Map.Entry<String, List<BuildModel.ModelSourceSet>> entry : byProject.entrySet()) {
            modules.add(new BuildModel.Module(entry.getKey(), entry.getValue()));
        }
        return new BuildModel(modules);
    }

    // --- Maven parsing --------------------------------------------------------------------------------------------

    // Package-private so the G003 classpath-completeness unit test can drive it with a crafted effective-POM, an
    // out-of-tree classpath directory (with missing/empty per-module classpath files), and the set of scopes whose
    // dependency:build-classpath goal failed to resolve — exactly the inputs the live Maven path would produce.
    static BuildModel parseMavenModel(Path projectRoot, Path effectivePom, Path classpathDir,
            Set<String> unresolvedScopes) throws IOException {
        Document document = parseXml(effectivePom);
        List<Element> projectElements = effectiveProjectElements(document);
        // Map each module's artifactId -> its real directory by walking the aggregator POMs' <module> entries (which are
        // directory paths, NOT artifactIds) and reading each module pom.xml's own artifactId. This is reliable even when
        // a module's directory name differs from its artifactId, which artifactId-based derivation got wrong (M4).
        Map<String, Path> moduleDirByArtifactId = mapModuleDirsByArtifactId(projectRoot);
        // Reactor coordinate index: every reactor module's groupId:artifactId -> its artifactId (the project id used to
        // qualify source-set names). Used to recognize which of a module's resolved dependencies are sibling reactor
        // modules so an intra-reactor source edge can be modeled.
        Map<String, String> reactorArtifactByCoord = new LinkedHashMap<>();
        for (Element project : projectElements) {
            String groupId = mavenProjectGroupId(project);
            String artifactId = textOfChild(project, "artifactId");
            if (groupId != null && artifactId != null) {
                reactorArtifactByCoord.put(groupId.trim() + ":" + artifactId.trim(), artifactId.trim());
            }
        }
        List<BuildModel.Module> modules = new ArrayList<>();
        for (Element project : projectElements) {
            Path moduleDir = mavenModuleDir(projectRoot, project, moduleDirByArtifactId);
            String release = mavenCompilerSetting(project, "release", "maven.compiler.release");
            String source = mavenCompilerSetting(project, "source", "maven.compiler.source");
            String target = mavenCompilerSetting(project, "target", "maven.compiler.target");
            String encoding = mavenProperty(project, "project.build.sourceEncoding");

            Path mainRoot = mavenSourceDir(moduleDir, project, "sourceDirectory", "src/main/java");
            Path testRoot = mavenSourceDir(moduleDir, project, "testSourceDirectory", "src/test/java");

            // Compile scope feeds main; test scope (a superset) feeds test. Keeping them distinct ensures main is never
            // compiled against test-only dependencies. The per-module classpath files live under the out-of-tree temp
            // directory, named by this module's effective groupId/artifactId (matching what Maven interpolated).
            String groupId = mavenProjectGroupId(project);
            String artifactId = textOfChild(project, "artifactId");
            List<String> compileClasspath = readMavenClasspath(classpathDir, groupId, artifactId, MAVEN_COMPILE_SCOPE);
            List<String> testClasspath = readMavenClasspath(classpathDir, groupId, artifactId, MAVEN_TEST_SCOPE);

            // G003: whether each scope's dependency classpath is PROVEN for this module. It is unproven only when the
            // scope's build-classpath goal failed globally AND this module actually declares an external (non-reactor,
            // non-system) dependency that we therefore cannot guarantee was resolved. A module with only reactor-sibling
            // or system-path dependencies (or none) is still proven: siblings resolve from source via -sourcepath and a
            // system dependency carries its own absolute path, so a build-classpath failure does not endanger them.
            boolean declaresExternalDeps = mavenDeclaresExternalDependency(project, reactorArtifactByCoord);
            boolean compileProven = !(unresolvedScopes.contains(MAVEN_COMPILE_SCOPE) && declaresExternalDeps);
            boolean testProven = !(unresolvedScopes.contains(MAVEN_TEST_SCOPE) && declaresExternalDeps);

            // Primary roots from the effective POM plus any additional roots contributed by the build-helper-maven-plugin
            // (a common way Maven projects add generated/extra source roots). Declared roots are kept even if not yet on
            // disk, so the compiler model reflects the project's configured layout rather than only what currently exists.
            List<String> mainSourceRoots = dedup(prependIfDir(mainRoot), mavenBuildHelperSources(project, moduleDir, "add-source"));
            List<String> testSourceRoots = dedup(prependIfDir(testRoot), mavenBuildHelperSources(project, moduleDir, "add-test-source"));

            // Generated roots: the maven-compiler-plugin's annotation-processor output directory (declared by convention,
            // or relocated via <generatedSourcesDirectory>), the configured output directories of known code-generation
            // plugins (protobuf/OpenAPI/JAXB/ANTLR/jOOQ/Avro/QueryDSL/...) which may live outside the conventional parent,
            // and any directory already holding generated .java under the conventional generated-sources parent (covering
            // nested plugin layouts and unknown/custom processors). See mavenGeneratedRoots.
            List<String> mainGenerated = mavenGeneratedRoots(project, moduleDir, "target/generated-sources", "annotations", false);
            List<String> testGenerated = mavenGeneratedRoots(project, moduleDir, "target/generated-test-sources", "test-annotations", true);
            // Maven's conventional compiler output directories (whether or not they exist yet on disk).
            List<String> mainOutput = List.of(moduleDir.resolve("target/classes").toString());
            List<String> testOutput = List.of(moduleDir.resolve("target/test-classes").toString());
            // Resolve the maven-compiler-plugin's declared annotationProcessorPaths (GAV coordinates) against the
            // test classpath jars (the superset), so Maven gets a real -processorpath like Gradle does.
            List<String> annotationProcessorPath = mavenAnnotationProcessorPath(project, testClasspath);

            // Intra-reactor source edges: the other reactor modules this module depends on (matched by groupId:artifactId
            // in the effective POM's resolved <dependencies>). Fed into both source sets so a module that references an
            // unbuilt sibling resolves it from source via the dependsOn -> -sourcepath wiring.
            List<String> reactorDependencies = mavenReactorDependencies(project, reactorArtifactByCoord);

            // The maven-compiler-plugin's effective extra compiler arguments (--enable-preview, --add-exports, -A
            // processor options, -parameters, etc.), shared by both source sets just as a single <configuration> applies
            // to both default-compile and default-testCompile executions.
            List<String> compilerArgs = mavenCompilerArgs(project);

            List<BuildModel.ModelSourceSet> sourceSets = new ArrayList<>();
            if (!mainSourceRoots.isEmpty()) {
                sourceSets.add(new BuildModel.ModelSourceSet("main", mainSourceRoots, mainGenerated,
                        mainOutput, compileClasspath, List.of(), annotationProcessorPath, release, source, target, encoding,
                        reactorDependencies, compilerArgs, compileProven));
            }
            if (!testSourceRoots.isEmpty()) {
                // The test compile classpath also needs main's compiled output; main's source root is added to the test
                // source set's -sourcepath via the dependsOn edge, so symbol resolution against main works even before
                // main is built.
                sourceSets.add(new BuildModel.ModelSourceSet("test", testSourceRoots, testGenerated,
                        testOutput, testClasspath, List.of(), annotationProcessorPath, release, source, target, encoding,
                        reactorDependencies, compilerArgs, testProven));
            }
            if (!sourceSets.isEmpty()) {
                String projectId = textOfChild(project, "artifactId");
                modules.add(new BuildModel.Module(projectId == null ? moduleDir.toString() : projectId, sourceSets));
            }
        }
        return new BuildModel(modules);
    }

    /**
     * The artifactIds of sibling reactor modules this Maven project depends on. Each {@code <dependency>} in the
     * effective POM whose {@code groupId:artifactId} matches another reactor module is captured (self-dependency
     * skipped) so the discoverer can feed that module's source roots into this module's {@code -sourcepath}.
     */
    private static List<String> mavenReactorDependencies(Element project, Map<String, String> reactorArtifactByCoord) {
        String ownArtifact = textOfChild(project, "artifactId");
        String ownTrimmed = ownArtifact == null ? null : ownArtifact.trim();
        Element dependencies = firstChild(project, "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Element dependency : childElements(dependencies, "dependency")) {
            String groupId = textOfChild(dependency, "groupId");
            String artifactId = textOfChild(dependency, "artifactId");
            if (groupId == null || artifactId == null) {
                continue;
            }
            String depArtifact = reactorArtifactByCoord.get(groupId.trim() + ":" + artifactId.trim());
            if (depArtifact != null && !depArtifact.equals(ownTrimmed)) {
                result.add(depArtifact);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * G003: whether this module declares at least one EXTERNAL dependency in its effective POM — a {@code <dependency>}
     * that is neither a sibling reactor module (matched by {@code groupId:artifactId} in {@code reactorArtifactByCoord},
     * which resolves from source via -sourcepath) nor a {@code system}-scoped dependency (which carries its own absolute
     * {@code <systemPath>} and so needs no repository resolution). Only such a dependency makes a failed
     * {@code dependency:build-classpath} dangerous: its jar may be missing from the classpath the model compiles
     * against, which can silently corrupt overload resolution / type hierarchy used in semantic planning.
     */
    private static boolean mavenDeclaresExternalDependency(Element project, Map<String, String> reactorArtifactByCoord) {
        Element dependencies = firstChild(project, "dependencies");
        if (dependencies == null) {
            return false;
        }
        for (Element dependency : childElements(dependencies, "dependency")) {
            String groupId = textOfChild(dependency, "groupId");
            String artifactId = textOfChild(dependency, "artifactId");
            if (groupId == null || artifactId == null) {
                continue;
            }
            if (reactorArtifactByCoord.containsKey(groupId.trim() + ":" + artifactId.trim())) {
                continue;
            }
            String scope = textOfChild(dependency, "scope");
            if (scope != null && "system".equalsIgnoreCase(scope.trim())) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Returns the project elements in an effective-pom output, which is either a single project or a wrapper. */
    private static List<Element> effectiveProjectElements(Document document) {
        Element root = document.getDocumentElement();
        List<Element> result = new ArrayList<>();
        if ("project".equals(root.getLocalName()) || "project".equals(root.getNodeName())) {
            result.add(root);
            return result;
        }
        // help:effective-pom wraps multiple reactor projects in a <projects> element (with XML comments between them).
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && "project".equals(element.getNodeName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Path mavenModuleDir(Path projectRoot, Element project, Map<String, Path> moduleDirByArtifactId) {
        // help:effective-pom does not record each module's directory. Prefer the directory discovered from the
        // aggregator POM's <modules> entries (resolved via each module pom.xml's own artifactId), which is correct even
        // when the directory name differs from the artifactId. Fall back to an artifactId-named directory, then to the
        // project root (single-module case).
        String artifactId = textOfChild(project, "artifactId");
        if (artifactId != null) {
            Path mapped = moduleDirByArtifactId.get(artifactId);
            if (mapped != null) {
                return mapped;
            }
            Path candidate = projectRoot.resolve(artifactId);
            if (Files.isDirectory(candidate.resolve("src")) || Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    /**
     * Maps each reactor module's artifactId to its real directory by walking the aggregator POMs' {@code <module>}
     * directory entries from {@code projectRoot} and reading each module's own {@code pom.xml} artifactId. Nested
     * aggregators are followed transitively. The root project itself is included so a single-module build still maps.
     */
    private static Map<String, Path> mapModuleDirsByArtifactId(Path projectRoot) {
        Map<String, Path> result = new LinkedHashMap<>();
        Deque<Path> pending = new java.util.ArrayDeque<>();
        Set<Path> visited = new java.util.HashSet<>();
        pending.add(projectRoot.toAbsolutePath().normalize());
        while (!pending.isEmpty()) {
            Path dir = pending.poll();
            if (!visited.add(dir)) {
                continue;
            }
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            try {
                Document doc = parseXml(pom);
                Element root = doc.getDocumentElement();
                String artifactId = textOfChild(root, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) {
                    // First mapping wins for a given artifactId (the nearest-to-root directory), keeping behavior stable.
                    result.putIfAbsent(artifactId.trim(), dir);
                }
                Element modules = firstChild(root, "modules");
                if (modules != null) {
                    for (Element module : childElements(modules, "module")) {
                        String rel = module.getTextContent();
                        if (rel != null && !rel.isBlank()) {
                            pending.add(dir.resolve(rel.trim()).toAbsolutePath().normalize());
                        }
                    }
                }
            } catch (IOException ignored) {
                // An unreadable/invalid module pom is skipped; that module's classpath falls back to projectRoot.
            }
        }
        return result;
    }

    private static Path mavenSourceDir(Path moduleDir, Element project, String element, String fallback) {
        Element build = firstChild(project, "build");
        if (build != null) {
            String configured = textOfChild(build, element);
            if (configured != null && !configured.isBlank()) {
                Path path = Path.of(configured.trim());
                return path.isAbsolute() ? path.normalize() : moduleDir.resolve(configured.trim()).normalize();
            }
        }
        return moduleDir.resolve(fallback).normalize();
    }

    private static List<String> readMavenClasspath(Path classpathDir, String groupId, String artifactId, String scope) {
        // dependency:build-classpath wrote each module's classpath under the out-of-tree temp directory, named by the
        // module's effective groupId/artifactId. A null groupId/artifactId (malformed effective-pom) yields no classpath.
        if (classpathDir == null || groupId == null || artifactId == null) {
            return List.of();
        }
        Path classpathFile = classpathDir.resolve(mavenClasspathFileName(groupId, artifactId, scope));
        try {
            if (Files.isRegularFile(classpathFile)) {
                return splitClasspath(Files.readString(classpathFile, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // ignore; an empty classpath surfaces as javac diagnostics
        }
        return List.of();
    }

    /**
     * Returns a project's effective groupId. help:effective-pom fully resolves inheritance, so the project's own
     * {@code <groupId>} is normally present; we fall back to the {@code <parent><groupId>} for robustness.
     */
    private static String mavenProjectGroupId(Element project) {
        String groupId = textOfChild(project, "groupId");
        if (groupId != null && !groupId.isBlank()) {
            return groupId.trim();
        }
        Element parent = firstChild(project, "parent");
        if (parent != null) {
            String parentGroupId = textOfChild(parent, "groupId");
            if (parentGroupId != null && !parentGroupId.isBlank()) {
                return parentGroupId.trim();
            }
        }
        return null;
    }

    private static List<String> splitClasspath(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String part : value.trim().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static String mavenCompilerSetting(Element project, String pluginParam, String property) {
        String fromPlugin = mavenCompilerPluginConfig(project, pluginParam);
        if (fromPlugin != null) {
            return fromPlugin;
        }
        return mavenProperty(project, property);
    }

    /**
     * The effective extra compiler arguments configured on the maven-compiler-plugin, captured for merging into the
     * source set's javacOptions. Covers every shape the plugin accepts:
     * <ul>
     *   <li>the modern list form {@code <compilerArgs><arg>--add-exports</arg><arg>...=ALL-UNNAMED</arg></compilerArgs>},
     *       where each {@code <arg>} is one argv token forwarded verbatim to javac;</li>
     *   <li>the singular string form {@code <compilerArgument>-Xlint:all -Werror</compilerArgument>}, which Maven splits
     *       on whitespace;</li>
     *   <li>the deprecated map form {@code <compilerArguments><Akey>value</Akey></compilerArguments>}, where each child
     *       name is a key (a leading {@code -} is prepended when absent) followed by its value as a separate token -- the
     *       legacy shape Maven used for {@code -A} processor options;</li>
     *   <li>the typed boolean parameters {@code <parameters>} and {@code <enablePreview>}, which the plugin exposes as
     *       dedicated configuration rather than raw args, synthesized here into {@code -parameters} and
     *       {@code --enable-preview}.</li>
     * </ul>
     * Tokens still carrying an unresolved {@code ${...}} placeholder are skipped (the effective POM should already have
     * interpolated them; an unresolved one is not a usable compiler token). Returns args in declaration order, de-duped.
     */
    private static List<String> mavenCompilerArgs(Element project) {
        Element build = firstChild(project, "build");
        LinkedHashSet<String> args = new LinkedHashSet<>();
        if (build == null) {
            return new ArrayList<>(args);
        }
        for (Element plugins : childElements(build, "plugins")) {
            for (Element plugin : childElements(plugins, "plugin")) {
                if (!"maven-compiler-plugin".equals(textOfChild(plugin, "artifactId"))) {
                    continue;
                }
                Element configuration = firstChild(plugin, "configuration");
                if (configuration == null) {
                    continue;
                }
                Element compilerArgs = firstChild(configuration, "compilerArgs");
                if (compilerArgs != null) {
                    for (Element arg : childElements(compilerArgs, "arg")) {
                        addCompilerArg(args, arg.getTextContent());
                    }
                }
                String compilerArgument = textOfChild(configuration, "compilerArgument");
                if (compilerArgument != null && !compilerArgument.isBlank()) {
                    for (String token : compilerArgument.trim().split("\\s+")) {
                        addCompilerArg(args, token);
                    }
                }
                Element compilerArguments = firstChild(configuration, "compilerArguments");
                if (compilerArguments != null) {
                    for (Element entry : childElements(compilerArguments)) {
                        String key = entry.getNodeName();
                        if (key == null || key.isBlank()) {
                            continue;
                        }
                        addCompilerArg(args, key.startsWith("-") ? key : "-" + key);
                        String value = entry.getTextContent();
                        if (value != null && !value.isBlank()) {
                            addCompilerArg(args, value.trim());
                        }
                    }
                }
                if (mavenConfigFlag(configuration, "parameters")) {
                    addCompilerArg(args, "-parameters");
                }
                if (mavenConfigFlag(configuration, "enablePreview")) {
                    addCompilerArg(args, "--enable-preview");
                }
            }
        }
        return new ArrayList<>(args);
    }

    /** Adds a trimmed compiler-arg token, skipping blanks and tokens that still carry an unresolved {@code ${...}}. */
    private static void addCompilerArg(LinkedHashSet<String> args, String raw) {
        if (raw == null) {
            return;
        }
        String token = raw.trim();
        if (token.isEmpty() || token.contains("${")) {
            return;
        }
        args.add(token);
    }

    /** True when the named maven-compiler-plugin configuration element is present and set to {@code true}. */
    private static boolean mavenConfigFlag(Element configuration, String name) {
        String value = textOfChild(configuration, name);
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    /** All direct child elements of the given element, regardless of tag name (declaration order preserved). */
    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Resolves the maven-compiler-plugin's declared {@code <annotationProcessorPaths>} (GAV coordinates) to real jar
     * paths by matching each declared {@code <artifactId>} (and {@code <version>} when present) against the already
     * resolved compile classpath. {@code dependency:build-classpath} resolves the processor jars onto the test-scoped
     * classpath, so this needs no extra Maven invocation and works fully offline. Returns the matched jar paths in the
     * order the processors are declared; unmatched declarations are skipped (they surface later as javac diagnostics).
     */
    private static List<String> mavenAnnotationProcessorPath(Element project, List<String> classpath) {
        Element build = firstChild(project, "build");
        if (build == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Element plugins : childElements(build, "plugins")) {
            for (Element plugin : childElements(plugins, "plugin")) {
                if (!"maven-compiler-plugin".equals(textOfChild(plugin, "artifactId"))) {
                    continue;
                }
                Element configuration = firstChild(plugin, "configuration");
                if (configuration == null) {
                    continue;
                }
                Element processorPaths = firstChild(configuration, "annotationProcessorPaths");
                if (processorPaths == null) {
                    continue;
                }
                for (Element path : childElements(processorPaths, "path")) {
                    String artifactId = textOfChild(path, "artifactId");
                    String version = textOfChild(path, "version");
                    if (artifactId == null || artifactId.isBlank() || artifactId.contains("${")) {
                        continue;
                    }
                    String jar = matchClasspathJar(classpath, artifactId.trim(),
                            version != null && !version.isBlank() && !version.contains("${") ? version.trim() : null);
                    if (jar != null && !result.contains(jar)) {
                        result.add(jar);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Finds the resolved classpath jar for a Maven {@code artifactId}/{@code version}. The local-repo layout names jars
     * {@code <artifactId>-<version>.jar}, so a suffix match on the file name is reliable and avoids re-resolving the
     * coordinate. When a version is supplied the exact {@code artifactId-version.jar} is preferred.
     */
    private static String matchClasspathJar(List<String> classpath, String artifactId, String version) {
        String exact = version == null ? null : artifactId + "-" + version + ".jar";
        String prefix = artifactId + "-";
        String fallback = null;
        for (String entry : classpath) {
            String name = Path.of(entry).getFileName().toString();
            if (exact != null && name.equals(exact)) {
                return entry;
            }
            if (fallback == null && name.startsWith(prefix) && name.endsWith(".jar")) {
                // The remainder after the artifactId prefix should start with a digit (a version), not another word,
                // so "guava-21.0.jar" matches artifactId "guava" but "guava-testlib-..." does not.
                String remainder = name.substring(prefix.length());
                if (!remainder.isEmpty() && Character.isDigit(remainder.charAt(0))) {
                    fallback = entry;
                }
            }
        }
        return fallback;
    }

    private static String mavenCompilerPluginConfig(Element project, String param) {
        Element build = firstChild(project, "build");
        if (build == null) {
            return null;
        }
        for (Element plugins : childElements(build, "plugins")) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = textOfChild(plugin, "artifactId");
                if (!"maven-compiler-plugin".equals(artifactId)) {
                    continue;
                }
                Element configuration = firstChild(plugin, "configuration");
                if (configuration != null) {
                    String value = textOfChild(configuration, param);
                    if (value != null && !value.isBlank() && !value.contains("${")) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    private static String mavenProperty(Element project, String name) {
        Element properties = firstChild(project, "properties");
        if (properties == null) {
            return null;
        }
        String value = textOfChild(properties, name);
        if (value != null && !value.isBlank() && !value.contains("${")) {
            return value.trim();
        }
        return null;
    }

    // Package-private so the XML-hardening unit test can feed it a crafted DOCTYPE/XXE payload directly.
    static Document parseXml(Path file) throws IOException {
        try {
            DocumentBuilder builder = newSecureDocumentBuilderFactory().newDocumentBuilder();
            try (InputStream stream = Files.newInputStream(file)) {
                return builder.parse(stream);
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse Maven effective POM: " + e.getMessage(), e);
        }
    }

    /**
     * A {@link DocumentBuilderFactory} hardened against XML External Entity (XXE) attacks before it ever touches a
     * project-controlled {@code pom.xml}: secure processing is enabled, DOCTYPE declarations are disallowed, external
     * general and parameter entities and nonvalidating external-DTD loading are disabled, XInclude and entity-reference
     * expansion are off, and external DTD/schema access is blocked. Because DOCTYPE declarations are disallowed, any
     * document carrying a {@code <!DOCTYPE ...>} — the vector for billion-laughs entity expansion, local-file
     * disclosure, and SSRF — is rejected with a fatal parse error rather than processed (OWASP XXE prevention).
     */
    static DocumentBuilderFactory newSecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    /** Sets a JAXP attribute, tolerating parsers that do not recognize it (the security features above still apply). */
    private static void setAttributeQuietly(DocumentBuilderFactory factory, String name, Object value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some JAXP implementations don't expose ACCESS_EXTERNAL_* as DocumentBuilderFactory attributes; the
            // disallow-doctype-decl and external-entity features above already block the XXE vectors on those parsers.
        }
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> children = childElements(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String textOfChild(Element parent, String name) {
        Element child = firstChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    /** Singleton list with the normalized root if it is an existing directory, else empty (primary source root). */
    private static List<String> prependIfDir(Path root) {
        return Files.isDirectory(root) ? List.of(root.toAbsolutePath().normalize().toString()) : List.of();
    }

    /** Order-preserving union of the given path-string lists, dropping duplicates. */
    @SafeVarargs
    private static List<String> dedup(List<String>... lists) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> list : lists) {
            merged.addAll(list);
        }
        return new ArrayList<>(merged);
    }

    /**
     * Resolves additional source roots contributed by the build-helper-maven-plugin's {@code add-source} (main) and
     * {@code add-test-source} (test) goals. Each matching execution's {@code <configuration><sources><source>} entries
     * are returned, resolved against the module directory. Roots are included whether or not they currently exist on disk
     * (they may be generated during a build), so the model reflects the project's declared layout.
     */
    private static List<String> mavenBuildHelperSources(Element project, Path moduleDir, String goal) {
        List<String> roots = new ArrayList<>();
        Element build = firstChild(project, "build");
        if (build == null) {
            return roots;
        }
        Element plugins = firstChild(build, "plugins");
        if (plugins == null) {
            return roots;
        }
        for (Element plugin : childElements(plugins, "plugin")) {
            if (!"build-helper-maven-plugin".equals(textOfChild(plugin, "artifactId"))) {
                continue;
            }
            Element executions = firstChild(plugin, "executions");
            if (executions == null) {
                continue;
            }
            for (Element execution : childElements(executions, "execution")) {
                Element goals = firstChild(execution, "goals");
                if (goals == null || !goalMatches(goals, goal)) {
                    continue;
                }
                Element configuration = firstChild(execution, "configuration");
                Element sources = configuration == null ? null : firstChild(configuration, "sources");
                if (sources == null) {
                    continue;
                }
                for (Element source : childElements(sources, "source")) {
                    String text = source.getTextContent();
                    if (text != null && !text.isBlank()) {
                        Path path = Path.of(text.trim());
                        roots.add((path.isAbsolute() ? path : moduleDir.resolve(text.trim())).normalize().toString());
                    }
                }
            }
        }
        return roots;
    }

    private static boolean goalMatches(Element goalsElement, String goal) {
        for (Element goalElement : childElements(goalsElement, "goal")) {
            String text = goalElement.getTextContent();
            if (text != null && goal.equals(text.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Complete generated-source-root discovery for a scope, combining three complementary mechanisms so that generated
     * roots are classified whether or not a build has run and whether or not they live under the conventional parent:
     * <ol>
     *   <li><b>Annotation-processor output</b> (always, even before a build creates it): the maven-compiler-plugin's
     *       {@code <generatedSourcesDirectory>} when configured, else the conventional
     *       {@code target/generated-sources/annotations} (or {@code .../generated-test-sources/test-annotations}).</li>
     *   <li><b>Code-generation plugin outputs</b> (main scope only): the configured (or default) output directory of each
     *       known generator -- protobuf, gRPC, OpenAPI/Swagger, JAXB/XJC, ANTLR, jOOQ, Avro, QueryDSL, etc. These are
     *       authoritative from the POM and may live OUTSIDE {@code target/generated-sources}.</li>
     *   <li><b>Filesystem classification</b>: every directory under the conventional generated parent that actually holds
     *       generated {@code .java} sources, with the true source root inferred from each file's {@code package}
     *       declaration. This catches nested plugin layouts (e.g. {@code protobuf/java}, {@code openapi/src/main/java})
     *       and unknown/custom processors without hardcoding their conventions.</li>
     * </ol>
     * Everything under {@code target/} is build output (never hand-authored source), so the filesystem scan is bounded to
     * the generated parent and is safe to classify wholesale as generated -- which is what makes both reference visibility
     * (roots feed {@code -sourcepath}) and edit refusal (edits into generated roots are rejected) correct and complete.
     */
    private static List<String> mavenGeneratedRoots(Element project, Path moduleDir, String conventionalParent,
            String annotationSubdir, boolean testScope) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        // 1. Annotation-processor output (honor a relocated <generatedSourcesDirectory>; main scope only -- the test
        //    annotation output has its own conventional dir and the compiler param does not distinguish scopes here).
        String configuredAnnotationDir = testScope ? null : mavenCompilerPluginConfig(project, "generatedSourcesDirectory");
        if (configuredAnnotationDir != null && !configuredAnnotationDir.isBlank()) {
            roots.add(resolveModulePath(moduleDir, configuredAnnotationDir));
        } else {
            roots.add(moduleDir.resolve(conventionalParent).resolve(annotationSubdir).toAbsolutePath().normalize().toString());
        }
        // 2. Known code-generation plugin output directories (main scope: these plugins bind to generate-sources).
        if (!testScope) {
            roots.addAll(mavenCodegenPluginRoots(project, moduleDir));
        }
        // 3. Filesystem classification under the conventional generated parent (package-inferred source roots).
        roots.addAll(generatedSourceRootsUnder(moduleDir.resolve(conventionalParent)));
        return new ArrayList<>(roots);
    }

    /**
     * Output directories configured on known code-generation Maven plugins, read straight from the effective POM so they
     * are known even before {@code generate-sources} runs and even when they live outside {@code target/generated-sources}.
     * Each entry maps a generator's {@code artifactId} to the configuration element holding its output directory and the
     * default that generator uses when the element is absent; jOOQ's nested {@code <generator><target><directory>} is
     * handled specially. Returns normalized absolute directories for those plugins actually declared in the build.
     */
    private static List<String> mavenCodegenPluginRoots(Element project, Path moduleDir) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        Element build = firstChild(project, "build");
        if (build == null) {
            return new ArrayList<>(roots);
        }
        for (Element plugins : childElements(build, "plugins")) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = textOfChild(plugin, "artifactId");
                if (artifactId == null) {
                    continue;
                }
                CodegenPlugin spec = CODEGEN_PLUGINS.get(artifactId);
                if (spec == null) {
                    continue;
                }
                for (Element configuration : codegenConfigurations(plugin)) {
                    String dir = spec.outputDir(configuration);
                    if (dir != null && !dir.isBlank()) {
                        roots.add(resolveModulePath(moduleDir, dir));
                    } else if (spec.defaultDir() != null) {
                        roots.add(resolveModulePath(moduleDir, spec.defaultDir()));
                    }
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /** A plugin-level configuration plus each execution-level configuration, so per-execution output dirs are captured. */
    private static List<Element> codegenConfigurations(Element plugin) {
        List<Element> configs = new ArrayList<>();
        Element pluginConfig = firstChild(plugin, "configuration");
        if (pluginConfig != null) {
            configs.add(pluginConfig);
        }
        Element executions = firstChild(plugin, "executions");
        if (executions != null) {
            for (Element execution : childElements(executions, "execution")) {
                Element executionConfig = firstChild(execution, "configuration");
                if (executionConfig != null) {
                    configs.add(executionConfig);
                }
            }
        }
        if (configs.isEmpty()) {
            // No explicit configuration: still emit one (null) slot so the generator's default directory is contributed.
            configs.add(null);
        }
        return configs;
    }

    /** Describes how to read a code-generation plugin's output directory from its {@code <configuration>}. */
    private record CodegenPlugin(String configElement, String defaultDir, String suffix, boolean nestedJooq) {
        String outputDir(Element configuration) {
            if (configuration == null) {
                return null;
            }
            if (nestedJooq) {
                Element generator = firstChild(configuration, "generator");
                Element target = generator == null ? null : firstChild(generator, "target");
                String directory = target == null ? null : textOfChild(target, "directory");
                return directory;
            }
            String value = textOfChild(configuration, configElement);
            if (value == null || value.isBlank()) {
                return null;
            }
            return suffix == null ? value : value + suffix;
        }
    }

    // Known code-generation plugins and where each writes its generated sources. The default directory is used when the
    // plugin is declared without an explicit output element, so a conventional layout is classified even before a build.
    private static final Map<String, CodegenPlugin> CODEGEN_PLUGINS = Map.ofEntries(
            Map.entry("protobuf-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/protobuf/java", null, false)),
            Map.entry("protoc-jar-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources", null, false)),
            Map.entry("openapi-generator-maven-plugin", new CodegenPlugin("output", "target/generated-sources/openapi/src/main/java", "/src/main/java", false)),
            Map.entry("swagger-codegen-maven-plugin", new CodegenPlugin("output", "target/generated-sources/swagger/src/main/java", "/src/main/java", false)),
            Map.entry("jaxb2-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/jaxb", null, false)),
            Map.entry("maven-jaxb2-plugin", new CodegenPlugin("generateDirectory", "target/generated-sources/xjc", null, false)),
            Map.entry("cxf-xjc-plugin", new CodegenPlugin("sourceRoot", "target/generated-sources/cxf", null, false)),
            Map.entry("antlr4-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/antlr4", null, false)),
            Map.entry("antlr3-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/antlr3", null, false)),
            Map.entry("avro-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/avro", null, false)),
            Map.entry("apt-maven-plugin", new CodegenPlugin("outputDirectory", "target/generated-sources/java", null, false)),
            Map.entry("jooq-codegen-maven-plugin", new CodegenPlugin(null, "target/generated-sources/jooq", null, true)));

    /**
     * Every directory under {@code parent} that directly holds generated {@code .java} sources, reduced to the true source
     * root by stripping each file's {@code package} segments from its parent directory. A file declaring
     * {@code package a.b;} at {@code .../protobuf/java/a/b/Foo.java} yields root {@code .../protobuf/java}. Scanning is
     * bounded to {@code parent} (always under {@code target/}, i.e. build output) and capped to avoid pathological trees.
     */
    private static List<String> generatedSourceRootsUnder(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        try (var stream = Files.walk(parent)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .limit(GENERATED_SCAN_FILE_CAP)
                    .forEach(javaFile -> {
                        Path root = inferSourceRoot(javaFile);
                        if (root != null) {
                            roots.add(root.toAbsolutePath().normalize().toString());
                        }
                    });
        } catch (IOException ignored) {
            // An unreadable generated tree contributes no filesystem-classified roots; the annotation-processor and
            // plugin-config roots still apply.
        }
        return new ArrayList<>(roots);
    }

    // Upper bound on generated .java files scanned per parent. Generated trees are normally far smaller; the cap only
    // guards against a pathologically large tree making extraction slow, and distinct roots saturate long before it.
    private static final long GENERATED_SCAN_FILE_CAP = 5000L;

    /** Source root of a .java file: its parent directory with as many segments stripped as its package has components. */
    private static Path inferSourceRoot(Path javaFile) {
        Path dir = javaFile.getParent();
        if (dir == null) {
            return null;
        }
        String packageName = readPackageName(javaFile);
        if (packageName == null || packageName.isBlank()) {
            return dir;
        }
        int segments = packageName.split("\\.").length;
        Path root = dir;
        for (int i = 0; i < segments && root != null; i++) {
            root = root.getParent();
        }
        return root;
    }

    private static final java.util.regex.Pattern PACKAGE_DECLARATION =
            java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** The declared package of a .java file, or null if it is in the default package or cannot be read. */
    private static String readPackageName(Path javaFile) {
        try {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = PACKAGE_DECLARATION.matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException | RuntimeException ignored) {
            // A file that cannot be read (encoding, permissions) yields no package; its directory is used as the root.
            return null;
        }
    }

    /** Resolves a possibly-relative configured path against the module directory, normalized to an absolute string. */
    private static String resolveModulePath(Path moduleDir, String configured) {
        Path path = Path.of(configured.trim());
        return (path.isAbsolute() ? path : moduleDir.resolve(path)).toAbsolutePath().normalize().toString();
    }

    // --- shared helpers -------------------------------------------------------------------------------------------

    /**
     * Chooses the Gradle launcher honoring the JDTLS {@code gradle_wrapper_enabled} preference (project-model plan
     * section 3 step 5). When the wrapper is enabled (or no preference is supplied) the project's {@code ./gradlew} is
     * preferred, matching the default {@link #resolveLauncher} order. When the wrapper is explicitly disabled, the
     * {@code gradle} on PATH is preferred (mirroring Buildship's own-distribution default), falling back to the wrapper
     * only when no PATH Gradle exists.
     */
    private static Path resolveGradleLauncher(Path projectRoot, JdtlsSettings jdtls) {
        if (Boolean.FALSE.equals(jdtls.gradleWrapperEnabled())) {
            if (isOnPath("gradle")) {
                return Path.of("gradle");
            }
            return resolveLauncher(projectRoot, "gradlew", "gradlew.bat", "gradle");
        }
        return resolveLauncher(projectRoot, "gradlew", "gradlew.bat", "gradle");
    }

    private static Path resolveLauncher(Path projectRoot, String wrapperUnix, String wrapperWindows, String onPath) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectRoot.resolve(windows ? wrapperWindows : wrapperUnix);
        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().normalize();
        }
        if (isOnPath(onPath)) {
            return Path.of(onPath);
        }
        return null;
    }

    private static boolean isOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate) || (windows && Files.isRegularFile(Path.of(dir, executable + ".cmd")))
                    || (windows && Files.isRegularFile(Path.of(dir, executable + ".bat")))) {
                return true;
            }
        }
        return false;
    }

    private Path writeInitScript() throws IOException {
        try (InputStream stream = BuildModelExtractor.class.getResourceAsStream(INIT_SCRIPT_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Bundled Gradle init script resource is missing: " + INIT_SCRIPT_RESOURCE);
            }
            Path temp = Files.createTempFile("serena-model", ".init.gradle");
            Files.write(temp, stream.readAllBytes());
            return temp;
        }
    }

    private static ProcessResult run(List<String> command, Path workingDir) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        // Drain stdout on a SEPARATE thread so the timeout governs the whole interaction. Reading to EOF on the calling
        // thread before waitFor would block forever on a hung build tool, making the timeout dead code. The reader still
        // prevents the child from deadlocking on a full output pipe.
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                captured.set(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The stream is closed when the process is destroyed on timeout; partial output is acceptable here.
            }
        }, "serena-build-extractor-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            // Bounded join so a stuck reader cannot hang the sidecar thread; the process is already being killed.
            reader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
            throw new IOException("Build tool invocation timed out after " + TIMEOUT_SECONDS + "s: " + command.get(0));
        }
        // The process has exited; the reader will hit EOF promptly. Bounded join to collect its captured output.
        reader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        return new ProcessResult(process.exitValue(), captured.get());
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String trimmed = output.strip();
        int max = 600;
        return trimmed.length() <= max ? trimmed : "..." + trimmed.substring(trimmed.length() - max);
    }

    private static String fallbackHint() {
        return "Set java_refactor.build_tool_mode: explicit with source_roots/classpath, or "
                + "java_refactor.allow_conventional_fallback: true.";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    /** Best-effort recursive deletion of a temp directory (the per-project Gradle model output dir) and its contents. */
    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(BuildModelExtractor::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private static String stringValue(Object value, String defaultValue) {
        String result = nullableString(value);
        return result == null ? defaultValue : result;
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object element : list) {
            String text = nullableString(element);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private record ProcessResult(int exitCode, String output) {
    }

    /**
     * Optional build-tool settings reused from Serena's Java language server (its {@code ls_specific_settings.java}
     * entry) when {@code java_refactor.use_jdtls_settings} is enabled. Any field may be {@code null} (absent). See the
     * project-model plan section 3 step 5 and section 13.
     */
    record JdtlsSettings(String mavenUserSettings, String gradleUserHome, String gradleJavaHome, Boolean gradleWrapperEnabled) {
        static final JdtlsSettings NONE = new JdtlsSettings(null, null, null, null);
    }
}
