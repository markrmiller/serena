package io.serena.javarefactor.rename;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.serena.javarefactor.edits.PlannerSupport.refusalJson;
import static io.serena.javarefactor.edits.PlannerSupport.relative;
import static io.serena.javarefactor.edits.PlannerSupport.sha256;
import static io.serena.javarefactor.edits.PlannerSupport.simpleName;

public final class SemanticRenamePlanner {
    public String plan(JavaProjectModel model, String relativePath, long line, long column, String newName) throws IOException {
        return plan(model, relativePath, line, column, newName, false, false, TargetHints.NONE);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, String newName,
                boolean includeJavadocs, boolean includeComments) throws IOException {
        return plan(model, relativePath, line, column, newName, includeJavadocs, includeComments, TargetHints.NONE);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, String newName,
                boolean includeJavadocs, boolean includeComments, TargetHints hints) throws IOException {
        validateName(newName);
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            RefactorAnalysisResult analysis = index.resolveTarget(relativePath, line, column, hints.nameHint());
            if (analysis.target() == null) {
                return refusalJson("target_not_found", "No refactorable Java symbol was found at the requested position.");
            }
            // Target-identity gate (before every other check): the caller named a symbol; prove the position-resolved
            // element IS that symbol before planning any edit for it.
            String hintMismatch = hints.mismatch(analysis.target());
            if (hintMismatch != null) {
                return refusalJson("target_mismatch", "Refused to plan a rename against an unverified target: " + hintMismatch);
            }
            // Centralized target-origin editability gate (before op-specific logic): refuse generated roots, external/
            // out-of-tree source attachments, and classpath-only binary targets.
            String originRefusal = index.targetOriginRefusal(analysis.target());
            if (originRefusal != null) {
                return refusalJson("non_editable_target", originRefusal);
            }
            String oldName = simpleName(analysis.target().key().name());
            if (oldName.equals(newName)) {
                return refusalJson("same_name", "New name matches the selected symbol name.");
            }
            Element targetElement = analysis.target().element();
            if (targetElement.getKind() == ElementKind.CONSTRUCTOR) {
                return refusalJson(
                        "unsupported_constructor_rename",
                        "Renaming a constructor directly is not supported: rename the enclosing class/type instead, which "
                                + "updates the constructor declarations and all constructor calls atomically.");
            }
            if (targetElement.getKind() == ElementKind.PARAMETER && index.parameterRenameTouchesExternalHierarchy(targetElement)) {
                return refusalJson(
                        "parameter_hierarchy_external_declaration",
                        "Cannot safely rename this parameter across the full hierarchy because one related declaration is "
                                + "defined by a JDK or dependency method outside the editable project sources.");
            }
            List<Element> group = targetElement.getKind() == ElementKind.PARAMETER
                    ? index.parameterRenameTargets(targetElement)
                    : index.overrideGroup(targetElement);
            if (index.overridesLibraryMethod(group)) {
                return refusalJson(
                        "overrides_library_method",
                        "Cannot rename a method that overrides a JDK or dependency method; the external declaration cannot be updated.");
            }
            String conflict = index.detectRenameConflict(targetElement, group, newName);
            if (conflict != null) {
                return refusalJson("name_conflict", conflict);
            }
            // The home-task group above does not include override declarations that live in other source-set tasks
            // (e.g. a test subclass overriding a main method). Those declarations are rewritten by the cross-task
            // rename too, so they must receive the same duplicate-member / same-arity-overload / inherited-conflict
            // analysis on THEIR declaring class, evaluated with that task's element/type universe.
            String crossTaskConflict = index.detectCrossTaskRenameConflict(targetElement, newName);
            if (crossTaskConflict != null) {
                return refusalJson("name_conflict", crossTaskConflict);
            }
            String fileConflict = detectFileRenameConflict(model.projectRoot(), analysis.target(), oldName, newName);
            if (fileConflict != null) {
                return refusalJson("name_conflict", fileConflict);
            }
            List<IdentifierSpan> references = collectReferences(index, group, targetElement);
            if (targetElement instanceof TypeElement type) {
                String importConflict = index.detectImportCollision(type, references, newName);
                if (importConflict != null) {
                    return refusalJson("name_conflict", importConflict);
                }
            }
            FileRename fileRename = topLevelFileRename(model.projectRoot(), analysis.target(), oldName, newName);
            String nonEditable = index.detectNonEditableEditTarget(
                    references, fileRename == null ? null : new SemanticIndex.FileRenameView(fileRename.oldPath()));
            if (nonEditable != null) {
                return refusalJson("non_editable_target", nonEditable);
            }
            List<String> warnings = new ArrayList<>();
            if (includeJavadocs) {
                List<IdentifierSpan> docSpans;
                if (targetElement.getKind() == ElementKind.PARAMETER) {
                    docSpans = new ArrayList<>();
                    Set<String> parameterKeys = index.parameterRenameTargetKeys(targetElement);
                    for (Element parameterTarget : index.parameterRenameTargets(targetElement)) {
                        docSpans = mergeSpans(
                                docSpans,
                                index.javadocReferenceSpans(parameterKeys, oldName, parameterTarget));
                    }
                } else if (index.isRecordComponentBackedField(targetElement)) {
                    docSpans = index.javadocReferenceSpans(index.recordComponentRenameTargetKeys(targetElement), null, null);
                } else {
                    docSpans = index.javadocReferenceSpans(index.renameTargetKeys(group), null, null);
                }
                references = mergeSpans(references, docSpans);
            }
            if (includeComments) {
                List<IdentifierSpan> textualSpans = index.textualOccurrenceSpans(oldName);
                if (!textualSpans.isEmpty()) {
                    references = mergeSpans(references, textualSpans);
                    warnings.add("include_comments rewrote " + textualSpans.size() + " textual occurrence(s) of '"
                            + oldName + "' in comments/string literals; these are heuristic (whole-token text matches), "
                            + "not semantic references — review before applying.");
                }
            }
            // Safety surface (warning-only): incomplete-classpath/annotation-processing caveats, plus a reflection/
            // resource caveat when a type's name changes (Class.forName, ServiceLoader, Spring/XML/serialization, etc.).
            warnings.addAll(PlannerSupport.modelSafetyWarnings(model));
            if (targetElement instanceof TypeElement) {
                warnings.add(PlannerSupport.reflectionResourceCaveat("type '" + oldName + "'"));
            }
            return workspaceEditJson(model.projectRoot(), analysis, references, fileRename, newName, warnings);
        }
    }

    /** Merges two ordered span lists, deduplicating by file/offset and re-ordering by file then start offset. */
    private static List<IdentifierSpan> mergeSpans(List<IdentifierSpan> base, List<IdentifierSpan> extra) {
        Map<String, IdentifierSpan> deduped = new LinkedHashMap<>();
        for (IdentifierSpan span : base) {
            deduped.putIfAbsent(span.file() + ":" + span.startOffset() + ":" + span.endOffset(), span);
        }
        for (IdentifierSpan span : extra) {
            deduped.putIfAbsent(span.file() + ":" + span.startOffset() + ":" + span.endOffset(), span);
        }
        return deduped.values().stream()
                .sorted(Comparator.comparing((IdentifierSpan span) -> span.file().toString()).thenComparingLong(IdentifierSpan::startOffset))
                .toList();
    }

    private static String workspaceEditJson(Path projectRoot, RefactorAnalysisResult analysis, List<IdentifierSpan> references, FileRename fileRename, String newName, List<String> warnings) throws IOException {
        // Every reference (including the declaration's own span) is rewritten in place under its CURRENT path. For a
        // top-level type rename the declaration file is renamed by the file operation AFTER these text edits are staged,
        // so the edits target the old path and the rename moves the already-edited content to the new path.
        List<PlannerSupport.TextEdit> edits = references.stream()
                .map(span -> new PlannerSupport.TextEdit(span.file(), span.startOffset(), span.endOffset(), newName,
                        span.startOffset() == analysis.target().span().startOffset() && span.file().equals(analysis.target().span().file())
                                ? "DECLARATION" : "REFERENCE"))
                .toList();
        String changes = PlannerSupport.changesJson(projectRoot, edits);
        String fileOperations = fileRename == null ? "[]" : "[" + PlannerSupport.renameFileOp(projectRoot, fileRename.oldPath(), fileRename.newPath()) + "]";
        long touchedFileCount = references.stream().map(IdentifierSpan::file).distinct().count();
        String warningsJson = warnings.stream().map(JsonUtil::quote).collect(Collectors.joining(",", "[", "]"));
        return "{"
                + "\"accepted\":true,"
                + "\"target\":" + analysis.targetJson(projectRoot) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + changes + ","
                + "\"fileOperations\":" + fileOperations + ","
                + "\"warnings\":" + warningsJson + ","
                + "\"preconditions\":[\"post-javac validation required before apply\"],"
                + "\"stats\":{\"editCount\":" + references.size() + ",\"fileOperationCount\":" + (fileRename == null ? 0 : 1) + ",\"touchedFileCount\":" + touchedFileCount + "}"
                + "},"
                + "\"stats\":{\"editCount\":" + references.size() + ",\"fileOperationCount\":" + (fileRename == null ? 0 : 1) + "}"
                + "}";
    }

    private static FileRename topLevelFileRename(Path projectRoot, ResolvedTarget target, String oldName, String newName) {
        String kind = target.key().kind();
        if (!Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE").contains(kind)) {
            return null;
        }
        String oldPath = relative(projectRoot, target.span().file());
        Path fileName = Path.of(oldPath).getFileName();
        if (fileName == null || !fileName.toString().equals(oldName + ".java")) {
            return null;
        }
        Path parent = Path.of(oldPath).getParent();
        String newFile = newName + ".java";
        return new FileRename(oldPath, parent == null ? newFile : parent.resolve(newFile).toString().replace('\\', '/'));
    }

    /**
     * Collects every rename edit span: references to all members of the override/implementation group, plus — for a
     * type rename — the declaration spans of that type's constructors (whose declared name is the type name). The
     * combined list is deduplicated by file/offset and ordered.
     */
    private static List<IdentifierSpan> collectReferences(SemanticIndex index, List<Element> group, Element targetElement) {
        // scanReferences already deduplicates by file/offset and orders the result; only operation-specific multi-key
        // surfaces (hierarchy parameters, record components, type constructors) need widening or re-merging here.
        if (targetElement.getKind() == ElementKind.PARAMETER) {
            return index.scanReferencesForParameterRename(targetElement);
        }
        if (index.isRecordComponentBackedField(targetElement)) {
            return index.scanReferencesForRecordComponentRename(targetElement);
        }
        if (!(targetElement instanceof TypeElement type)) {
            return index.scanReferencesForRename(group);
        }
        List<IdentifierSpan> references = new ArrayList<>(index.scanReferencesForRename(group));
        references.addAll(index.constructorDeclarationSpans(type));
        Map<String, IdentifierSpan> deduped = new LinkedHashMap<>();
        for (IdentifierSpan span : references) {
            deduped.putIfAbsent(span.file() + ":" + span.startOffset() + ":" + span.endOffset(), span);
        }
        return deduped.values().stream()
                .sorted(Comparator.comparing((IdentifierSpan span) -> span.file().toString()).thenComparingLong(IdentifierSpan::startOffset))
                .toList();
    }

    private static String detectFileRenameConflict(Path projectRoot, ResolvedTarget target, String oldName, String newName) {
        FileRename fileRename = topLevelFileRename(projectRoot, target, oldName, newName);
        if (fileRename != null && Files.exists(projectRoot.resolve(fileRename.newPath()))) {
            return "Top-level type rename target file already exists: " + fileRename.newPath();
        }
        return null;
    }

    private static void validateName(String newName) {
        if (newName == null || !SourceVersion.isIdentifier(newName) || SourceVersion.isKeyword(newName)) {
            throw new IllegalArgumentException("Invalid Java identifier for rename: " + newName);
        }
    }

    private record FileRename(String oldPath, String newPath) {
    }
}
