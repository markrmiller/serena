package io.serena.javarefactor.move;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.inline.*;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.serena.javarefactor.edits.PlannerSupport.refusalJson;
import static io.serena.javarefactor.edits.PlannerSupport.relative;
import static io.serena.javarefactor.edits.PlannerSupport.sha256;
import static io.serena.javarefactor.edits.PlannerSupport.simpleName;

public final class MoveTopLevelTypePlanner {

    public String plan(JavaProjectModel model, String relativePath, long line, long column, String targetPackage) throws IOException {
        return plan(model, relativePath, line, column, targetPackage, null);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, String targetPackage, String targetDirectory) throws IOException {
        return plan(model, relativePath, line, column, targetPackage, targetDirectory, TargetHints.NONE);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, String targetPackage,
                String targetDirectory, TargetHints hints) throws IOException {
        boolean hasPackage = targetPackage != null;
        boolean hasDirectory = targetDirectory != null;
        if (hasPackage == hasDirectory) {
            return refusalJson("ambiguous_move_target", "Move requires exactly one of targetPackage or targetDirectory.");
        }
        if (relativePath.endsWith("module-info.java")) {
            return refusalJson("unsupported_module_info", "Moving module-info.java package declarations is not supported in v1.");
        }
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            RefactorAnalysisResult analysis = index.resolveTarget(relativePath, line, column, hints.nameHint());
            if (analysis.target() == null || !Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE").contains(analysis.target().key().kind())) {
                return refusalJson("not_top_level_type", "Move requires a top-level Java type target.");
            }
            // Target-identity gate (before every other check): the caller named a symbol; prove the position-resolved
            // element IS that symbol before planning any edit for it.
            String hintMismatch = hints.mismatch(analysis.target());
            if (hintMismatch != null) {
                return refusalJson("target_mismatch", "Refused to plan a move against an unverified target: " + hintMismatch);
            }
            // Centralized target-origin editability gate (before op-specific logic): refuse generated roots, external/
            // out-of-tree source attachments, and classpath-only binary targets.
            String originRefusal = index.targetOriginRefusal(analysis.target());
            if (originRefusal != null) {
                return refusalJson("non_editable_target", originRefusal);
            }
            String typeName = simpleName(analysis.target().key().name());
            Path oldFile = model.projectRoot().resolve(relativePath).toAbsolutePath().normalize();
            if (!oldFile.getFileName().toString().equals(typeName + ".java")) {
                return refusalJson("not_file_backed_top_level_type", "Move requires a file-backed top-level type.");
            }
            // Decide whole-file move vs. extraction. A file whose only top-level declaration is the moved type moves as a
            // whole (rename, preserving file identity). A file with 2+ top-level TYPE declarations (public+companion or
            // multiple public) extracts the selected type into a new file, leaving the others behind (HB-1). A file with a
            // single type but stray non-type trivia (e.g. a top-level ';') is still refused: extraction is for genuine
            // multi-type sources, and whole-file move of trivia-bearing sources stays conservatively out of scope.
            int topLevelTypeCount = index.topLevelTypeDeclarationCount(oldFile);
            boolean extractFromMultiType = topLevelTypeCount > 1;
            if (!extractFromMultiType) {
                String triviaRefusal = index.soleTopLevelTypeRefusal(oldFile, typeName);
                if (triviaRefusal != null) {
                    return refusalJson("multiple_top_level_declarations", triviaRefusal);
                }
            }
            Path newFile;
            String resolvedTargetPackage;
            if (hasDirectory) {
                Path targetDir = model.projectRoot().resolve(targetDirectory).toAbsolutePath().normalize();
                Path sourceRoot = sourceRootContaining(model, targetDir);
                if (sourceRoot == null) {
                    return refusalJson("target_directory_outside_source_root",
                            "target_directory is not under any project source root: " + targetDirectory);
                }
                resolvedTargetPackage = sourceRoot.equals(targetDir)
                        ? ""
                        : sourceRoot.relativize(targetDir).toString().replace('\\', '/').replace('/', '.');
                newFile = targetDir.resolve(typeName + ".java").normalize();
            } else {
                resolvedTargetPackage = targetPackage;
                Path sourceRoot = sourceRootFor(model, oldFile);
                newFile = sourceRoot.resolve(resolvedTargetPackage.replace('.', '/')).resolve(typeName + ".java").normalize();
            }
            validatePackage(resolvedTargetPackage);
            String effectiveTargetPackage = resolvedTargetPackage;
            if (newFile.equals(oldFile)) {
                return refusalJson("same_package", "Move target matches the current location.");
            }
            if (Files.exists(newFile)) {
                return refusalJson("target_exists", "Move target already exists: " + relative(model.projectRoot(), newFile));
            }
            // Duplicate detection across ALL source roots / source sets: refuse when a top-level type with the same
            // simple name already exists in the TARGET package anywhere the project model knows about, not merely when
            // the conventional target file exists on disk (the colliding type may live in another source root).
            if (index.targetPackageHasType(effectiveTargetPackage, typeName, oldFile)) {
                return refusalJson("target_type_exists",
                        "A top-level type named '" + typeName + "' already exists in package '"
                                + (effectiveTargetPackage.isEmpty() ? "<default>" : effectiveTargetPackage)
                                + "' in the project.");
            }
            java.nio.charset.Charset charset = SemanticIndex.charsetOf(model);
            String oldPackage = index.packageNameOf(oldFile);
            String moduleRefusal = moduleInfoRefusal(index, model, oldPackage, typeName);
            if (moduleRefusal != null) {
                return moduleRefusal;
            }
            // Moving INTO the default package: default-package types cannot be imported, so any unqualified reference the
            // moved file makes to a former same-package sibling becomes unresolvable with no import able to fix it. That
            // would produce an accepted preview that always fails on apply, so refuse up front (M6).
            if (effectiveTargetPackage.isEmpty() && !oldPackage.isEmpty()
                    && !index.referencedTypeFqnsInPackage(oldFile, oldPackage, analysis.target().element()).isEmpty()) {
                return refusalJson("move_to_default_package_breaks_references",
                        "Move refuses relocating a type into the default package while it references former same-package "
                                + "siblings unqualified: default-package types cannot be imported, so those references "
                                + "would not compile. Qualify or restructure those references first.");
            }
            // Moving INTO the default package: a default-package type cannot be imported, statically imported, fully
            // qualified, or referenced at all from a compilation unit in a NAMED package (JLS 7.5: a type-import
            // declaration must name a type in a named package; there is no qualified name for a default-package type).
            // So ANY inbound reference from a named-package file — regardless of reference kind, including
            // cross-source-set references — becomes unresolvable. Refuse with the concrete blocking locations rather
            // than planning an edit that can never compile. The moved file itself is exempt: it becomes a
            // default-package file along with the move.
            if (effectiveTargetPackage.isEmpty()) {
                List<IdentifierSpan> inboundFromNamedPackages = new ArrayList<>();
                for (IdentifierSpan reference : analysis.references()) {
                    Path referencingFile = reference.file();
                    if (referencingFile.equals(oldFile)) {
                        continue;
                    }
                    // Fail closed: a file the model cannot place (packageNameOfAnyTask == null) is treated as a
                    // named-package blocker rather than assumed to be in the default package.
                    String referencingPackage = index.packageNameOfAnyTask(referencingFile);
                    if (referencingPackage == null || !referencingPackage.isEmpty()) {
                        inboundFromNamedPackages.add(reference);
                    }
                }
                if (!inboundFromNamedPackages.isEmpty()) {
                    return refusalJson("move_to_default_package_breaks_inbound_references",
                            "Move refuses relocating '" + typeName + "' into the default package: "
                                    + inboundFromNamedPackages.size() + " reference(s) from file(s) in named packages "
                                    + "would become unresolvable, because Java code in a named package cannot import, "
                                    + "statically import, fully qualify, or otherwise reference a default-package type. "
                                    + "Move the referencing code out of its named package or pick a named target "
                                    + "package instead; the blocking references are listed.",
                            index.referencesJsonRich(model.projectRoot(), inboundFromNamedPackages));
                }
            }
            if (extractFromMultiType) {
                return extractionEditJson(index, model, analysis, oldFile, newFile, oldPackage, effectiveTargetPackage, typeName, charset);
            }
            return workspaceEditJson(index, model, analysis, oldFile, newFile, oldPackage, effectiveTargetPackage, typeName, charset);
        }
    }

    /**
     * Refuses the move when any compiled {@code module-info.java} {@code exports} or {@code opens} the old package.
     * Moving a type out of an exported/opened package can leave the module descriptor referencing a package that no
     * longer contains the type (or becomes empty), and the new package is not exported — a module-graph change this
     * conservative v1 move does not attempt to rewrite. Detection is AST/model-based (via the compiler's
     * {@link com.sun.source.tree.ModuleTree} directives), not a regex over module-info source text, so a commented-out
     * or string-literal directive is never misread.
     *
     * <p>Beyond {@code exports}/{@code opens} of the old package, this also refuses when a {@code module-info.java}
     * {@code uses} or {@code provides} the moved type by its old fully-qualified name. Those directives reference TYPES
     * (not packages), so moving a service interface or a provider implementation would leave a stale FQN in the module
     * descriptor that v1 does not rewrite. Fail closed rather than desync the descriptor.
     */
    private static String moduleInfoRefusal(SemanticIndex index, JavaProjectModel model, String oldPackage, String typeName) {
        SemanticIndex.ModuleExportInfo info = index.moduleExportsPackage(oldPackage);
        if (info != null) {
            String message = "Move refuses to relocate a type out of package '" + oldPackage
                    + "', which is " + info.directive() + "ed by " + relative(model.projectRoot(), info.moduleInfoFile())
                    + ". Update the module descriptor manually first.";
            return refusalJson("module_info_package_exported", message);
        }
        String oldFqn = oldPackage.isEmpty() ? typeName : oldPackage + "." + typeName;
        SemanticIndex.ModuleExportInfo typeInfo = index.moduleReferencesType(oldFqn);
        if (typeInfo != null) {
            String message = "Move refuses to relocate type '" + oldFqn
                    + "', which is referenced by a '" + typeInfo.directive() + "' directive in "
                    + relative(model.projectRoot(), typeInfo.moduleInfoFile())
                    + ". Update the module descriptor manually first.";
            return refusalJson("module_info_type_referenced", message);
        }
        return null;
    }

    private static boolean hasSingleTypeImport(String source, String oldFqn) {
        return Pattern.compile("import\\s+" + Pattern.quote(oldFqn) + "\\s*;").matcher(source).find();
    }

    /**
     * The whole-line removal range {@code [start, end)} of the import statement whose package qualifier begins at
     * {@code qualifierStart}: from the start of the line (so leading {@code import }/whitespace is removed) through the
     * terminating line break, so removing an obsolete import leaves no blank line.
     */
    private static int[] importLineRange(String source, int qualifierStart) {
        int start = qualifierStart;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        int end = qualifierStart;
        while (end < source.length() && source.charAt(end) != '\n') {
            end++;
        }
        if (end < source.length() && source.charAt(end) == '\n') {
            end++;
        }
        return new int[]{start, end};
    }

    private static String workspaceEditJson(SemanticIndex index, JavaProjectModel model, RefactorAnalysisResult analysis, Path oldFile, Path newFile, String oldPackage, String targetPackage, String typeName, java.nio.charset.Charset charset) throws IOException {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        String oldRelative = relative(model.projectRoot(), oldFile);
        String newRelative = relative(model.projectRoot(), newFile);
        String oldFqn = oldPackage.isEmpty() ? typeName : oldPackage + "." + typeName;
        String newFqn = targetPackage.isEmpty() ? typeName : targetPackage + "." + typeName;

        // Moved file: rewrite its package, and add imports for former same-package siblings it used unqualified
        // (default-package siblings cannot be imported, so they are not collected). Skip siblings the file already
        // imports explicitly, since a duplicate single-type import is a javac error. The edit targets the file's CURRENT
        // (old) path; the rename file operation moves the already-edited content to the destination AFTER text staging.
        String movedSource = Files.readString(oldFile, charset);
        Set<String> outboundImports = oldPackage.isEmpty()
                ? Set.of()
                : index.referencedTypeFqnsInPackage(oldFile, oldPackage, analysis.target().element()).stream()
                        .filter(fqn -> !hasSingleTypeImport(movedSource, fqn))
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        edits.add(packageEdit(index, oldFile, targetPackage, outboundImports));

        // Inbound: every reference to the moved type across all source sets, classified from the compiler's reference
        // data (imports, fully-qualified usages, simple-name usages) with exact AST spans — never a raw text indexOf.
        TypeElement movedType = (TypeElement) analysis.target().element();
        List<SemanticIndex.MoveReference> references =
                index.classifyMoveReferences(movedType, oldFile, oldPackage, targetPackage);

        // Group per file so import insertion/removal and qualifier rewrites are computed against one source snapshot.
        Map<Path, List<SemanticIndex.MoveReference>> byFile = new LinkedHashMap<>();
        for (SemanticIndex.MoveReference reference : references) {
            byFile.computeIfAbsent(reference.file().toAbsolutePath().normalize(), key -> new ArrayList<>()).add(reference);
        }

        // Generated/dependency-source safety gate (shared with rename/safe-delete/inline): the moved file, its
        // destination, and every referencing file the move would rewrite must be editable, non-generated source files.
        java.util.LinkedHashSet<Path> affected = new java.util.LinkedHashSet<>();
        affected.add(oldFile);
        affected.add(newFile);
        affected.addAll(byFile.keySet());
        String nonEditable = index.detectNonEditableFiles(affected);
        if (nonEditable != null) {
            return refusalJson("non_editable_target", nonEditable);
        }

        appendInboundReferenceEdits(index, edits, byFile, oldFile, targetPackage, newFqn, charset);
        String fileOp = PlannerSupport.renameFileOp(model.projectRoot(), oldRelative, newRelative);
        // Safety surface (warning-only): a move changes the type's fully-qualified name, so external reflection/resource
        // references keyed by FQN can break; plus any model-level incomplete-classpath/annotation-processing caveats.
        List<String> warnings = PlannerSupport.modelSafetyWarnings(model);
        warnings.add(PlannerSupport.reflectionResourceCaveat("type '" + oldFqn + "' (now '" + newFqn + "')"));
        int editCount = edits.size();
        return "{\"accepted\":true,\"target\":" + analysis.targetJson(model.projectRoot()) + ",\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(model.projectRoot(), edits) + ","
                + "\"fileOperations\":[" + fileOp + "],"
                + "\"warnings\":" + PlannerSupport.warningsJson(warnings) + ",\"preconditions\":[\"top-level type file move\"],"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":1}},"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":1}}";
    }

    /**
     * Appends the inbound-reference edits (import rewrite/removal, fully-qualified rewrite, and simple-name import
     * insertion) for every referencing file in {@code byFile}. The moved/original file is skipped (its references are
     * handled by the move's own outbound edits); {@code classifyMoveReferences} already excludes it, and the explicit
     * guard keeps the whole-file move correct when the source file would otherwise appear. Shared by the whole-file move
     * and the extraction move so both classify and rewrite inbound references identically.
     */
    private static void appendInboundReferenceEdits(SemanticIndex index, List<PlannerSupport.TextEdit> edits,
            Map<Path, List<SemanticIndex.MoveReference>> byFile, Path oldFile, String targetPackage, String newFqn,
            java.nio.charset.Charset charset) throws IOException {
        for (Map.Entry<Path, List<SemanticIndex.MoveReference>> entry : byFile.entrySet()) {
            Path file = entry.getKey();
            if (file.equals(oldFile)) {
                continue;
            }
            List<SemanticIndex.MoveReference> fileRefs = entry.getValue();
            String source = Files.readString(file, charset);

            boolean hasSingleTypeImportRef = false;
            boolean importRemoved = false;
            boolean usesSimpleName = false;
            for (SemanticIndex.MoveReference reference : fileRefs) {
                switch (reference.kind()) {
                    case IMPORT -> {
                        // Only a NON-static single-type import satisfies a simple-name TYPE use. A static import
                        // (`import static com.old.Foo.X;`) imports members, not the type name, so it is rewritten
                        // below but must not suppress inserting `import newpkg.Foo;` for a remaining simple-name use.
                        if (!reference.staticImport()) {
                            hasSingleTypeImportRef = true;
                        }
                        if (reference.importRemovable()) {
                            // Obsolete import (the importing file is in the moved type's new package): remove the whole
                            // import statement line.
                            int[] lineRange = importLineRange(source, reference.start());
                            edits.add(new PlannerSupport.TextEdit(file, lineRange[0], lineRange[1], "", "IMPORT"));
                            importRemoved = true;
                        } else {
                            // Rewrite only the package qualifier (`com.old` -> `com.newpkg`); the trailing simple name
                            // and any static-member suffix are untouched.
                            edits.add(new PlannerSupport.TextEdit(file, reference.start(), reference.end(), targetPackage, "IMPORT"));
                        }
                    }
                    case QUALIFIED_FQN ->
                            edits.add(new PlannerSupport.TextEdit(file, reference.start(), reference.end(), newFqn, "REFERENCE"));
                    case SIMPLE -> usesSimpleName = true;
                }
            }
            // A simple-name use needs an explicit single-type import of the moved type at its new package when the file
            // does not already keep one. A file in the OLD package (no import, simple name) gains an import; a file whose
            // single-type import we just rewrote already covers it; a file whose obsolete import we removed is now in the
            // new package and needs none. A file already living in the TARGET package sees the moved type by package
            // visibility, so no import is inserted even when it only carried a static/wildcard import before.
            if (usesSimpleName && !hasSingleTypeImportRef && !importRemoved && !targetPackage.isEmpty()
                    && !targetPackage.equals(index.packageNameOfAnyTask(file))) {
                PlannerSupport.TextEdit insertion = importInsertion(source, index.packageDeclarationRange(file), file, newFqn);
                if (insertion != null) {
                    edits.add(insertion);
                }
            }
        }
    }

    /**
     * HB-1 extraction move: the selected top-level type is NOT the sole declaration in its file, so instead of moving
     * the whole file it is extracted into a NEW file in the destination package while the remaining sibling declarations
     * stay behind. The new file carries the destination package, only the imports the moved type actually uses (import
     * splitting), and the moved declaration verbatim (its attached Javadoc/annotations included). The original file has
     * only that declaration removed; if its remaining siblings still reference the moved type by simple name, it gains an
     * import of the type's new fully-qualified name. Inbound references in every other file are rewritten exactly as for
     * the whole-file move. Apply-time javac validation rolls back the whole edit if the result does not compile.
     */
    private static String extractionEditJson(SemanticIndex index, JavaProjectModel model, RefactorAnalysisResult analysis,
            Path oldFile, Path newFile, String oldPackage, String targetPackage, String typeName,
            java.nio.charset.Charset charset) throws IOException {
        SemanticIndex.DeclarationRange declaration = index.declarationRange(analysis.target().element());
        if (declaration == null) {
            return refusalJson("unsupported_declaration_range", "Could not resolve the declaration range of the type to extract.");
        }
        String movedSource = Files.readString(oldFile, charset);
        int[] removal = SemanticIndex.expandDeclarationRangeForDelete(movedSource, declaration.start(), declaration.end());
        int exStart = removal[0];
        int exEnd = removal[1];
        String declarationText = movedSource.substring(exStart, exEnd);

        TypeElement movedType = (TypeElement) analysis.target().element();
        String oldFqn = oldPackage.isEmpty() ? typeName : oldPackage + "." + typeName;
        String newFqn = targetPackage.isEmpty() ? typeName : targetPackage + "." + typeName;

        Set<String> newFileImports = index.typeImportsForExtraction(movedType, targetPackage);
        String newFileContent = buildExtractedFileContent(targetPackage, newFileImports, declarationText);

        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        // Remove ONLY the moved declaration (with its attached Javadoc/annotations) from the original file.
        edits.add(new PlannerSupport.TextEdit(oldFile, exStart, exEnd, "", "DECLARATION"));

        // The original keeps its remaining siblings; if any of them reference the moved type by simple name, the original
        // now needs an explicit import of the moved type at its new package (skipped when the package is unchanged).
        if (!targetPackage.isEmpty() && !targetPackage.equals(oldPackage)
                && index.referencesTypeBySimpleNameOutside(oldFile, movedType, exStart, exEnd)) {
            PlannerSupport.TextEdit importEdit = importInsertion(movedSource, index.packageDeclarationRange(oldFile), oldFile, newFqn);
            if (importEdit != null) {
                edits.add(importEdit);
            }
        }

        // Inbound references in every OTHER file (classifyMoveReferences excludes the original file).
        List<SemanticIndex.MoveReference> references = index.classifyMoveReferences(movedType, oldFile, oldPackage, targetPackage);
        Map<Path, List<SemanticIndex.MoveReference>> byFile = new LinkedHashMap<>();
        for (SemanticIndex.MoveReference reference : references) {
            byFile.computeIfAbsent(reference.file().toAbsolutePath().normalize(), key -> new ArrayList<>()).add(reference);
        }

        java.util.LinkedHashSet<Path> affected = new java.util.LinkedHashSet<>();
        affected.add(oldFile);
        affected.add(newFile);
        affected.addAll(byFile.keySet());
        String nonEditable = index.detectNonEditableFiles(affected);
        if (nonEditable != null) {
            return refusalJson("non_editable_target", nonEditable);
        }

        appendInboundReferenceEdits(index, edits, byFile, oldFile, targetPackage, newFqn, charset);

        String newRelative = relative(model.projectRoot(), newFile);
        String fileOp = PlannerSupport.createFileOp(newRelative, newFileContent);
        List<String> warnings = PlannerSupport.modelSafetyWarnings(model);
        warnings.add(PlannerSupport.reflectionResourceCaveat("type '" + oldFqn + "' (now '" + newFqn + "')"));
        int editCount = edits.size();
        return "{\"accepted\":true,\"target\":" + analysis.targetJson(model.projectRoot()) + ",\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(model.projectRoot(), edits) + ","
                + "\"fileOperations\":[" + fileOp + "],"
                + "\"warnings\":" + PlannerSupport.warningsJson(warnings) + ",\"preconditions\":[\"top-level type extraction\"],"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":1}},"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":1}}";
    }

    /** New-file content for an extraction move: destination package, the only-needed imports (grouped), then the type. */
    private static String buildExtractedFileContent(String targetPackage, Set<String> imports, String declarationText) {
        StringBuilder sb = new StringBuilder();
        if (!targetPackage.isEmpty()) {
            sb.append("package ").append(targetPackage).append(";\n\n");
        }
        if (!imports.isEmpty()) {
            List<String> sorted = new ArrayList<>(imports);
            sorted.sort((a, b) -> {
                int group = Integer.compare(importGroup(a), importGroup(b));
                return group != 0 ? group : a.compareTo(b);
            });
            int previousGroup = -1;
            for (String fqn : sorted) {
                int group = importGroup(fqn);
                if (previousGroup != -1 && group != previousGroup) {
                    sb.append("\n");
                }
                sb.append("import ").append(fqn).append(";\n");
                previousGroup = group;
            }
            sb.append("\n");
        }
        sb.append(declarationText);
        return sb.toString();
    }

    private static PlannerSupport.TextEdit packageEdit(SemanticIndex index, Path oldFile, String targetPackage, Set<String> outboundImports) throws IOException {
        int[] range = index.packageDeclarationRange(oldFile);
        String imports = outboundImports.isEmpty()
                ? ""
                : outboundImports.stream().sorted().map(fqn -> "import " + fqn + ";").collect(Collectors.joining("\n"));
        if (range == null) {
            String header = targetPackage.isEmpty() ? "" : "package " + targetPackage + ";\n";
            return new PlannerSupport.TextEdit(oldFile, 0, 0, imports.isEmpty() ? header : header + imports + "\n", "PACKAGE");
        }
        String replacement = targetPackage.isEmpty() ? "" : "package " + targetPackage + ";";
        if (!imports.isEmpty()) {
            replacement = replacement.isEmpty() ? imports : replacement + "\n" + imports;
        }
        return new PlannerSupport.TextEdit(oldFile, range[0], range[1], replacement, "PACKAGE");
    }

    private static final Pattern IMPORT_STATEMENT =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+(static[ \\t]+)?([\\w.*]+)[ \\t]*;[ \\t]*\\r?\\n?");

    /** A parsed import statement: its whole-line span, whether it is a {@code static} import, and the imported name. */
    private record ImportStmt(int lineStart, int lineEnd, boolean isStatic, String name) {
    }

    /** A computed import insertion: the zero-width offset and the text to splice in. */
    private record ImportInsertion(int offset, String text) {
    }

    /**
     * A zero-width edit inserting a single-type {@code import newFqn;} deterministically. When the file already has an
     * import block, the new import is placed inside it in sorted order within its group ({@code java.*}, then
     * {@code javax.*}, then everything else — third-party and project share the trailing group because the planner has
     * no reliable project-vs-library signal here), with a single blank line kept between groups. When the file has no
     * imports, the import is inserted after the package declaration (or at the very top of a default-package file).
     * Never inserts a duplicate: returns null when {@code newFqn} is already imported so the caller skips the edit.
     */
    private static PlannerSupport.TextEdit importInsertion(String source, int[] packageRange, Path file, String newFqn) {
        ImportInsertion insertion = computeImportInsertion(source, packageRange, newFqn);
        if (insertion == null) {
            return null;
        }
        return new PlannerSupport.TextEdit(file, insertion.offset(), insertion.offset(), insertion.text(), "IMPORT");
    }

    static ImportInsertion computeImportInsertion(String source, int[] packageRange, String newFqn) {
        List<ImportStmt> imports = parseImports(source);
        for (ImportStmt existing : imports) {
            if (!existing.isStatic() && existing.name().equals(newFqn)) {
                return null;
            }
        }
        List<ImportStmt> regular = imports.stream().filter(stmt -> !stmt.isStatic()).collect(Collectors.toList());
        int newGroup = importGroup(newFqn);
        if (regular.isEmpty()) {
            if (packageRange == null) {
                return new ImportInsertion(0, "import " + newFqn + ";\n\n");
            }
            return new ImportInsertion(packageRange[1], "\n\nimport " + newFqn + ";");
        }
        ImportStmt after = null;
        ImportStmt before = null;
        for (ImportStmt stmt : regular) {
            int cmp = compareImport(stmt, newGroup, newFqn);
            if (cmp < 0) {
                after = stmt;
            } else if (cmp > 0) {
                before = stmt;
                break;
            }
        }
        if (after != null) {
            boolean blankBefore = importGroup(after.name()) != newGroup;
            return new ImportInsertion(after.lineEnd(), (blankBefore ? "\n" : "") + "import " + newFqn + ";\n");
        }
        ImportStmt first = regular.get(0);
        boolean blankAfter = importGroup(first.name()) != newGroup;
        return new ImportInsertion(first.lineStart(), "import " + newFqn + ";\n" + (blankAfter ? "\n" : ""));
    }

    private static List<ImportStmt> parseImports(String source) {
        List<ImportStmt> imports = new ArrayList<>();
        java.util.regex.Matcher matcher = IMPORT_STATEMENT.matcher(source);
        while (matcher.find()) {
            imports.add(new ImportStmt(matcher.start(), matcher.end(), matcher.group(1) != null, matcher.group(2)));
        }
        return imports;
    }

    /** Group ordering: {@code java.*} (0), {@code javax.*} (1), then all other (third-party/project) imports (2). */
    private static int importGroup(String name) {
        if (name.startsWith("java.")) {
            return 0;
        }
        if (name.startsWith("javax.")) {
            return 1;
        }
        return 2;
    }

    /** Negative if {@code stmt} sorts before the new import (lower group, or same group lexicographically), else positive. */
    private static int compareImport(ImportStmt stmt, int newGroup, String newFqn) {
        int group = Integer.compare(importGroup(stmt.name()), newGroup);
        if (group != 0) {
            return group;
        }
        return stmt.name().compareTo(newFqn);
    }

    private static Path sourceRootFor(JavaProjectModel model, Path oldFile) throws IOException {
        Path root = sourceRootContaining(model, oldFile);
        if (root == null) {
            throw new IOException("Source root not found for move target: " + oldFile);
        }
        return root;
    }

    /**
     * The deepest project source root containing {@code path} (a directory or file), or {@code null} when none does.
     * The deepest match wins so a nested source root is preferred over an enclosing one when both qualify.
     */
    private static Path sourceRootContaining(JavaProjectModel model, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path best = null;
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (normalized.startsWith(normalizedRoot) && (best == null || normalizedRoot.getNameCount() > best.getNameCount())) {
                    best = normalizedRoot;
                }
            }
        }
        return best;
    }

    private static void validatePackage(String packageName) {
        if (packageName == null) {
            throw new IllegalArgumentException("targetPackage is required");
        }
        if (packageName.isEmpty()) {
            return;
        }
        for (String part : packageName.split("\\.")) {
            if (!SourceVersion.isIdentifier(part) || SourceVersion.isKeyword(part)) {
                throw new IllegalArgumentException("Invalid Java package: " + packageName);
            }
        }
    }
}
