package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.GeneratedSourcePolicy;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.shared.SourceText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3 planner that owns the <em>moveSourceRoot</em> operation (headline plan §1.1/§4.1, the {@code moveSourceRoot}
 * workspace step / {@code JavaMoveSourceRootTool}).
 *
 * <p>Relocates Java source files from one configured source root to ANOTHER configured source root while keeping their
 * package declarations identical — a pure physical move between source sets (e.g. {@code src/main/java} →
 * {@code src/main/java11} for a multi-release jar, or shifting a package tree between modules). Because every moved
 * file's declared package is unchanged, fully-qualified names and imports across the project are unaffected, so this
 * planner emits ONLY rename {@link FileOperation}s and no text edits. An optional {@code packages} list restricts the
 * move to specific packages (and, by default, their subpackages); an empty list moves every package rooted under the
 * source root.
 *
 * <p>The planner carries the not-yet-validated placeholder diagnostic delta; the authoritative before/after javac delta
 * is produced by the sidecar's {@code PreviewDiagnosticValidator} after the plan is built (the same contract every
 * V2/V3 operation uses), so a move that would shadow or duplicate a type on the compile path is caught and refused
 * rather than silently applied.
 */
public final class MoveSourceRootPlanner {
    private static final Pattern DOTTED_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final PackageRewritePolicy policy;

    public MoveSourceRootPlanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, PackageRewritePolicy.defaults());
    }

    public MoveSourceRootPlanner(Path projectRoot, JavaProjectModel model, PackageRewritePolicy policy) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.policy = policy;
    }

    /**
     * Plans the source-root move. {@code fields} must carry {@code sourceRoot} and {@code targetSourceRoot} (both
     * project-relative configured source roots); optional {@code packages} (a JSON array or comma-separated list of
     * dotted package names, default empty = all packages under {@code sourceRoot}) and {@code includeSubpackages}
     * (default {@code true}). {@code apply} only affects the reported {@code mode}; the sidecar never writes files.
     */
    public String plan(Map<String, Object> fields, boolean apply) {
        try {
            io.serena.javarefactor.v3.transformation.TransformationStep step = planStep(fields);
            return ResponseBuilder.acceptedResult(
                    projectRoot,
                    "moveSourceRoot",
                    apply,
                    step.semanticTargetJson(),
                    step.edits(),
                    step.fileOperations(),
                    step.warnings(),
                    List.of("moveSourceRoot relocates files between configured source roots; the after-state is "
                            + "javac-validated before the preview is accepted."),
                    ResponseBuilder.DiagnosticDelta.unvalidated(),
                    false);
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("moveSourceRoot", apply, refusal.code, refusal.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("moveSourceRoot", apply, "move_source_root_failed", error.getMessage());
        }
    }

    /**
     * Builds the structured edit/op contribution for this operation (refactor-feature-plan-V3.md §3) so a transformation
     * workspace can compose it with other operations. Throws {@link Refusal} on a precondition violation and
     * {@link IOException} on a read failure — the caller maps those to the canonical refusal JSON.
     */
    public io.serena.javarefactor.v3.transformation.TransformationStep planStep(Map<String, Object> fields) throws IOException {
        {
            String sourceRootText = required(fields, "sourceRoot");
            String targetRootText = required(fields, "targetSourceRoot");
            boolean rewriteBuildFiles = boolValue(fields, "rewriteBuildFiles", false);

            Path sourceRoot = projectRoot.resolve(sourceRootText).toAbsolutePath().normalize();
            String owningSourceSet = configuredSourceSetName(sourceRoot);
            if (owningSourceSet == null) {
                throw new Refusal("source_root_not_found",
                        "sourceRoot '" + sourceRootText + "' is not a configured source root of this project.");
            }
            Path targetRoot = projectRoot.resolve(targetRootText).toAbsolutePath().normalize();
            if (sourceRoot.equals(targetRoot)) {
                throw new Refusal("malformed_move_source_root",
                        "sourceRoot and targetSourceRoot must be different configured source roots.");
            }

            // §6.3: no build-file edits by default. A target that is not an already-configured source root would
            // require registering it as a srcDir of the owning source set, so refuse with the coded signal unless the
            // caller explicitly opts in to an additive build-file rewrite via rewriteBuildFiles.
            List<PlannerSupport.TextEdit> edits = new ArrayList<>();
            if (configuredSourceSetName(targetRoot) == null) {
                if (!rewriteBuildFiles) {
                    throw new Refusal("BUILD_FILE_UPDATE_REQUIRED",
                            "Target source root '" + targetRootText + "' is not a configured source root of source set '"
                                    + owningSourceSet + "'. Edit the build file manually, or re-run with "
                                    + "rewriteBuildFiles=true to append an additive source-root registration "
                                    + "(Gradle sourceSets srcDir, or Maven build-helper-maven-plugin add-source).");
                }
                edits.add(buildSourceSetRegistration(sourceRoot, targetRoot, owningSourceSet));
            }

            boolean includeSubpackages = boolValue(fields, "includeSubpackages", true);
            boolean preservePackageNames = boolValue(fields, "preservePackageNames", true);
            Set<String> requestedPackages = parsePackages(fields.get("packages"));

            // Enumerate the matched files once, capturing each file's declared package and the package implied by its
            // directory under the source root (the §6.2 "directory mapping"). Shared preconditions (generated-source,
            // destination collision, empty selection) apply to BOTH modes, so they are validated here.
            List<MatchedFile> matched = new ArrayList<>();
            Set<String> matchedPackages = new LinkedHashSet<>();
            Set<Path> destinations = new LinkedHashSet<>();
            for (Path file : filesUnder(sourceRoot)) {
                String declared = declaredPackage(SourceText.read(model, file));
                if (!requestedPackages.isEmpty() && !packageRequested(declared, requestedPackages, includeSubpackages)) {
                    continue;
                }
                matchedPackages.add(declared);

                String relativeOld = PlannerSupport.relative(projectRoot, file);
                if (GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)
                        || GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativeOld)) {
                    throw new Refusal("non_editable_target",
                            "Refusing moveSourceRoot: '" + relativeOld + "' is generated/non-editable and cannot be moved.");
                }
                Path relativeWithinRoot = sourceRoot.relativize(file.toAbsolutePath().normalize());
                Path newFile = targetRoot.resolve(relativeWithinRoot).toAbsolutePath().normalize();
                if (Files.exists(newFile) || !destinations.add(newFile)) {
                    throw new Refusal("package_collision",
                            "Target source root already contains '" + PlannerSupport.relative(projectRoot, newFile)
                                    + "'; refusing to overwrite a file on the move.");
                }
                // §6.2 step 6: the new package is the dotted form of the file's directory beneath the (target) source
                // root. Because the relative directory is preserved by the move, this is the directory beneath the
                // source root; it equals the declared package for a conventional file and differs only when the
                // declaration did not already match its on-disk directory.
                String directoryPackage = directoryPackage(relativeWithinRoot);
                matched.add(new MatchedFile(file, declared, directoryPackage, relativeOld,
                        PlannerSupport.relative(projectRoot, newFile)));
            }

            if (matched.isEmpty()) {
                throw new Refusal("package_not_found", requestedPackages.isEmpty()
                        ? "No Java source files were found under source root '" + sourceRootText + "'."
                        : "No Java source files matching the requested packages were found under '" + sourceRootText + "'.");
            }

            String semanticTarget = "{\"sourceRoot\":" + io.serena.javarefactor.protocol.JsonUtil.quote(sourceRootText)
                    + ",\"targetSourceRoot\":" + io.serena.javarefactor.protocol.JsonUtil.quote(targetRootText)
                    + ",\"packageCount\":" + matchedPackages.size()
                    + ",\"preservePackageNames\":" + preservePackageNames + "}";
            List<String> warnings = new ArrayList<>();

            if (preservePackageNames) {
                // §6.2 step 5: pure physical relocation — files move, declarations and references untouched.
                List<FileOperation> fileOperations = new ArrayList<>();
                for (MatchedFile mf : matched) {
                    fileOperations.add(FileOperation.rename(
                            mf.relativeOld(), mf.relativeNew(), PlannerSupport.sha256(mf.file())));
                }
                warnings.add("moveSourceRoot relocates " + fileOperations.size() + " file(s) from source root '"
                        + sourceRootText + "' to '" + targetRootText
                        + "' without changing package declarations; fully-qualified names and imports are unaffected.");
                warnings.add(PlannerSupport.reflectionResourceCaveat("source root '" + sourceRootText + "'"));
                appendBuildFileWarning(edits, warnings, owningSourceSet, targetRootText);
                return new io.serena.javarefactor.v3.transformation.TransformationStep(
                        "moveSourceRoot", edits, fileOperations, warnings, semanticTarget);
            }

            // §6.2 step 6: recompute each moved file's package from the directory mapping and run the package-rename
            // logic. Files whose declared package already matches their directory move verbatim (a plain rename); the
            // rest are delegated, per distinct declared package, to MovePackagePlanner so package declarations, imports,
            // static imports, FQNs, module-info, and resource references are all rewritten and javac-validated. This
            // REUSES the existing package-rename machinery rather than duplicating it.
            return planWithRecomputedPackages(fields, matched, sourceRootText, targetRootText, targetRoot,
                    owningSourceSet, edits, warnings, semanticTarget);
        }
    }

    /** A matched moved file: its path, declared package, directory-implied package, and project-relative old/new paths. */
    private record MatchedFile(Path file, String declaredPackage, String directoryPackage,
                               String relativeOld, String relativeNew) {
    }

    /**
     * §6.2 step 6 ({@code preservePackageNames=false}): builds the move by recomputing each moved file's package from
     * its directory and delegating to {@link MovePackagePlanner}. Files already declaring their directory-implied
     * package move verbatim (plain rename, no edit); for every distinct declared package that does NOT match its
     * directory, a single {@link MovePackagePlanner#planStep} call (with {@code includeSubpackages=false}, so each
     * subpackage maps to its OWN directory-derived target) contributes the package-declaration edit, file relocation
     * under the target source root, and all reference / module-info / resource rewrites.
     */
    private io.serena.javarefactor.v3.transformation.TransformationStep planWithRecomputedPackages(
            Map<String, Object> fields, List<MatchedFile> matched, String sourceRootText, String targetRootText,
            Path targetRoot, String owningSourceSet, List<PlannerSupport.TextEdit> edits, List<String> warnings,
            String semanticTarget) throws IOException {
        // A declared package must map consistently to a single directory-implied package across all its matched files;
        // otherwise the renaming is ambiguous (a non-standard layout where one package lives in two directories).
        Map<String, String> packageMapping = new LinkedHashMap<>();
        for (MatchedFile mf : matched) {
            String existing = packageMapping.putIfAbsent(mf.declaredPackage(), mf.directoryPackage());
            if (existing != null && !existing.equals(mf.directoryPackage())) {
                throw new Refusal("malformed_move_source_root",
                        "preservePackageNames=false cannot recompute package '" + mf.declaredPackage()
                                + "': its files map to more than one directory-implied package ('" + existing + "' and '"
                                + mf.directoryPackage() + "'); the source layout does not mirror its packages.");
            }
        }

        List<FileOperation> fileOperations = new ArrayList<>();
        int relocatedVerbatim = 0;
        int renamedPackages = 0;
        for (MatchedFile mf : matched) {
            if (mf.declaredPackage().equals(mf.directoryPackage())) {
                // Declaration already matches its directory: nothing to rewrite, just relocate the file.
                fileOperations.add(FileOperation.rename(
                        mf.relativeOld(), mf.relativeNew(), PlannerSupport.sha256(mf.file())));
                relocatedVerbatim++;
            }
        }

        // Delegate, per distinct declared package that needs a rename, to MovePackagePlanner.
        for (Map.Entry<String, String> entry : packageMapping.entrySet()) {
            String declaredPackage = entry.getKey();
            String directoryPackage = entry.getValue();
            if (declaredPackage.equals(directoryPackage)) {
                continue;
            }
            Map<String, Object> moveFields = new HashMap<>();
            moveFields.put("sourcePackage", declaredPackage);
            moveFields.put("targetPackage", directoryPackage);
            moveFields.put("targetSourceRoot", targetRootText);
            // includeSubpackages=false: each package maps to its own directory-derived target, so subpackages are
            // handled by their own mapping entry rather than by a parent prefix-swap.
            moveFields.put("includeSubpackages", false);
            io.serena.javarefactor.v3.transformation.TransformationStep step =
                    new MovePackagePlanner(projectRoot, model, policy).planStep(moveFields);
            edits.addAll(step.edits());
            fileOperations.addAll(step.fileOperations());
            warnings.addAll(step.warnings());
            renamedPackages++;
        }

        warnings.add("moveSourceRoot (preservePackageNames=false) relocates files from source root '" + sourceRootText
                + "' to '" + targetRootText + "', recomputing " + renamedPackages
                + " package declaration(s) from the directory mapping and moving " + relocatedVerbatim
                + " file(s) whose package already matched their directory; imports, fully-qualified references, "
                + "module-info, and resources are rewritten and validated.");
        warnings.add(PlannerSupport.reflectionResourceCaveat("source root '" + sourceRootText + "'"));
        appendBuildFileWarning(edits, warnings, owningSourceSet, targetRootText);
        return new io.serena.javarefactor.v3.transformation.TransformationStep(
                "moveSourceRoot", edits, fileOperations, warnings, semanticTarget);
    }

    /** Appends the §6.3 build-file-rewrite caveat when an additive source-set registration edit was produced. */
    private static void appendBuildFileWarning(List<PlannerSupport.TextEdit> edits, List<String> warnings,
            String owningSourceSet, String targetRootText) {
        for (PlannerSupport.TextEdit edit : edits) {
            if ("MOVE_SOURCE_ROOT_BUILD_FILE".equals(edit.kind())) {
                warnings.add("rewriteBuildFiles=true: appended an additive '" + owningSourceSet
                        + "' source-set srcDir registration for '" + targetRootText + "' to the module build file; "
                        + "review the build-file edit before applying.");
                return;
            }
        }
    }

    /** The dotted package implied by a file's directory: its relative-within-root parent path with separators as dots. */
    private static String directoryPackage(Path relativeWithinRoot) {
        Path dir = relativeWithinRoot.getParent();
        if (dir == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dir.getNameCount(); i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(dir.getName(i).toString());
        }
        return sb.toString();
    }

    private static boolean packageRequested(String declared, Set<String> requested, boolean includeSubpackages) {
        if (requested.contains(declared)) {
            return true;
        }
        if (!includeSubpackages) {
            return false;
        }
        for (String pkg : requested) {
            if (declared.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> parsePackages(Object value) {
        Set<String> packages = new LinkedHashSet<>();
        if (value == null) {
            return packages;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                addPackage(packages, String.valueOf(element));
            }
            return packages;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.equals("[]")) {
            return packages;
        }
        // Accept either a JSON array literal ["a.b","c.d"] or a comma-separated list a.b, c.d.
        text = text.replaceAll("^\\[|\\]$", "");
        for (String raw : text.split(",")) {
            addPackage(packages, raw.replace("\"", ""));
        }
        return packages;
    }

    private void addPackage(Set<String> packages, String raw) {
        String name = raw.trim();
        if (name.isEmpty()) {
            return;
        }
        if (!DOTTED_NAME.matcher(name).matches()) {
            throw new Refusal("malformed_move_source_root", "Invalid package name in 'packages': '" + name + "'.");
        }
        packages.add(name);
    }

    /** Name of the source set whose configured source roots include {@code root}, or {@code null} if none does. */
    private String configuredSourceSetName(Path root) {
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path sourceRoot : sourceSet.sourceRoots()) {
                if (sourceRoot.toAbsolutePath().normalize().equals(root)) {
                    return sourceSet.name();
                }
            }
        }
        return null;
    }

    /**
     * Builds the additive build-file edit that registers {@code targetRoot} as a source root of the source set named
     * {@code sourceSetName} (the §6.3 guarded build-file rewrite). For a Gradle module the block is appended at
     * end-of-file and only ADDS a {@code srcDir} (Gradle merges repeated {@code sourceSets} blocks and {@code srcDir}
     * accumulates); for a Maven module it adds an additive {@code build-helper-maven-plugin}
     * {@code add-source}/{@code add-test-source} goal binding (inserting the plugin/execution or extending an existing
     * one's {@code <sources>}), so existing build configuration is never rewritten. Refuses with
     * {@code build_file_rewrite_unsupported} only for a genuinely unsupported build-file shape (no Gradle/Maven build file
     * found, or a malformed/unparseable POM), rather than silently producing an edit the build cannot use.
     */
    private PlannerSupport.TextEdit buildSourceSetRegistration(Path sourceRoot, Path targetRoot, String sourceSetName)
            throws IOException {
        Path buildFile = locateBuildFile(sourceRoot);
        String fileName = buildFile.getFileName().toString();
        if (fileName.equals("pom.xml")) {
            return buildMavenAddSourceEdit(buildFile, targetRoot, sourceSetName);
        }
        Path moduleDir = buildFile.getParent();
        String relativeDir = moduleDir.relativize(targetRoot).toString().replace('\\', '/');
        boolean kotlinDsl = fileName.endsWith(".kts");
        String block = kotlinDsl
                ? "\nsourceSets {\n    named(\"" + sourceSetName + "\") {\n        java {\n            srcDir(\""
                        + relativeDir + "\")\n        }\n    }\n}\n"
                : "\nsourceSets {\n    " + sourceSetName + " {\n        java {\n            srcDir('"
                        + relativeDir + "')\n        }\n    }\n}\n";
        int endOfFile = Files.readString(buildFile).length();
        return new PlannerSupport.TextEdit(buildFile, endOfFile, endOfFile, block, "MOVE_SOURCE_ROOT_BUILD_FILE");
    }

    /**
     * Builds the additive Maven {@code build-helper-maven-plugin} edit that registers {@code targetRoot} as an extra
     * source root via the {@code add-source} (main) / {@code add-test-source} (test) goal — the additive Maven analogue of
     * the Gradle {@code srcDir} registration. The edit is computed against the parsed POM (so a malformed file is refused
     * rather than corrupted) and applied as a formatting-preserving text edit on the raw bytes:
     *
     * <ul>
     *   <li>when the POM already declares a {@code build-helper-maven-plugin} execution bound to the matching goal, a
     *       single {@code <source>} element is appended inside that execution's existing {@code <sources>} (extending the
     *       binding rather than duplicating the plugin);</li>
     *   <li>otherwise a complete {@code <plugin>} block is inserted before the closing {@code </plugins>} of an existing
     *       {@code <build>} block, or a fresh {@code <build><plugins>…</plugins></build>} is inserted before
     *       {@code </project>}.</li>
     * </ul>
     *
     * The {@code targetRoot} is recorded module-relative with forward slashes (Maven's POM-relative convention). A POM
     * whose shape is genuinely unsupported (not well-formed, no {@code <project>} root, or an existing build-helper
     * execution we cannot extend safely) is refused with {@code build_file_rewrite_unsupported}.
     */
    private PlannerSupport.TextEdit buildMavenAddSourceEdit(Path pom, Path targetRoot, String sourceSetName)
            throws IOException {
        String content = Files.readString(pom);
        org.w3c.dom.Document document = parsePom(pom, content);
        org.w3c.dom.Element project = document.getDocumentElement();
        if (project == null || !"project".equals(project.getLocalName() != null
                ? project.getLocalName() : project.getNodeName())) {
            throw new Refusal("build_file_rewrite_unsupported",
                    "Build file '" + PlannerSupport.relative(projectRoot, pom) + "' is not a recognizable Maven POM"
                            + " (<project> root); cannot register the target source root.");
        }
        Path moduleDir = pom.getParent();
        String relativeDir = moduleDir.relativize(targetRoot).toString().replace('\\', '/');
        // A test source root binds add-test-source; a main source root binds add-source. The owning source set name
        // ("test", or a name containing "test") selects the goal so the registration lands in the right scope.
        boolean testScope = sourceSetName != null
                && sourceSetName.toLowerCase(java.util.Locale.ROOT).contains("test");
        String goal = testScope ? "add-test-source" : "add-source";
        String newline = content.indexOf("\r\n") >= 0 ? "\r\n" : "\n";

        // Case A: extend an existing build-helper-maven-plugin execution already bound to this goal.
        int[] sourcesClose = existingBuildHelperSourcesClose(content, goal);
        if (sourcesClose != null) {
            String indent = indentOf(content, sourcesClose[0]);
            String insertion = indent + "    <source>" + relativeDir + "</source>" + newline + indent;
            return new PlannerSupport.TextEdit(pom, sourcesClose[0], sourcesClose[0], insertion,
                    "MOVE_SOURCE_ROOT_BUILD_FILE");
        }

        // Case B: insert a full <plugin> block. Prefer an existing <build><plugins>; else create the structure.
        String plugin = buildHelperPluginBlock(goal, relativeDir, newline);
        int pluginsClose = indexOfClose(content, "</plugins>");
        if (pluginsClose >= 0) {
            String indent = indentOf(content, pluginsClose);
            String insertion = indentBlock(plugin, indent + "    ", newline) + newline + indent;
            return new PlannerSupport.TextEdit(pom, pluginsClose, pluginsClose, insertion,
                    "MOVE_SOURCE_ROOT_BUILD_FILE");
        }
        int buildClose = indexOfClose(content, "</build>");
        if (buildClose >= 0) {
            String indent = indentOf(content, buildClose);
            String block = indent + "    <plugins>" + newline
                    + indentBlock(plugin, indent + "        ", newline) + newline
                    + indent + "    </plugins>" + newline + indent;
            return new PlannerSupport.TextEdit(pom, buildClose, buildClose, block, "MOVE_SOURCE_ROOT_BUILD_FILE");
        }
        int projectClose = indexOfClose(content, "</project>");
        if (projectClose < 0) {
            throw new Refusal("build_file_rewrite_unsupported",
                    "Maven POM '" + PlannerSupport.relative(projectRoot, pom) + "' has no closing </project>;"
                            + " cannot register the target source root.");
        }
        String indent = indentOf(content, projectClose);
        String block = indent + "    <build>" + newline
                + indent + "        <plugins>" + newline
                + indentBlock(plugin, indent + "            ", newline) + newline
                + indent + "        </plugins>" + newline
                + indent + "    </build>" + newline + indent;
        return new PlannerSupport.TextEdit(pom, projectClose, projectClose, block, "MOVE_SOURCE_ROOT_BUILD_FILE");
    }

    /** Parses {@code content} as a secure XML document, or refuses {@code build_file_rewrite_unsupported} if malformed. */
    private org.w3c.dom.Document parsePom(Path pom, String content) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(content)));
        } catch (Exception malformed) {
            throw new Refusal("build_file_rewrite_unsupported",
                    "Maven POM '" + PlannerSupport.relative(projectRoot, pom) + "' is not well-formed XML ("
                            + malformed.getClass().getSimpleName() + "); cannot register the target source root.");
        }
    }

    /**
     * The offset of the {@code </sources>} closing tag of an existing {@code build-helper-maven-plugin} execution bound to
     * {@code goal} (so a new {@code <source>} can be appended inside it), as {@code [closeOffset]}, or {@code null} when no
     * such existing binding is present (so a new plugin/execution must be inserted instead). Refuses
     * {@code build_file_rewrite_unsupported} when the plugin is bound to the goal but has no {@code <sources>} container we
     * can extend (a shape we will not silently rewrite).
     */
    private int[] existingBuildHelperSourcesClose(String content, String goal) {
        int pluginIdx = content.indexOf("build-helper-maven-plugin");
        while (pluginIdx >= 0) {
            // Bound the search to this plugin's element so a sibling plugin's goal/sources is never mis-attributed.
            int pluginStart = content.lastIndexOf("<plugin>", pluginIdx);
            int pluginEnd = content.indexOf("</plugin>", pluginIdx);
            if (pluginStart >= 0 && pluginEnd >= 0) {
                String pluginXml = content.substring(pluginStart, pluginEnd);
                if (pluginXml.contains("<goal>" + goal + "</goal>")) {
                    int sourcesOpen = content.indexOf("<sources>", pluginIdx);
                    int sourcesClose = sourcesOpen >= 0 ? content.indexOf("</sources>", sourcesOpen) : -1;
                    if (sourcesOpen < 0 || sourcesClose < 0 || sourcesClose > pluginEnd) {
                        throw new Refusal("build_file_rewrite_unsupported",
                                "build-helper-maven-plugin is bound to '" + goal + "' but declares no <sources> we can"
                                        + " extend; register the target source root manually.");
                    }
                    return new int[] {sourcesClose};
                }
            }
            pluginIdx = content.indexOf("build-helper-maven-plugin", pluginIdx + 1);
        }
        return null;
    }

    /** A complete {@code build-helper-maven-plugin} {@code <plugin>} block binding {@code goal} for {@code relativeDir}. */
    private static String buildHelperPluginBlock(String goal, String relativeDir, String newline) {
        return "<plugin>" + newline
                + "    <groupId>org.codehaus.mojo</groupId>" + newline
                + "    <artifactId>build-helper-maven-plugin</artifactId>" + newline
                + "    <executions>" + newline
                + "        <execution>" + newline
                + "            <id>add-" + goal + "-" + sanitizeId(relativeDir) + "</id>" + newline
                + "            <phase>generate-sources</phase>" + newline
                + "            <goals>" + newline
                + "                <goal>" + goal + "</goal>" + newline
                + "            </goals>" + newline
                + "            <configuration>" + newline
                + "                <sources>" + newline
                + "                    <source>" + relativeDir + "</source>" + newline
                + "                </sources>" + newline
                + "            </configuration>" + newline
                + "        </execution>" + newline
                + "    </executions>" + newline
                + "</plugin>";
    }

    /** A safe execution-id token derived from a relative directory (non-alphanumeric runs collapsed to a dash). */
    private static String sanitizeId(String relativeDir) {
        String id = relativeDir.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        return id.isEmpty() ? "source" : id;
    }

    /** Re-indents every line of {@code block} with {@code indent} (the first line included), joined by {@code newline}. */
    private static String indentBlock(String block, String indent, String newline) {
        String[] lines = block.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append(newline);
            }
            sb.append(indent).append(lines[i]);
        }
        return sb.toString();
    }

    /** The leading horizontal whitespace of the line containing {@code offset} (its indent), for matching formatting. */
    private static String indentOf(String content, int offset) {
        int lineStart = content.lastIndexOf('\n', offset - 1) + 1;
        int i = lineStart;
        while (i < content.length() && (content.charAt(i) == ' ' || content.charAt(i) == '\t')) {
            i++;
        }
        return content.substring(lineStart, i);
    }

    /** The offset of the start of the LAST occurrence of {@code closeTag} in {@code content}, or -1 if absent. */
    private static int indexOfClose(String content, String closeTag) {
        return content.lastIndexOf(closeTag);
    }

    /**
     * Walks up from {@code sourceRoot} (bounded by the project root) to the nearest Gradle build file or Maven POM,
     * returning whichever build file owns the module. Refuses {@code build_file_rewrite_unsupported} only when neither a
     * Gradle build file nor a {@code pom.xml} is found at or above the source root.
     */
    private Path locateBuildFile(Path sourceRoot) {
        Path dir = sourceRoot;
        while (dir != null && dir.startsWith(projectRoot)) {
            Path groovy = dir.resolve("build.gradle");
            Path kotlin = dir.resolve("build.gradle.kts");
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(groovy)) {
                return groovy;
            }
            if (Files.isRegularFile(kotlin)) {
                return kotlin;
            }
            if (Files.isRegularFile(pom)) {
                return pom;
            }
            dir = dir.getParent();
        }
        throw new Refusal("build_file_rewrite_unsupported",
                "No Gradle build file (build.gradle or build.gradle.kts) or Maven pom.xml was found at or above source"
                        + " root '" + PlannerSupport.relative(projectRoot, sourceRoot)
                        + "'; cannot register the target source root.");
    }

    private List<Path> filesUnder(Path sourceRoot) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                Path normalized = file.toAbsolutePath().normalize();
                if (normalized.startsWith(sourceRoot)) {
                    files.add(normalized);
                }
            }
        }
        return new ArrayList<>(files);
    }

    private static String declaredPackage(String source) {
        Matcher matcher = PACKAGE_DECL.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean boolValue(Map<String, Object> fields, String name, boolean fallback) {
        Object value = fields.get(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private static String required(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new Refusal("malformed_move_source_root", name + " is required.");
    }

    private static final class Refusal extends RuntimeException implements CodedRefusal {
        private final String code;

        private Refusal(String code, String message) {
            super(message);
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }
}
