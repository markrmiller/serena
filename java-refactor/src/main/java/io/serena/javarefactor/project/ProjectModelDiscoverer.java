package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ProjectModelDiscoverer {
    // Fallback exclusion set used when the initialize contract supplies no ignoredPatterns (and for build-file/
    // invalidation walks that are not source attribution). Source discovery uses the per-project configured set.
    static final Set<String> DEFAULT_EXCLUDED_DIR_NAMES = Set.of(".git", ".gradle", ".serena", "build", "target", "out");

    /** Resolves a build model for a project root, possibly via a coarse build-file-hash cache supplied by {@link Main}. */
    @FunctionalInterface
    interface ExtractionProvider {
        BuildModelExtractor.Result extract(BuildKind buildKind, Path projectRoot, DiscoveryConfig config);
    }

    private final ExtractionProvider extractionProvider;

    public ProjectModelDiscoverer() {
        this(defaultExtractionProvider());
    }

    public ProjectModelDiscoverer(ExtractionProvider extractionProvider) {
        this.extractionProvider = extractionProvider;
    }

    private static ExtractionProvider defaultExtractionProvider() {
        BuildModelExtractor extractor = new BuildModelExtractor();
        return (buildKind, projectRoot, config) -> buildKind == BuildKind.GRADLE
                ? extractor.extractGradle(projectRoot, config.offline(), config.jdtlsSettings())
                : extractor.extractMaven(projectRoot, config.offline(), null, config.jdtlsSettings(), config.mavenProfiles());
    }

    JavaProjectModel discover(Path rawProjectRoot, String configuration) {
        JavaProjectModel model = buildUnvalidatedModel(rawProjectRoot, configuration);
        if (!model.errors().isEmpty()) {
            return model;
        }
        return new JavacSession().validate(model);
    }

    /**
     * Performs the cheap discovery phase (build-kind detection and source-file collection) without running the
     * expensive javac validation pass. Callers may cache the validated result keyed by source/invalidation-file state.
     */
    public JavaProjectModel buildUnvalidatedModel(Path rawProjectRoot, String configuration) {
        Path projectRoot = rawProjectRoot.toAbsolutePath().normalize();
        DiscoveryConfig discoveryConfig = DiscoveryConfig.from(configuration);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (discoveryConfig.buildToolModeConflict() != null) {
            errors.add(discoveryConfig.buildToolModeConflict());
            return new JavaProjectModel(projectRoot, "invalid", List.of(), errors, warnings, List.of(), discoveryConfig.allowIncompleteAnalysis(), false, List.of());
        }

        if (!Files.isDirectory(projectRoot)) {
            errors.add("Project root is not a directory: " + projectRoot);
            return new JavaProjectModel(projectRoot, "invalid", List.of(), errors, warnings, List.of(), discoveryConfig.allowIncompleteAnalysis(), false, List.of());
        }

        BuildKind buildKind = detectBuildKind(projectRoot, discoveryConfig);
        List<Path> invalidationFiles = discoverInvalidationFiles(projectRoot);
        // Set when real build-tool extraction failed and discovery degraded to a conventional layout; the apply path
        // refuses such a degraded model so edits are never committed against an unverified classpath (see H3).
        boolean[] fallbackUsed = {false};
        List<SourceSet> sourceSets = switch (buildKind) {
            case EXPLICIT -> discoverExplicit(projectRoot, discoveryConfig, invalidationFiles, warnings);
            case GRADLE -> discoverGradle(projectRoot, discoveryConfig, invalidationFiles, errors, warnings, fallbackUsed);
            case MAVEN -> discoverMaven(projectRoot, discoveryConfig, invalidationFiles, errors, warnings, fallbackUsed);
            case PLAIN -> discoverPlain(projectRoot, discoveryConfig, invalidationFiles);
        };
        if (!errors.isEmpty()) {
            return new JavaProjectModel(projectRoot, buildKind.name().toLowerCase(), sourceSets, errors, warnings, invalidationFiles, discoveryConfig.allowIncompleteAnalysis(), fallbackUsed[0], List.of());
        }

        if (sourceSets.isEmpty()) {
            errors.add("No Java source files were discovered.");
        }
        long javaFileCount = sourceSets.stream().mapToLong(sourceSet -> sourceSet.javaFiles().size()).sum();
        if (javaFileCount > discoveryConfig.maxFiles()) {
            errors.add("Java source file count " + javaFileCount + " exceeds configured java_refactor.max_files=" + discoveryConfig.maxFiles() + ".");
        }

        return new JavaProjectModel(projectRoot, buildKind.name().toLowerCase(), sourceSets, errors, warnings, invalidationFiles, discoveryConfig.allowIncompleteAnalysis(), fallbackUsed[0], List.of());
    }

    private static BuildKind detectBuildKind(Path projectRoot, DiscoveryConfig config) {
        if (config.explicitModel() != null) {
            return BuildKind.EXPLICIT;
        }
        BuildKind forced = forcedBuildKind(config.buildToolMode());
        if (forced != null) {
            return forced;
        }
        if (!config.explicitSourceRoots().isEmpty()) {
            return BuildKind.EXPLICIT;
        }
        if (existsAny(projectRoot, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradlew", "gradlew.bat")) {
            return BuildKind.GRADLE;
        }
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildKind.MAVEN;
        }
        return BuildKind.PLAIN;
    }

    private static BuildKind forcedBuildKind(String buildToolMode) {
        if (buildToolMode == null || buildToolMode.isBlank()) {
            return null;
        }
        return switch (buildToolMode.trim().toLowerCase()) {
            case "explicit" -> BuildKind.EXPLICIT;
            case "maven" -> BuildKind.MAVEN;
            case "gradle" -> BuildKind.GRADLE;
            case "plain" -> BuildKind.PLAIN;
            default -> null;
        };
    }

    private static boolean existsAny(Path projectRoot, String... names) {
        for (String name : names) {
            if (Files.exists(projectRoot.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static List<SourceSet> discoverExplicit(Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles, List<String> warnings) {
        if (config.explicitModel() != null) {
            return sourceSetsFromBuildModel(config.explicitModel(), projectRoot, config, invalidationFiles);
        }
        List<Path> roots = new ArrayList<>();
        for (String sourceRoot : config.explicitSourceRoots()) {
            Path root = projectRoot.resolve(sourceRoot).normalize();
            if (Files.isDirectory(root)) {
                roots.add(root);
            } else {
                warnings.add("Configured Java source root does not exist: " + sourceRoot);
            }
        }
        return List.of(createSourceSet("main", projectRoot, roots, config, invalidationFiles));
    }

    private List<SourceSet> discoverGradle(Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles, List<String> errors, List<String> warnings, boolean[] fallbackUsed) {
        return discoverFromBuildTool(BuildKind.GRADLE, projectRoot, config, invalidationFiles, errors, warnings, fallbackUsed);
    }

    private List<SourceSet> discoverMaven(Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles, List<String> errors, List<String> warnings, boolean[] fallbackUsed) {
        return discoverFromBuildTool(BuildKind.MAVEN, projectRoot, config, invalidationFiles, errors, warnings, fallbackUsed);
    }

    /**
     * Runs real Maven/Gradle build-model extraction and maps the result into source sets. On extraction failure the
     * behavior is fail-closed (append an error) unless {@code allowConventionalFallback} is set, in which case it
     * degrades to conventional discovery with a warning.
     */
    private List<SourceSet> discoverFromBuildTool(BuildKind buildKind, Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles, List<String> errors, List<String> warnings, boolean[] fallbackUsed) {
        BuildModelExtractor.Result result = extractionProvider.extract(buildKind, projectRoot, config);
        if (result.isFailure()) {
            if (config.allowConventionalFallback()) {
                warnings.add("Build-model extraction failed; falling back to conventional source-layout discovery with "
                        + "reduced fidelity (no resolved classpaths or custom source sets). " + result.error());
                fallbackUsed[0] = true;
                return discoverConventional(projectRoot, config, invalidationFiles);
            }
            errors.add(result.error());
            return List.of();
        }
        if (result.warning() != null) {
            warnings.add(result.warning());
        }

        List<SourceSet> sourceSets = sourceSetsFromBuildModel(result.model(), projectRoot, config, invalidationFiles);
        if (sourceSets.isEmpty()) {
            // The build tool resolved but reported NO Java source sets (e.g. an aggregator pom, a project without the
            // java plugin, or extraction that missed the source sets). Fail CLOSED by default — exactly like an
            // extraction failure — because degrading to a classpath-poor conventional scan would let a Maven/Gradle
            // project with a missed source set still produce previews against an unverified model (H3). Only degrade
            // when conventional fallback is explicitly allowed, in which case it is flagged (fallbackUsed) so the apply
            // path refuses on the degraded model.
            if (config.allowConventionalFallback()) {
                warnings.add("Build-model extraction returned no Java source sets; falling back to conventional "
                        + "source-layout discovery with reduced fidelity (no resolved classpaths or custom source sets).");
                fallbackUsed[0] = true;
                return discoverConventional(projectRoot, config, invalidationFiles);
            }
            errors.add("Build-model extraction (" + buildKind.name().toLowerCase() + ") returned no Java source sets, so "
                    + "the project model is incomplete and refactoring would be unsafe. Fix the build configuration (or "
                    + "set java_refactor.allow_conventional_fallback to analyze with reduced fidelity, which blocks apply).");
            return List.of();
        }
        return sourceSets;
    }

    private static List<SourceSet> sourceSetsFromBuildModel(BuildModel model, Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles) {
        List<SourceSet> sourceSets = new ArrayList<>();
        boolean multiModule = model.modules().size() > 1;
        for (BuildModel.Module module : model.modules()) {
            for (BuildModel.ModelSourceSet modelSourceSet : module.sourceSets()) {
                String name = multiModule ? module.project() + ":" + modelSourceSet.name() : modelSourceSet.name();
                List<Path> roots = new ArrayList<>();
                roots.addAll(resolveAgainst(projectRoot, toPaths(modelSourceSet.srcDirs())));
                List<Path> generatedRoots = resolveAgainst(projectRoot, toPaths(modelSourceSet.generatedRoots()));
                // B11: the model-declared resource roots, resolved to absolute paths exactly like the source/generated
                // roots, threaded into the SourceSet so ResourceRootModel discovers them model-first.
                List<Path> resourceRoots = resolveAgainst(projectRoot, toPaths(modelSourceSet.resourceDirs()));
                List<String> crossModuleDependsOn = new ArrayList<>();
                if (multiModule) {
                    for (String dependency : modelSourceSet.dependsOnProjects()) {
                        crossModuleDependsOn.add(dependency + ":main");
                    }
                }
                SourceSet sourceSet = createSourceSet(
                        name,
                        projectRoot,
                        roots,
                        resolveAgainst(projectRoot, toPaths(modelSourceSet.outputDirs())),
                        generatedRoots,
                        resolveAgainst(projectRoot, toPaths(modelSourceSet.classpath())),
                        resolveAgainst(projectRoot, toPaths(modelSourceSet.modulePath())),
                        resolveAgainst(projectRoot, toPaths(modelSourceSet.annotationProcessorPath())),
                        new JavaVersions(modelSourceSet.release(), normalizeJavaVersion(modelSourceSet.source()), normalizeJavaVersion(modelSourceSet.target())),
                        modelSourceSet.encoding(),
                        config,
                        invalidationFiles,
                        crossModuleDependsOn,
                        modelSourceSet.compilerArgs(),
                        modelSourceSet.classpathProven(),
                        resourceRoots);
                if (!sourceSet.javaFiles().isEmpty()) {
                    sourceSets.add(sourceSet);
                }
            }
        }
        return sourceSets;
    }

    private static List<SourceSet> discoverConventional(Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles) {
        return List.of(createSourceSet("main", projectRoot, discoverJavaRoots(projectRoot, config), config, invalidationFiles));
    }

    private static List<Path> toPaths(List<String> values) {
        return values.stream().map(Path::of).toList();
    }

    private static List<SourceSet> discoverPlain(Path projectRoot, DiscoveryConfig config, List<Path> invalidationFiles) {
        return List.of(createSourceSet("main", projectRoot, discoverJavaRoots(projectRoot, config), config, invalidationFiles));
    }

    /** Conventional/explicit/plain source set: roots, classpath, and versions all come from {@link DiscoveryConfig}. */
    private static SourceSet createSourceSet(String name, Path projectRoot, List<Path> roots, DiscoveryConfig config, List<Path> invalidationFiles) {
        // Only generated roots that exist on disk are kept (mirroring outputDirs below); a plain project that has never
        // been built reports none, rather than two phantom dirs that would spuriously trip the annotation-processing
        // safety caveat (generatedSourceRoots non-empty + annotation processing disabled).
        List<Path> generatedRoots = Stream.of(projectRoot.resolve("build/generated/sources/annotationProcessor/java/main"), projectRoot.resolve("target/generated-sources/annotations"))
                .filter(Files::isDirectory)
                .toList();
        // Conventional compiler output directories for the two supported build layouts; only those that exist on disk
        // are kept, so a plain project that has never been built simply reports none (rather than two phantom dirs).
        List<Path> outputDirs = Stream.of(projectRoot.resolve("build/classes/java/main"), projectRoot.resolve("target/classes"))
                .filter(Files::isDirectory)
                .toList();
        return createSourceSet(
                name,
                projectRoot,
                roots,
                outputDirs,
                generatedRoots,
                config.classpath(),
                config.modulePath(),
                List.of(),
                new JavaVersions(config.releaseVersion(), config.sourceVersion(), config.targetVersion()),
                config.encoding(),
                config,
                invalidationFiles,
                List.of(),
                List.of(),
                // Conventional/explicit/plain source sets use the config-supplied classpath directly (no build-classpath
                // resolution step), so there is no "unproven" condition here. (Conventional FALLBACK after a failed
                // extraction is flagged separately via conventionalFallbackUsed, which refuses apply.)
                true,
                // B11: these source sets carry no model-declared resource roots (no build-model extraction); the resolver
                // then derives resource roots from the filename convention as the guarded fallback.
                List.of());
    }

    /**
     * Build-tool-extracted source set: roots, generated roots, classpath, module path, and versions are supplied
     * explicitly from the extracted {@link BuildModel}. User-configured encoding/annotation-processing settings and
     * explicit version overrides still take precedence over the extracted values.
     */
    private static SourceSet createSourceSet(
            String name,
            Path projectRoot,
            List<Path> roots,
            List<Path> outputDirs,
            List<Path> generatedRoots,
            List<Path> rawClasspath,
            List<Path> rawModulePath,
            List<Path> rawAnnotationProcessorPath,
            JavaVersions extractedVersions,
            String extractedEncoding,
            DiscoveryConfig config,
            List<Path> invalidationFiles,
            List<String> crossModuleDependsOn,
            List<String> extractedCompilerArgs,
            boolean classpathProven,
            List<Path> resourceRoots) {
        List<Path> existingRoots = roots.stream().map(Path::toAbsolutePath).map(Path::normalize).filter(Files::isDirectory).distinct().sorted().toList();
        // B11: normalize/dedup/sort the model-declared resource roots (existence is re-checked by ResourceRootModel,
        // which only returns directories that exist), mirroring how source/generated roots are normalized.
        List<Path> normalizedResourceRoots = resourceRoots.stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().sorted().toList();
        List<Path> normalizedGeneratedRoots = generatedRoots.stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().sorted().toList();
        List<Path> analysisRoots = config.generatedSourcesRead()
                ? Stream.concat(existingRoots.stream(), normalizedGeneratedRoots.stream().filter(Files::isDirectory))
                        .distinct()
                        .sorted()
                        .toList()
                : existingRoots;
        List<Path> javaFiles = collectJavaFiles(projectRoot, analysisRoots, config.maxFiles(), config.ignoredMatcher());
        List<ModuleSource> moduleSources = discoverModuleSources(javaFiles);
        boolean modular = !moduleSources.isEmpty();
        List<Path> lombokJars = resolveAgainst(projectRoot, config.lombokJars());
        List<Path> classpath = Stream.concat(resolveAgainst(projectRoot, rawClasspath).stream(), lombokJars.stream()).distinct().sorted().toList();
        List<Path> modulePath = resolveAgainst(projectRoot, rawModulePath);
        List<Path> annotationProcessorPath = Stream.concat(resolveAgainst(projectRoot, rawAnnotationProcessorPath).stream(), lombokJars.stream()).distinct().sorted().toList();
        // Explicit Serena config overrides win over extracted values; otherwise use what the build tool reported.
        JavaVersions versions = new JavaVersions(
                config.releaseVersion() != null ? config.releaseVersion() : extractedVersions.releaseVersion(),
                config.sourceVersion() != null ? config.sourceVersion() : extractedVersions.sourceVersion(),
                config.targetVersion() != null ? config.targetVersion() : extractedVersions.targetVersion());
        String encoding = config.encoding() != null ? config.encoding() : extractedEncoding;
        // javac's --module-source-path (needed only to disambiguate a multi-module source set) requires a class-output
        // directory. Analysis is the goal, not artifacts, so point -d at a throwaway temp dir; javac creates it on demand.
        Path moduleOutputDir = moduleSources.size() > 1 ? moduleAnalysisOutputDir(projectRoot, name) : null;
        List<String> javacOptions = createJavacOptions(versions, encoding, config.annotationProcessing(), annotationProcessorPath, classpath, modulePath, moduleSources, moduleOutputDir, extractedCompilerArgs);
        return new SourceSet(
                name,
                existingRoots,
                javaFiles,
                outputDirs.stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().sorted().toList(),
                classpath,
                modulePath,
                normalizedGeneratedRoots,
                versions.releaseVersion(),
                versions.sourceVersion(),
                versions.targetVersion(),
                encoding,
                modular,
                config.annotationProcessing(),
                annotationProcessorPath,
                config.allowIncompleteAnalysis(),
                javacOptions,
                invalidationFiles,
                combineDependsOn(name, crossModuleDependsOn),
                classpathProven,
                normalizedResourceRoots
        );
    }

    /**
     * Combines the convention-based intra-module dependency ({@link #defaultDependsOn}) with any cross-module edges
     * (the {@code <module>:main} names this source set depends on across the reactor/multi-project build), de-duplicated
     * and order-preserving.
     */
    private static List<String> combineDependsOn(String name, List<String> crossModuleDependsOn) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(defaultDependsOn(name));
        combined.addAll(crossModuleDependsOn);
        return new ArrayList<>(combined);
    }

    /**
     * Convention-based source-set dependency direction: every non-{@code main} source set (test, integrationTest, ...)
     * depends on its OWN module's {@code main}; {@code main} depends on nothing so it is never compiled with visibility
     * into test. In multi-module builds the source-set name is qualified ({@code <modulePath>:<sourceSet>}, e.g.
     * {@code :app:test} or {@code module-a:test}), so the dependency must be qualified with the same module prefix
     * ({@code :app:main} / {@code module-a:main}) — an unqualified {@code "main"} would not exist and
     * {@link SourceSet#crossSourceRoots} would resolve nothing. When no matching {@code main} set exists the dependency
     * simply resolves to nothing.
     */
    private static List<String> defaultDependsOn(String name) {
        int lastColon = name.lastIndexOf(':');
        String simpleName = lastColon < 0 ? name : name.substring(lastColon + 1);
        if ("main".equals(simpleName)) {
            return List.of();
        }
        String modulePrefix = lastColon < 0 ? "" : name.substring(0, lastColon + 1);
        return List.of(modulePrefix + "main");
    }

    /** A declared module: its name and the source directory rooted at the module (the parent of its module-info.java). */
    private record ModuleSource(String name, Path root) {
    }

    /**
     * A throwaway class-output directory for multi-module analysis (javac requires {@code -d} alongside
     * {@code --module-source-path}). Created under the system temp dir; never written to the project tree. If creation
     * fails, javac will create the declared directory itself on first use, so we fall back to a deterministic temp path.
     */
    private static Path moduleAnalysisOutputDir(Path projectRoot, String sourceSetName) {
        // A STABLE per-(project, source set) path so repeated discoveries reuse one directory instead of leaking a fresh
        // temp dir each time. javac creates it on demand; the hash keeps distinct projects/source sets from colliding.
        String key = Integer.toHexString((projectRoot.toAbsolutePath().normalize() + "" + sourceSetName).hashCode());
        return Path.of(System.getProperty("java.io.tmpdir"), "serena-jr-modules-" + key);
    }

    private static final Pattern MODULE_NAME = Pattern.compile("\\bmodule\\s+([\\w.]+)");

    /**
     * Every {@code module-info.java} in the source set, mapped to (module name, module root). The module root is the
     * directory containing {@code module-info.java}; javac's {@code --module-source-path <name>=<root>} resolves the
     * module's sources from there. A {@code module-info.java} whose declaration cannot be parsed is skipped.
     */
    private static List<ModuleSource> discoverModuleSources(List<Path> javaFiles) {
        List<ModuleSource> moduleSources = new ArrayList<>();
        for (Path file : javaFiles) {
            if (!file.getFileName().toString().equals("module-info.java")) {
                continue;
            }
            String moduleName = parseModuleName(file);
            if (moduleName != null) {
                moduleSources.add(new ModuleSource(moduleName, file.toAbsolutePath().normalize().getParent()));
            }
        }
        return moduleSources;
    }

    private static String parseModuleName(Path moduleInfo) {
        try {
            // Strip line/block comments so a commented-out declaration is not mistaken for the real one.
            String text = Files.readString(moduleInfo)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("//[^\\n]*", " ");
            Matcher matcher = MODULE_NAME.matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Resolves configured classpath/module-path entries the way source roots are resolved: relative entries are taken
     * against the project root (matching the Serena YAML mental model) while absolute entries are preserved as-is.
     */
    private static List<Path> resolveAgainst(Path projectRoot, List<Path> paths) {
        List<Path> resolved = new ArrayList<>();
        for (Path path : paths) {
            resolved.add(path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize());
        }
        return resolved;
    }

    // javac options this method deterministically reconstructs from the resolved model/config. Any extracted compiler
    // arg matching one of these (as a bare flag with its value in the next token, or fused as "<flag>=<value>") is
    // dropped during the merge so the build tool's args can only EXTEND -- never override -- the paths and versions
    // Serena already resolved. Everything else (--enable-preview, --add-exports/opens/reads, -parameters, -A..., -X...)
    // passes through verbatim.
    private static final Set<String> MANAGED_VALUE_FLAGS = Set.of(
            "--release", "-source", "-target", "-encoding",
            "-classpath", "-cp", "--class-path",
            "--module-path", "-p",
            "-sourcepath", "--source-path",
            "-processorpath", "--processor-path", "--processor-module-path",
            "-d", "-s", "-h",
            "--module-source-path");

    /**
     * Merges the build tool's extracted extra compiler arguments onto the deterministically reconstructed {@code options},
     * skipping any argument that would collide with an option this method already controls ({@link #MANAGED_VALUE_FLAGS}
     * plus the {@code -proc:*} processing mode). Surviving arguments -- {@code --enable-preview}, {@code --add-exports} /
     * {@code --add-opens} / {@code --add-reads}, {@code -parameters}, {@code -A} processor options, {@code -X*}/{@code -g}/
     * lint settings, etc. -- are preserved verbatim and in order so javac sees exactly what the build would pass. The skip
     * is what "merge safely" means: extracted args extend, never override, Serena's resolved paths and versions.
     */
    private static void appendExtractedCompilerArgs(List<String> options, List<String> extractedCompilerArgs) {
        for (int i = 0; i < extractedCompilerArgs.size(); i++) {
            String arg = extractedCompilerArgs.get(i);
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String flag = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (MANAGED_VALUE_FLAGS.contains(flag)) {
                // A managed flag carrying its value as a separate token (e.g. "--release" "17"): drop that value token
                // too, unless the value was already fused into this token with '=' (e.g. "--release=17").
                if (!arg.contains("=") && i + 1 < extractedCompilerArgs.size()) {
                    i++;
                }
                continue;
            }
            if (arg.startsWith("-proc:")) {
                continue;
            }
            options.add(arg);
        }
    }

    private static List<String> createJavacOptions(JavaVersions versions, String encoding, String annotationProcessing, List<Path> annotationProcessorPath, List<Path> classpath, List<Path> modulePath, List<ModuleSource> moduleSources, Path moduleOutputDir, List<String> extractedCompilerArgs) {
        boolean modular = !moduleSources.isEmpty();
        // --module-source-path is only needed to disambiguate multiple modules in one source set; a single module is
        // resolved from its passed-in sources directly (and that path needs no -d output directory).
        boolean useModuleSourcePath = moduleSources.size() > 1;
        List<String> options = new ArrayList<>();
        // Default warning suppression (design's required default javac options alongside -proc:none and -encoding). The
        // compiler invocation must be stable and warning-free regardless of source set mode, so this is emitted for every
        // source set (plain, explicit, Maven, Gradle); non-error diagnostics are filtered later, but the invocation itself
        // stays quiet.
        options.add("-Xlint:none");
        if (versions.releaseVersion() != null) {
            options.add("--release");
            options.add(versions.releaseVersion());
        } else {
            if (versions.sourceVersion() != null) {
                options.add("-source");
                options.add(versions.sourceVersion());
            }
            if (versions.targetVersion() != null) {
                options.add("-target");
                options.add(versions.targetVersion());
            }
        }
        if (encoding != null) {
            options.add("-encoding");
            options.add(encoding);
        }
        if (modular) {
            // Named modules read their dependencies from the module path, NOT the classpath (a named module cannot read
            // the unnamed/classpath module). Build tools surface deps differently: Gradle puts them on modulePath when
            // modular, but Maven/explicit projects carry them on classpath. Route BOTH onto --module-path (jars resolve
            // as automatic modules) so a modular project never loses its dependencies and compiles/validates correctly.
            List<Path> modularPath = new ArrayList<>(modulePath);
            for (Path entry : classpath) {
                if (!modularPath.contains(entry)) {
                    modularPath.add(entry);
                }
            }
            if (!modularPath.isEmpty()) {
                options.add("--module-path");
                options.add(joinPaths(modularPath));
            }
            if (useModuleSourcePath) {
                // One --module-source-path <module>=<root> per declared module so javac builds the real module graph
                // and can locate every module's sources. Multiple modules cannot be combined into a single
                // --module-source-path value (the path separator is ambiguous), so each module gets its own option.
                for (ModuleSource moduleSource : moduleSources) {
                    options.add("--module-source-path");
                    options.add(moduleSource.name() + "=" + moduleSource.root().toAbsolutePath().normalize());
                }
                // Put every project module into the root module set so each is resolved and validated even when no
                // other module requires it.
                options.add("--add-modules");
                options.add(moduleSources.stream().map(ModuleSource::name).distinct().collect(Collectors.joining(",")));
                if (moduleOutputDir != null) {
                    options.add("-d");
                    options.add(moduleOutputDir.toAbsolutePath().normalize().toString());
                }
            }
        } else {
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(joinPaths(classpath));
            }
            if (!modulePath.isEmpty()) {
                options.add("--module-path");
                options.add(joinPaths(modulePath));
            }
        }
        // Annotation-processing vocabulary (JDK-portable; -proc:full is JDK21+ only and intentionally avoided):
        //   none      -> disable processing entirely (-proc:none)
        //   classpath -> let javac discover processors on the compile classpath (no -proc:none, no -processorpath)
        //   project   -> run only the project's declared processors via a dedicated -processorpath; if the project
        //                declares none, this means "no processors" and degrades to -proc:none.
        if ("classpath".equals(annotationProcessing)) {
            // No options: javac's default discovers processors on the regular compile classpath.
        } else if ("project".equals(annotationProcessing)) {
            if (annotationProcessorPath.isEmpty()) {
                options.add("-proc:none");
            } else {
                options.add("-processorpath");
                options.add(joinPaths(annotationProcessorPath));
            }
        } else {
            options.add("-proc:none");
        }
        appendExtractedCompilerArgs(options, extractedCompilerArgs);
        return options;
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream().map(path -> path.toAbsolutePath().normalize().toString()).reduce((left, right) -> left + System.getProperty("path.separator") + right).orElse("");
    }

    private static List<Path> discoverInvalidationFiles(Path projectRoot) {
        // Root-only files that are not picked up by the recursive build-file walk below (wrappers and Serena config).
        // Both the Gradle (gradlew/gradlew.bat) and Maven (mvnw/mvnw.cmd) wrappers are tracked: a wrapper version bump can
        // change the resolved build tool and therefore the extracted model.
        List<String> names = List.of(
                "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd",
                ".mvn/maven.config", ".serena/project.yml", ".serena/project.local.yml"
        );
        Set<Path> result = new LinkedHashSet<>();
        for (String name : names) {
            Path path = projectRoot.resolve(name).normalize();
            if (Files.exists(path)) {
                result.add(path);
            }
        }
        // The Maven wrapper directory pins the wrapper/Maven distribution (maven-wrapper.properties and friends); a change
        // there can alter the build tooling, so every regular file under .mvn/wrapper is an invalidation input.
        Path mvnWrapperDir = projectRoot.resolve(".mvn/wrapper");
        if (Files.isDirectory(mvnWrapperDir)) {
            try (Stream<Path> wrapperFiles = Files.walk(mvnWrapperDir)) {
                wrapperFiles.filter(Files::isRegularFile).map(path -> path.toAbsolutePath().normalize()).forEach(result::add);
            } catch (IOException ignored) {
                // ignore unreadable wrapper dir; build extraction surfaces any resulting model errors separately
            }
        }
        // Recursively include every build file (subproject build.gradle/pom.xml, gradle.properties, version catalogs,
        // buildSrc, etc.) so a dependency change in any subproject invalidates the cached/extracted model (M5).
        result.addAll(collectBuildFiles(projectRoot));
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))
                    .filter(path -> isNotUnderExcludedDirectory(path, DEFAULT_EXCLUDED_DIR_NAMES))
                    .forEach(result::add);
        } catch (IOException ignored) {
            // ignore unreadable invalidation walk; Java file discovery will report model errors separately if needed
        }
        return result.stream().sorted().toList();
    }

    /**
     * Recursively collects every build file under {@code projectRoot} (pom.xml, build/settings .gradle(.kts),
     * gradle.properties, *.versions.toml) excluding generated/VCS directories. Shared by {@link ProjectModelDiscoverer}
     * invalidation tracking and {@link ExtractionCache} keying so a subproject build-file change is detected (M5).
     */
    static List<Path> collectBuildFiles(Path projectRoot) {
        Set<Path> result = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(ProjectModelDiscoverer::isBuildFile)
                    .filter(path -> isNotUnderExcludedDirectory(path, DEFAULT_EXCLUDED_DIR_NAMES))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(result::add);
        } catch (IOException ignored) {
            // ignore unreadable build-file walk; extraction/validation surfaces resulting model errors separately
        }
        return result.stream().sorted().toList();
    }

    private static boolean isBuildFile(Path path) {
        String name = path.getFileName().toString();
        return switch (name) {
            case "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                    "gradle.properties" -> true;
            default -> name.endsWith(".versions.toml");
        };
    }

    /**
     * Conventional source-root discovery (plain/javac fallback, per the project-model plan section 3 step 4): collects
     * {@code src/main/java}, {@code src/test/java}, and {@code src} as separate roots, keeping only those that exist on
     * disk, and honoring any manual {@code DiscoveryConfig.sourceRoots} overrides (also existence-filtered). When none of
     * those exist the project root itself is used so a flat single-file project (sources directly under the root) still
     * resolves. {@code createSourceSet} normalizes/deduplicates/sorts the returned roots and re-checks existence.
     */
    private static List<Path> discoverJavaRoots(Path projectRoot, DiscoveryConfig config) {
        List<Path> roots = new ArrayList<>();
        for (String override : config.explicitSourceRoots()) {
            Path root = projectRoot.resolve(override).normalize();
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        for (String conventional : List.of("src/main/java", "src/test/java", "src")) {
            Path root = projectRoot.resolve(conventional);
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            return List.of(projectRoot);
        }
        return roots;
    }

    private static List<Path> collectJavaFiles(Path projectRoot, List<Path> roots, int maxFiles, IgnoredMatcher matcher) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Set<Path> javaFiles = new LinkedHashSet<>();
        for (Path root : roots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(path -> path.toAbsolutePath().normalize())
                        // Bare-name patterns are tested against segments INSIDE the declared root only: the root itself is
                        // an explicit (possibly generated) source root that may legitimately live under build/ or target/,
                        // so its own ancestors must not exclude it. Glob patterns are tested against the project-relative
                        // POSIX path so design examples like "target/**" / "build/**" prune exactly what they name.
                        .filter(path -> !matcher.excludes(normalizedRoot.relativize(path), toProjectRelativePosix(normalizedProjectRoot, path)))
                        .limit((long) maxFiles + 1)
                        .forEach(javaFiles::add);
            } catch (IOException ignored) {
                // unreadable roots produce an empty source-set, which is reported by the model-level no-sources check
            }
        }
        return javaFiles.stream().sorted().toList();
    }

    /** Project-relative POSIX path for glob matching; falls back to the absolute POSIX path when outside the root. */
    private static String toProjectRelativePosix(Path projectRoot, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path base = normalized.startsWith(projectRoot) ? projectRoot.relativize(normalized) : normalized;
        return base.toString().replace('\\', '/');
    }

    private static boolean isNotUnderExcludedDirectory(Path path, Set<String> excludedDirNames) {
        for (Path part : path) {
            if (excludedDirNames.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Matches discovered source files against the configured {@code ignoredPatterns}. Two pattern styles coexist:
     *
     * <ul>
     *   <li><b>Bare directory name</b> — a single path segment with no {@code /} and no glob metacharacter
     *       ({@code *}, {@code ?}, {@code [}). Keeps the historical directory-segment semantics: it prunes a file when
     *       ANY segment of the file's path <em>within its source root</em> equals the name. This is why the built-in
     *       defaults ({@code build}, {@code target}, {@code .git}, ...) still prune those directories anywhere.</li>
     *   <li><b>Glob</b> — any pattern containing {@code /} or a glob metacharacter. Matched against the file's
     *       <em>project-relative POSIX path</em>, so {@code target/**}, {@code build/**}, and nested patterns such as
     *       {@code src/**}{@code /generated/**} prune exactly what they name. {@code **} matches across directory
     *       separators, {@code *} and {@code ?} match within a single segment.</li>
     * </ul>
     */
    static final class IgnoredMatcher {
        private final Set<String> bareDirNames;
        private final List<Pattern> globs;

        private IgnoredMatcher(Set<String> bareDirNames, List<Pattern> globs) {
            this.bareDirNames = bareDirNames;
            this.globs = globs;
        }

        static IgnoredMatcher from(Collection<String> patterns) {
            Set<String> bareDirNames = new LinkedHashSet<>();
            List<Pattern> globs = new ArrayList<>();
            for (String raw : patterns) {
                if (raw == null) {
                    continue;
                }
                String pattern = raw.trim();
                if (pattern.isEmpty()) {
                    continue;
                }
                if (isBareDirName(pattern)) {
                    bareDirNames.add(pattern);
                } else {
                    globs.add(Pattern.compile(globToRegex(stripLeadingSlashes(pattern))));
                }
            }
            return new IgnoredMatcher(bareDirNames, globs);
        }

        /** True when the file should be pruned. {@code rootRelative} is path-within-source-root; {@code projectRelative} is project-relative POSIX. */
        boolean excludes(Path rootRelative, String projectRelative) {
            if (!bareDirNames.isEmpty()) {
                for (Path segment : rootRelative) {
                    if (bareDirNames.contains(segment.toString())) {
                        return true;
                    }
                }
            }
            for (Pattern glob : globs) {
                if (glob.matcher(projectRelative).matches()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isBareDirName(String pattern) {
            return pattern.indexOf('/') < 0
                    && pattern.indexOf('*') < 0
                    && pattern.indexOf('?') < 0
                    && pattern.indexOf('[') < 0;
        }

        /** A leading {@code /} only anchors the glob to the project root, which it already is; drop it so it matches. */
        private static String stripLeadingSlashes(String pattern) {
            int start = 0;
            while (start < pattern.length() && pattern.charAt(start) == '/') {
                start++;
            }
            return pattern.substring(start);
        }

        /**
         * Translates a path glob to an anchored regex. {@code **}{@code /} matches zero or more leading directories;
         * a trailing/standalone {@code **} matches any remaining characters (including separators); {@code *} and
         * {@code ?} match within a single segment; every other regex metacharacter is escaped literally.
         */
        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                if (c == '*') {
                    boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubleStar) {
                        i += 2;
                        if (i < glob.length() && glob.charAt(i) == '/') {
                            // "**/" => optional run of leading directories (matches zero dirs too).
                            regex.append("(?:.*/)?");
                            i++;
                        } else {
                            // Trailing or standalone "**" => anything, including path separators.
                            regex.append(".*");
                        }
                    } else {
                        // Single "*" stays within one path segment.
                        regex.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    regex.append("[^/]");
                    i++;
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                    i++;
                }
            }
            return regex.append('$').toString();
        }
    }

    private static String normalizeJavaVersion(String version) {
        if (version == null) {
            return null;
        }
        return version.replace('_', '.');
    }

    enum BuildKind {
        EXPLICIT,
        GRADLE,
        MAVEN,
        PLAIN
    }

    record JavaVersions(String releaseVersion, String sourceVersion, String targetVersion) {
    }

    record DiscoveryConfig(
            String buildToolMode,
            List<String> explicitSourceRoots,
            List<Path> classpath,
            List<Path> modulePath,
            String releaseVersion,
            String sourceVersion,
            String targetVersion,
            String encoding,
            String annotationProcessing,
            boolean allowIncompleteAnalysis,
            boolean allowConventionalFallback,
            boolean offline,
            List<String> mavenProfiles,
            boolean generatedSourcesRead,
            boolean generatedSourcesEdit,
            List<Path> lombokJars,
            BuildModel explicitModel,
            int maxFiles,
            // Optional JDTLS-derived build-tool settings (present only when java_refactor.use_jdtls_settings is enabled
            // and the corresponding Java LS setting is configured); any may be null/absent.
            String mavenUserSettings,
            String gradleUserHome,
            String gradleJavaHome,
            Boolean gradleWrapperEnabled,
            // Source-discovery ignored patterns from the initialize contract's ignoredPatterns. Null when the contract
            // supplies none (ignoredMatcher() then falls back to DEFAULT_EXCLUDED_DIR_NAMES); a present list (even empty)
            // fully replaces the default. Bare directory names keep directory-segment semantics; entries containing a
            // path separator or a glob metacharacter (e.g. "target/**", "build/**") are matched as globs over the
            // project-relative POSIX path.
            List<String> ignoredPatterns,
            // Non-null when the initialize config supplied conflicting build-tool mode aliases (buildToolMode vs
            // buildToolModel with different values); surfaced as a project-model error by buildUnvalidatedModel.
            String buildToolModeConflict
    ) {
        static DiscoveryConfig from(String configuration) {
            Map<String, Object> json = parseConfiguration(configuration);
            BuildToolMode buildToolMode = readBuildToolMode(json);
            return new DiscoveryConfig(
                    buildToolMode.value(),
                    readStringList(json, "sourceRoots"),
                    readPathList(json, "classpath"),
                    readPathList(json, "modulePath"),
                    readString(json, "release", null),
                    readString(json, "source", null),
                    readString(json, "target", null),
                    readString(json, "encoding", "UTF-8"),
                    readAnnotationProcessing(json.get("annotationProcessing")),
                    readBool(json, "allowIncompleteAnalysis", false),
                    readBool(json, "allowConventionalFallback", false),
                    readBool(json, "offline", false),
                    readStringList(json, "mavenProfiles"),
                    readGeneratedSourcesBool(json, "read", true),
                    readGeneratedSourcesBool(json, "edit", false),
                    readLombokJars(json),
                    readExplicitModel(json),
                    readInt(json, "maxFiles", 2000),
                    readString(json, "mavenUserSettings", null),
                    readString(json, "gradleUserHome", null),
                    readString(json, "gradleJavaHome", null),
                    readNullableBool(json, "gradleWrapperEnabled"),
                    readNullableStringList(json, "ignoredPatterns"),
                    buildToolMode.conflict()
            );
        }

        private static BuildModel readExplicitModel(Map<String, Object> json) {
            Object value = json.get("model");
            if (!(value instanceof Map<?, ?> modelMap)) {
                return null;
            }
            Object modulesValue = modelMap.get("modules");
            if (!(modulesValue instanceof List<?> modules)) {
                return new BuildModel(List.of());
            }
            List<BuildModel.Module> parsedModules = new ArrayList<>();
            for (Object moduleValue : modules) {
                if (!(moduleValue instanceof Map<?, ?> moduleMap)) {
                    continue;
                }
                Object sourceSetsValue = firstValue(moduleMap, "sourceSets", "source_sets");
                List<BuildModel.ModelSourceSet> parsedSourceSets = new ArrayList<>();
                if (sourceSetsValue instanceof List<?> sourceSets) {
                    for (Object sourceSetValue : sourceSets) {
                        if (!(sourceSetValue instanceof Map<?, ?> sourceSetMap)) {
                            continue;
                        }
                        parsedSourceSets.add(new BuildModel.ModelSourceSet(
                                readModelString(sourceSetMap, "name", "main"),
                                readModelStringList(sourceSetMap, "srcDirs", "src_dirs", "sourceRoots", "source_roots"),
                                readModelStringList(sourceSetMap, "generatedRoots", "generated_roots"),
                                readModelStringList(sourceSetMap, "outputDirs", "output_dirs"),
                                readModelStringList(sourceSetMap, "classpath"),
                                readModelStringList(sourceSetMap, "modulePath", "module_path"),
                                readModelStringList(sourceSetMap, "annotationProcessorPath", "annotation_processor_path"),
                                readModelString(sourceSetMap, "release", null),
                                readModelString(sourceSetMap, "source", null),
                                readModelString(sourceSetMap, "target", null),
                                readModelString(sourceSetMap, "encoding", null),
                                readModelStringList(sourceSetMap, "dependsOnProjects", "depends_on_projects"),
                                readModelStringList(sourceSetMap, "compilerArgs", "compiler_args"),
                                // Explicit/conventional models carry a proven classpath (no build-classpath step); B11
                                // resource roots are read from an explicit "resourceDirs"/"resource_dirs" field when given.
                                true,
                                readModelStringList(sourceSetMap, "resourceDirs", "resource_dirs", "resourceRoots", "resource_roots")));
                    }
                } else {
                    // §17.3 flat-module form: each module entry carries sourceRoots/classpath/release directly (no
                    // nested sourceSets list). Synthesize a single implicit source set named after the module so that
                    // `modules: [{name: "lib", sourceRoots: [...], release: "17"}]` produces a "lib" source set.
                    List<String> flatSourceRoots = readModelStringList(moduleMap, "srcDirs", "src_dirs", "sourceRoots", "source_roots");
                    if (!flatSourceRoots.isEmpty()) {
                        parsedSourceSets.add(new BuildModel.ModelSourceSet(
                                "main",
                                flatSourceRoots,
                                readModelStringList(moduleMap, "generatedRoots", "generated_roots"),
                                readModelStringList(moduleMap, "outputDirs", "output_dirs"),
                                readModelStringList(moduleMap, "classpath"),
                                readModelStringList(moduleMap, "modulePath", "module_path"),
                                readModelStringList(moduleMap, "annotationProcessorPath", "annotation_processor_path"),
                                readModelString(moduleMap, "release", null),
                                readModelString(moduleMap, "source", null),
                                readModelString(moduleMap, "target", null),
                                readModelString(moduleMap, "encoding", null),
                                readModelStringList(moduleMap, "dependsOnProjects", "depends_on_projects"),
                                readModelStringList(moduleMap, "compilerArgs", "compiler_args"),
                                true,
                                readModelStringList(moduleMap, "resourceDirs", "resource_dirs", "resourceRoots", "resource_roots")));
                    }
                }
                // For the flat-module form the user supplies "name" (not "project"); prefer "project" for compat with
                // the nested-sourceSets form, then fall back to "name" (flat-module), then to "root" (single-module).
                String moduleProject = firstValue(moduleMap, "project", "name") instanceof String s ? s : "root";
                parsedModules.add(new BuildModel.Module(moduleProject, parsedSourceSets));
            }
            return new BuildModel(parsedModules);
        }

        private static Object firstValue(Map<?, ?> map, String... keys) {
            for (String key : keys) {
                if (map.containsKey(key)) {
                    return map.get(key);
                }
            }
            return null;
        }

        private static String readModelString(Map<?, ?> map, String key, String defaultValue) {
            Object value = map.get(key);
            return value instanceof String string ? string : defaultValue;
        }

        private static List<String> readModelStringList(Map<?, ?> map, String... keys) {
            Object value = firstValue(map, keys);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<String> strings = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String string) {
                    strings.add(string);
                }
            }
            return strings;
        }

        /** The effective build-tool mode plus any conflict between the design alias {@code buildToolModel} and {@code buildToolMode}. */
        private record BuildToolMode(String value, String conflict) {
        }

        /**
         * Resolves the build-tool mode from BOTH the design-contract key {@code buildToolModel} and the implementation
         * key {@code buildToolMode}. Either spelling is honored; if both are present and disagree (case-insensitively),
         * the disagreement is reported as a conflict so discovery fails loudly instead of silently picking one.
         */
        private static BuildToolMode readBuildToolMode(Map<String, Object> json) {
            String mode = readString(json, "buildToolMode", null);
            String designMode = readString(json, "buildToolModel", null);
            if (mode != null && designMode != null && !mode.trim().equalsIgnoreCase(designMode.trim())) {
                return new BuildToolMode(mode, "Conflicting build-tool mode configuration: buildToolMode=\"" + mode
                        + "\" conflicts with buildToolModel=\"" + designMode + "\". They are aliases; supply only one.");
            }
            return new BuildToolMode(mode != null ? mode : designMode, null);
        }

        /**
         * The matcher pruning files during source discovery. When the initialize contract supplies {@code ignoredPatterns}
         * it fully replaces the default — including an explicit empty list, which means "prune nothing" — so configuring it
         * is real behavior. When the key is absent, falls back to {@link #DEFAULT_EXCLUDED_DIR_NAMES} (bare names with
         * directory-segment semantics, preserving the historical built-in exclusions).
         */
        IgnoredMatcher ignoredMatcher() {
            return IgnoredMatcher.from(ignoredPatterns == null ? DEFAULT_EXCLUDED_DIR_NAMES : ignoredPatterns);
        }

        /** Bundles the optional JDTLS-derived build-tool settings for the {@link BuildModelExtractor}. */
        BuildModelExtractor.JdtlsSettings jdtlsSettings() {
            return new BuildModelExtractor.JdtlsSettings(mavenUserSettings, gradleUserHome, gradleJavaHome, gradleWrapperEnabled);
        }

        private static Map<String, Object> parseConfiguration(String configuration) {
            if (configuration == null) {
                return Map.of();
            }
            String trimmed = configuration.trim();
            if (trimmed.isEmpty() || "default".equals(trimmed)) {
                return Map.of();
            }
            return Json.parseObject(trimmed);
        }

        /**
         * Normalizes the annotation-processing mode to one of {@code none|classpath|project}. Missing/blank values and
         * unknown strings default to {@code none}. Legacy boolean/"disabled" inputs are mapped for back-compat: boolean
         * {@code false}/"disabled" -> {@code none}, boolean {@code true} -> {@code classpath}.
         */
        private static String readAnnotationProcessing(Object value) {
            if (Boolean.TRUE.equals(value)) {
                return "classpath";
            }
            if (Boolean.FALSE.equals(value)) {
                return "none";
            }
            if (value instanceof String string) {
                String normalized = string.trim().toLowerCase(java.util.Locale.ROOT);
                if ("classpath".equals(normalized) || "project".equals(normalized)) {
                    return normalized;
                }
            }
            return "none";
        }

        private static boolean readGeneratedSourcesBool(Map<String, Object> json, String key, boolean defaultValue) {
            Object nested = json.get("generated_sources");
            if (nested instanceof Map<?, ?> map && map.get(key) != null) {
                return readBoolValue(map.get(key), defaultValue);
            }
            Object camelNested = json.get("generatedSources");
            if (camelNested instanceof Map<?, ?> map && map.get(key) != null) {
                return readBoolValue(map.get(key), defaultValue);
            }
            String flatKey = "generated_sources." + key;
            if (json.containsKey(flatKey)) {
                return readBoolValue(json.get(flatKey), defaultValue);
            }
            String camelFlatKey = "generatedSources" + key.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + key.substring(1);
            if (json.containsKey(camelFlatKey)) {
                return readBoolValue(json.get(camelFlatKey), defaultValue);
            }
            return defaultValue;
        }

        private static List<Path> readLombokJars(Map<String, Object> json) {
            List<Path> jars = new ArrayList<>();
            Object single = json.get("lombokJar");
            if (single instanceof String string && !string.isBlank()) {
                jars.add(Path.of(string.trim()));
            }
            for (String key : List.of("lombokJars", "lombokClasspath")) {
                for (String entry : readStringList(json, key)) {
                    jars.add(Path.of(entry));
                }
            }
            return jars.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
        }

        private static boolean readBoolValue(Object value, boolean defaultValue) {
            if (value instanceof Boolean boolValue) {
                return boolValue;
            }
            if (value instanceof String string) {
                return Boolean.parseBoolean(string);
            }
            return defaultValue;
        }

        private static String readString(Map<String, Object> json, String key, String defaultValue) {
            Object value = json.get(key);
            if (value instanceof String string) {
                return string;
            }
            if (value instanceof Long longValue) {
                return String.valueOf((long) longValue);
            }
            if (value instanceof Double doubleValue) {
                return String.valueOf((double) doubleValue);
            }
            return defaultValue;
        }

        private static List<String> readStringList(Map<String, Object> json, String key) {
            Object value = json.get(key);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                if (element != null) {
                    String text = String.valueOf(element).trim();
                    if (!text.isEmpty()) {
                        result.add(text);
                    }
                }
            }
            return List.copyOf(result);
        }

        /** Like {@link #readStringList} but returns null when the key is absent, distinguishing "unset" from "empty". */
        private static List<String> readNullableStringList(Map<String, Object> json, String key) {
            return json.get(key) instanceof List<?> ? readStringList(json, key) : null;
        }

        private static List<Path> readPathList(Map<String, Object> json, String key) {
            return readStringList(json, key).stream().map(Path::of).sorted(Comparator.comparing(Path::toString)).toList();
        }

        private static Boolean readNullableBool(Map<String, Object> json, String key) {
            Object value = json.get(key);
            if (value instanceof Boolean boolValue) {
                return boolValue;
            }
            if (value instanceof String string) {
                return Boolean.parseBoolean(string);
            }
            return null;
        }

        private static boolean readBool(Map<String, Object> json, String key, boolean defaultValue) {
            return json.containsKey(key) ? readBoolValue(json.get(key), defaultValue) : defaultValue;
        }

        private static int readInt(Map<String, Object> json, String key, int defaultValue) {
            Object value = json.get(key);
            long parsed;
            if (value instanceof Long longValue) {
                parsed = longValue;
            } else if (value instanceof Double doubleValue) {
                parsed = (long) (double) doubleValue;
            } else if (value instanceof String string) {
                try {
                    parsed = Long.parseLong(string.trim());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            } else {
                return defaultValue;
            }
            return parsed > 0 ? (int) parsed : defaultValue;
        }
    }
}
