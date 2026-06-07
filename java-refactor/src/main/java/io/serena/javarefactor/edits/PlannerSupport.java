package io.serena.javarefactor.edits;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared package-private helpers used by the refactoring planners. These were previously copy-pasted identically across
 * {@code SemanticRenamePlanner}, {@code SafeDeletePlanner}, {@code InlineVariablePlanner}, and
 * {@code MoveTopLevelTypePlanner}; consolidating them here removes the duplication while keeping behavior identical.
 */
public final class PlannerSupport {
    private PlannerSupport() {
    }

    /** The hex SHA-256 of the file's bytes, used as the optimistic-concurrency oldHash on every text edit/file op. */
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    /** The project-root-relative, forward-slash form of an absolute path (the on-the-wire relativePath form). */
    public static String relative(Path projectRoot, Path path) {
        return projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The simple name of a possibly-qualified dotted name (the substring after the last '.'). */
    public static String simpleName(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? name : name.substring(index + 1);
    }

    /** The standard refusal envelope shared by the rename, inline, and move planners. */
    public static String refusalJson(String code, String message) {
        return "{\"accepted\":false,\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message) + "},\"warnings\":[],\"stats\":{}}";
    }

    /**
     * The safe-delete refusal envelope. Conforms to the V1 safe-delete result shape — {@code canDelete:false} and a
     * {@code reason} alongside the blocking {@code references[]} — while preserving the shared {@code accepted:false} +
     * {@code refusal:{code,message}} envelope so existing consumers keep working. {@code reason} mirrors the refusal
     * message.
     */
    public static String refusalJson(String code, String message, String referencesJson) {
        return "{\"accepted\":false,\"canDelete\":false,\"reason\":" + JsonUtil.quote(message)
                + ",\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message) + "}"
                + ",\"references\":" + referencesJson + ",\"warnings\":[],\"stats\":{}}";
    }

    /**
     * Model-level safety warnings shared by every accepted operation (rename, safe delete, move, inline). These surface
     * the cases the safety section requires callers to review before applying: an incomplete classpath (the model
     * degraded to a conventional, classpath-less layout) and annotation-processing-disabled analysis over a project that
     * has generated source roots. Returned as warning-only text; the apply path separately gates degraded models.
     */
    public static List<String> modelSafetyWarnings(JavaProjectModel model) {
        List<String> warnings = new ArrayList<>();
        if (model.conventionalFallbackUsed()) {
            warnings.add("Incomplete analysis: build-model extraction was unavailable, so a classpath-less conventional "
                    + "source layout was used; references that resolve only through the project's compile classpath may be "
                    + "missed. Review this preview before applying.");
        }
        if (!model.generatedSourceRoots().isEmpty() && model.annotationProcessingDisabled()) {
            warnings.add("Annotation processing is disabled for analysis (-proc:none) but the project has generated source "
                    + "roots; references introduced by annotation processors are seen only through already-generated sources "
                    + "on disk and may be incomplete.");
        }
        // V1 incomplete-analysis contract: when javac validation reported unresolved diagnostics and the project did
        // NOT opt in via allow_incomplete_analysis, the operation still produces a warning-only preview but apply is
        // refused (Main.modelGateRefusal). With the opt-in, withCompilerDiagnostics already routes the diagnostics
        // into the model warnings, so no extra caveat is added here.
        if (model.analysisIncomplete() && !model.allowIncompleteAnalysis()) {
            warnings.add("Incomplete analysis: javac reported " + model.compilerDiagnostics().size()
                    + " unresolved diagnostic(s) for this project, so semantic resolution may be incomplete and this "
                    + "preview may miss or misattribute references. Review it carefully; apply is refused unless "
                    + "java_refactor.allow_incomplete_analysis is true.");
        }
        return warnings;
    }

    /**
     * Reflection/resource caveat for operations that change a type/member's name or fully-qualified name. Such external
     * references (reflection, string-based class names, serialization, ServiceLoader/META-INF/services, Spring/XML/resource
     * configuration) are not tracked by the compiler model and may need manual updates.
     */
    public static String reflectionResourceCaveat(String elementDescription) {
        return "Best-effort only: references to " + elementDescription + " from reflection (e.g. Class.forName, "
                + "Method/Field lookups), string-based names, serialization, ServiceLoader/META-INF/services files, or "
                + "Spring/XML/resource configuration are not tracked and may need manual updates.";
    }

    /** Serializes a warning list to a JSON array string. */
    public static String warningsJson(List<String> warnings) {
        return warnings.stream().map(JsonUtil::quote).collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * One planned text edit in the Serena-specific V1 edit model: a half-open {@code [startOffset, endOffset)} range of
     * UTF-16 character offsets in {@code file}, replaced by {@code newText}. {@code kind} is a semantic tag (e.g.
     * {@code DECLARATION}, {@code REFERENCE}, {@code IMPORT}) carried through to the preview; it does not affect how the
     * Python applier applies the edit.
     */
    public record TextEdit(Path file, long startOffset, long endOffset, String newText, String kind) {
    }

    /**
     * Serializes the V1 {@code changes[]} array: edits grouped by file, each group carrying the file's project-relative
     * {@code path} and pre-edit {@code oldSha256} (the Python applier verifies this hash before applying any edit in the
     * group). File groups follow first-appearance order; within a group edits keep their given order (the applier sorts
     * descending and rejects overlap). A file that is also renamed/deleted is keyed here by its CURRENT (old) path so the
     * text edits apply to the existing file before any file operation moves or removes it.
     */
    public static String changesJson(Path projectRoot, List<TextEdit> edits) throws IOException {
        Map<Path, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (TextEdit edit : edits) {
            byFile.computeIfAbsent(edit.file().toAbsolutePath().normalize(), key -> new ArrayList<>()).add(edit);
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<Path, List<TextEdit>> entry : byFile.entrySet()) {
            String path = relative(projectRoot, entry.getKey());
            String oldSha256 = sha256(entry.getKey());
            String editsJson = entry.getValue().stream()
                    .map(edit -> "{\"startOffset\":" + edit.startOffset() + ",\"endOffset\":" + edit.endOffset()
                            + ",\"newText\":" + JsonUtil.quote(edit.newText()) + ",\"kind\":" + JsonUtil.quote(edit.kind()) + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            groups.add("{\"path\":" + JsonUtil.quote(path) + ",\"oldSha256\":" + JsonUtil.quote(oldSha256)
                    + ",\"edits\":" + editsJson + "}");
        }
        return "[" + String.join(",", groups) + "]";
    }

    /**
     * V1 rename file operation: {@code {kind:"rename", oldPath, newPath, oldSha256}} (project-relative paths).
     * {@code oldSha256} is the pre-edit hash of the rename SOURCE file; the Python applier verifies it before any
     * mutation so a file changed between planning and apply refuses the whole edit (optimistic concurrency for
     * destructive file operations, not only text edits).
     */
    public static String renameFileOp(Path projectRoot, String oldRelative, String newRelative) throws IOException {
        String oldSha256 = sha256(projectRoot.toAbsolutePath().normalize().resolve(oldRelative));
        return "{\"kind\":\"rename\",\"oldPath\":" + JsonUtil.quote(oldRelative) + ",\"newPath\":" + JsonUtil.quote(newRelative)
                + ",\"oldSha256\":" + JsonUtil.quote(oldSha256) + "}";
    }

    /**
     * V1 delete file operation: {@code {kind:"delete", path, oldSha256}} (project-relative path). {@code oldSha256} is
     * the pre-edit hash of the file being deleted; for a whole-file delete this is the ONLY hash precondition (there are
     * no text edits carrying one), so the Python applier requires and verifies it before deleting.
     */
    public static String deleteFileOp(Path projectRoot, String relative) throws IOException {
        String oldSha256 = sha256(projectRoot.toAbsolutePath().normalize().resolve(relative));
        return "{\"kind\":\"delete\",\"path\":" + JsonUtil.quote(relative) + ",\"oldSha256\":" + JsonUtil.quote(oldSha256) + "}";
    }

    /** V1 create file operation: {@code {kind:"create", path, content}} (project-relative path). */
    public static String createFileOp(String relative, String content) {
        return "{\"kind\":\"create\",\"path\":" + JsonUtil.quote(relative) + ",\"content\":" + JsonUtil.quote(content) + "}";
    }
}
