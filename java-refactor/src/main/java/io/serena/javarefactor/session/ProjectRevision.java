package io.serena.javarefactor.session;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable revision guard for planned Java refactor revision for session preview/status payloads.
 *
 * <p>The model hash covers source roots, generated roots, compiler options, dependency paths, and discovery warnings via
 * {@link JavaProjectModel#toJson()}. The source hashes cover exactly the files touched by the planned edit, so a
 * preview can be revalidated just before Python applies it without reusing stale source content.</p>
 */
public record ProjectRevision(
        String modelHash,
        Map<String, String> sourceHashes,
        Instant createdAt,
        Map<String, String> invalidationInputs,
        String derivedModelDigest) {
    public ProjectRevision {
        sourceHashes = Map.copyOf(sourceHashes);
        invalidationInputs = Map.copyOf(invalidationInputs);
    }

    /**
     * Captures the current model and touched-file hashes.
     *
     * @param model the discovered project model used by the planner
     * @param touchedFiles project-relative files whose content/file operation affects the planned edit
     * @return the captured project revision
     */
    public static ProjectRevision capture(JavaProjectModel model, List<String> touchedFiles) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (String relative : touchedFiles) {
            Path path = model.projectRoot().resolve(relative).normalize();
            if (Files.isRegularFile(path)) {
                hashes.put(relative, PlannerSupport.sha256(path));
            } else {
                hashes.put(relative, "<missing>");
            }
        }
        return new ProjectRevision(
                sha256(model.toJson()),
                hashes,
                Instant.now(),
                invalidationInputs(model, touchedFiles),
                model.revisionDigest());
    }

    /**
     * The first mismatch against a fresh revision, or {@code null} when they match.
     *
     * @param current the freshly captured revision
     * @return a human-readable mismatch reason
     */
    public String mismatch(ProjectRevision current) {
        if (!modelHash.equals(current.modelHash())) {
            return "project model changed";
        }
        if (!invalidationInputs.equals(current.invalidationInputs())) {
            return "revision invalidation inputs changed";
        }
        for (Map.Entry<String, String> entry : sourceHashes.entrySet()) {
            String currentHash = current.sourceHashes().get(entry.getKey());
            if (!entry.getValue().equals(currentHash)) {
                return "source file changed: " + entry.getKey();
            }
        }
        return null;
    }

    /**
     * The full incremental-apply revision guard (G002). Enforces the entire project-revision token every time, with one
     * narrow, explicit exemption: source files an earlier acknowledged, successfully-committed partial apply already
     * mutated ({@code exemptSourcePaths}) are not required to match their create-time content (we changed them on
     * purpose). Everything else must match.
     *
     * <p>The opaque model hash ({@code invalidationInputs}'s {@code modelHash} entry, and the record's {@code modelHash})
     * is deliberately NOT compared here: {@link JavaProjectModel#toJson()} folds in the project's {@code .java} file
     * INVENTORY (file counts and per-source-set file lists), so a legitimate earlier partial apply that CREATES a file
     * (e.g. extract-interface's new interface) shifts the model hash mid-session even though no external/config drift
     * occurred. Comparing the model hash would therefore wrongly reject benign multi-step incremental applies. Instead
     * every inventory-INDEPENDENT correctness input is captured explicitly in {@code invalidationInputs} — source roots,
     * generated roots, generated-source content digest, classpath, compiler options, and a build-file content digest —
     * and compared individually, so generated-root, build-file, compiler-arg, classpath, and source-root drift are all
     * refused. The per-file source hashes (minus the explicit exemptions) then prove the bytes this subset writes over
     * are still the bytes the preview was planned against.</p>
     *
     * <p>G003: because the explicit {@code invalidationInputs} are a hand-curated allowlist, a correctness field present
     * in {@link JavaProjectModel#toJson()} but absent from that list (e.g. {@code discoveryKind}, {@code modulePath},
     * {@code outputDirs}) could previously drift between create and apply without being refused. The mechanically
     * derived model digest ({@link JavaProjectModel#revisionDigest()}) is compared last as a catch-all: it hashes the
     * entire model field set minus only the inventory and volatile analysis outputs, so every other field is enforced
     * by default and the silent-escape gap is closed.</p>
     */
    public String mismatchFull(ProjectRevision current, java.util.Set<String> exemptSourcePaths) {
        for (Map.Entry<String, String> entry : invalidationInputs.entrySet()) {
            if (entry.getKey().equals("modelHash")) {
                continue;
            }
            if (!entry.getValue().equals(current.invalidationInputs().get(entry.getKey()))) {
                return "revision invalidation input changed: " + entry.getKey();
            }
        }
        for (String key : current.invalidationInputs().keySet()) {
            if (key.equals("modelHash") || invalidationInputs.containsKey(key)) {
                continue;
            }
            return "revision invalidation input changed: " + key;
        }
        for (Map.Entry<String, String> entry : sourceHashes.entrySet()) {
            if (exemptSourcePaths.contains(entry.getKey())) {
                continue;
            }
            String currentHash = current.sourceHashes().get(entry.getKey());
            if (!entry.getValue().equals(currentHash)) {
                return "source file changed: " + entry.getKey();
            }
        }
        // G003 mechanical catch-all: the granular invalidation inputs above give precise messages for the common
        // drift cases, but they are a hand-curated allowlist. The derived model digest closes that gap by comparing
        // EVERY inventory-independent, non-volatile model field (see JavaProjectModel#revisionDigest) — so a model
        // field that escaped the allowlist (e.g. discoveryKind, modulePath, outputDirs) can no longer drift silently.
        // Checked last so the granular messages win when both fire.
        if (!derivedModelDigest.equals(current.derivedModelDigest())) {
            return "revision invalidation input changed: derivedModelDigest";
        }
        return null;
    }

    /** Serializes the revision for session preview/status payloads. */
    public String toJson() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("modelHash", JsonUtil.quote(modelHash));
        fields.put("sourceHashes", JsonUtil.object(sourceHashJson()));
        fields.put("createdAt", JsonUtil.quote(createdAt.toString()));
        fields.put("invalidationInputs", JsonUtil.object(invalidationInputsJson()));
        return JsonUtil.object(fields);
    }

    private static Map<String, String> invalidationInputs(JavaProjectModel model, List<String> touchedFiles)
            throws IOException {
        Map<String, String> inputs = new TreeMap<>();
        inputs.put("modelHash", sha256(model.toJson()));
        inputs.put("projectRoot", model.projectRoot().normalize().toString());
        inputs.put("touchedFiles", String.join("\n", touchedFiles.stream().sorted().toList()));
        inputs.put("sourceRoots", String.join("\n", model.sourceSets().stream()
                .flatMap(sourceSet -> sourceSet.sourceRoots().stream())
                .map(Path::toString)
                .sorted()
                .toList()));
        inputs.put("generatedRoots", String.join("\n", model.generatedSourceRoots().stream()
                .map(Path::toString)
                .sorted()
                .toList()));
        // Content-sensitive, not just the root-path list: generated .java sources can change CONTENT under a stable
        // generated-root set (e.g. an annotation processor regenerates an API), shifting javac symbol resolution while
        // the model hash and touched-file set are unchanged. Capturing a digest of the generated-source inventory means
        // a preview planned against the old generated API is rejected at apply instead of silently committing.
        inputs.put("generatedSourcesDigest", generatedSourcesDigest(model));
        // Inventory-INDEPENDENT compiler-identity inputs, captured explicitly so the full incremental guard
        // (mismatchFull) can enforce them WITHOUT comparing the inventory-sensitive opaque model hash (G002). These are
        // exactly the correctness inputs that drift independently of which .java files happen to exist: the resolved
        // classpath, the javac options/release-target/encoding/modular flags, and the content of the build files
        // (pom.xml, build.gradle, ...) that define them. A change to any of these invalidates a planned preview.
        inputs.put("classpath", String.join("\n", model.classpath().stream()
                .map(Path::toString)
                .sorted()
                .toList()));
        inputs.put("compilerOptions", compilerOptions(model));
        inputs.put("buildFilesDigest", buildFilesDigest(model));
        return inputs;
    }

    /** A stable string of every compiler-identity option: explicit javac args plus the resolved release/target knobs. */
    private static String compilerOptions(JavaProjectModel model) {
        List<String> options = new java.util.ArrayList<>(model.javacOptions());
        options.add("release=" + model.release());
        options.add("source=" + model.source());
        options.add("target=" + model.target());
        options.add("encoding=" + model.encoding());
        options.add("modular=" + model.modular());
        return String.join("\n", options);
    }

    /**
     * A deterministic digest over the content of every build/invalidation file the model depends on (pom.xml,
     * build.gradle, settings files, ...): a sorted inventory of {@code absolutePath -> sha256(content)} collapsed into a
     * single hash. A build-file edit between session creation and incremental apply (a changed dependency, compiler arg,
     * or source-set definition) changes this digest and invalidates the in-flight session revision.
     */
    private static String buildFilesDigest(JavaProjectModel model) throws IOException {
        TreeMap<String, String> inventory = new TreeMap<>();
        for (Path file : model.invalidationFiles()) {
            Path normalized = file.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                inventory.put(normalized.toString(), PlannerSupport.sha256(normalized));
            } else {
                inventory.put(normalized.toString(), "<missing>");
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            builder.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        return sha256(builder.toString());
    }

    /**
     * A deterministic digest over every generated {@code .java} source under the model's generated roots: a sorted
     * inventory of {@code absolutePath -> sha256(content)} collapsed into a single hash. Non-existent roots (an
     * annotation processor that has not run yet) contribute nothing; when they later materialize, the digest changes
     * and any in-flight session revision is invalidated.
     */
    private static String generatedSourcesDigest(JavaProjectModel model) throws IOException {
        TreeMap<String, String> inventory = new TreeMap<>();
        for (Path root : model.generatedSourceRoots()) {
            Path normalizedRoot = root.normalize();
            if (!Files.isDirectory(normalizedRoot)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(normalizedRoot)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    inventory.put(file.toAbsolutePath().normalize().toString(), PlannerSupport.sha256(file));
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            builder.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        return sha256(builder.toString());
    }

    private Map<String, String> sourceHashJson() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(sourceHashes).entrySet()) {
            fields.put(entry.getKey(), JsonUtil.quote(entry.getValue()));
        }
        return fields;
    }

    private Map<String, String> invalidationInputsJson() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(invalidationInputs).entrySet()) {
            fields.put(entry.getKey(), JsonUtil.quote(entry.getValue()));
        }
        return fields;
    }

    private static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }
}
