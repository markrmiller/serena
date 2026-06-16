package io.serena.javarefactor.operations.extract_interface;

import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.JavaStyleProfile;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V2 extract-interface planner backed by javac type/member discovery and source-position edits. */
public final class ExtractInterfacePlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;

    public ExtractInterfacePlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    /** Returns an extract-interface preview for public method members only. */
    public String extractInterface(Map<String, Object> fields, boolean apply) {
        try {
            Path sourceFile = sourceFile(fields);
            String source = SourceText.read(model, sourceFile);
            String interfaceName = stringField(fields, "interfaceName", "");
            if (interfaceName.isBlank()) {
                throw new Refusal("missing_interface_name", "interfaceName is required.");
            }
            if (!interfaceName.matches("[A-Z_$][A-Za-z0-9_$]*")) {
                throw new Refusal("invalid_interface_name", "interfaceName must be a Java type identifier.");
            }
            String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
            SemanticIndex.SemanticType sourceType = index.primaryType(sourceFile);
            if (sourceType == null) {
                throw new Refusal("source_type_not_found", "Source file must contain a javac-resolved top-level class or interface declaration.");
            }
            // Refuse if the position-named type is not the primary type this planner extracts from.
            SemanticTargetGate.confirmSelection(verified, sourceType.element());
            String sourceKind = sourceType.kind().contains("interface") ? "interface" : "class";
            String sourceName = sourceType.name();
            String sourcePackage = sourceType.packageName();
            Set<String> requested = requestedMembers(fields);
            String targetPackage = stringField(fields, "targetPackage", sourcePackage);
            InterfaceFileSynthesizer synthesizer = new InterfaceFileSynthesizer(projectRoot, model, index);
            List<InterfaceFileSynthesizer.MethodSignature> signatures =
                    methodSignatures(synthesizer, index, sourceFile, requested, targetPackage);
            if (signatures.isEmpty()) {
                throw new Refusal("no_supported_members", "V2 extract interface requires selected public instance methods.");
            }
            rejectDuplicateSignatures(signatures);

            Path interfaceFile = synthesizer.interfaceFile(sourceFile, sourcePackage, targetPackage, interfaceName);
            String interfaceRelative = PlannerSupport.relative(projectRoot, interfaceFile);
            // An incremental session re-resolve (G001/G003) passes the output paths a prior acknowledged partial apply
            // already committed to disk. Such a file existing is THIS session's own expected progress, not a stale
            // external collision, so it must not refuse re-resolution. The create unit is still emitted below, so the
            // recomputed plan's unit structure and semantic target match the stored preview; selection then filters the
            // already-applied create out of the remaining subset.
            if (Files.exists(interfaceFile) && !sessionAppliedOutputPaths(fields).contains(interfaceRelative)) {
                throw new Refusal("interface_already_exists", "The target interface file already exists.");
            }

            ImportManager importPlanner = new ImportManager(source)
                    .withConflictResolver(ImportConflictResolvers.samePackageAndProject(
                            index, sourceFile, sourcePackage));
            ImportManager.TypeUse interfaceUsage = importPlanner.planTypeUsageDeep(
                    sourceFile, synthesizer.fqn(targetPackage, interfaceName), "EXTRACT_INTERFACE_IMPORT");
            String interfaceReference = interfaceUsage.renderedType();
            List<PlannerSupport.TextEdit> edits = new ArrayList<>();
            // Semantic implements/extends edit: compute a precise insertion offset within the javac-proven
            // inheritance-tail range, skipping any type-parameter section so generic bounds such as
            // "<T extends Comparable<T>>" are never mistaken for the inheritance clause (the failure mode of a
            // naive whole-tail keyword replace). The emitted edit is a zero-width insertion, not a rewrite.
            SemanticIndex.SourceRange inheritanceTail = sourceType.inheritanceTailRange();
            InheritanceInsertion inheritanceEdit = inheritanceInsertion(
                    sourceKind, inheritanceTail.text(index), inheritanceTail.start(), interfaceReference);
            edits.add(new PlannerSupport.TextEdit(
                    sourceFile,
                    inheritanceEdit.offset(),
                    inheritanceEdit.offset(),
                    inheritanceEdit.text(),
                    "EXTRACT_INTERFACE_INHERITANCE"));
            for (PlannerSupport.TextEdit importEdit : interfaceUsage.importEdits()) {
                if (!edits.contains(importEdit)) {
                    edits.add(importEdit);
                }
            }

            List<String> warnings = new ArrayList<>();
            warnings.add("V2 extractInterface discovers source type and selected public instance methods with javac Elements/TypeMirror/source positions; text rendering is applied only after semantic ranges are proven.");

            if (Boolean.TRUE.equals(fields.get("replaceUsages"))) {
                Set<String> selectedMethodKeys = new LinkedHashSet<>();
                for (InterfaceFileSynthesizer.MethodSignature signature : signatures) {
                    selectedMethodKeys.add(signature.methodKey());
                }
                TypeUsageRewriter.UsageNarrowing narrowing = new TypeUsageRewriter(projectRoot, index)
                        .plan(sourceType, targetPackage, interfaceName, selectedMethodKeys);
                // G026: surface EVERY blocking unsafe usage / non-extracted call across all candidates at once, rather
                // than throwing on the first one, so the caller can fix all blockers in a single pass.
                if (!narrowing.blockingSites().isEmpty()) {
                    throw new Refusal(
                            "unsafe_usage_replacement",
                            "Usage narrowing would hide " + narrowing.blockingSites().size()
                                    + " non-interface use(s); resolve every listed site before narrowing.",
                            narrowing.blockingSites());
                }
                // G024: narrowing a FIELD or PARAMETER declaration changes a type's public-API surface. Without an
                // explicit confirmation flag we REFUSE (listing the affected sites) rather than emitting a warning-only
                // change. Local-variable narrowing is internal to one method body and proceeds without confirmation.
                if (!narrowing.apiVisibleSites().isEmpty() && !confirmPublicApiChange(fields)) {
                    throw new Refusal(
                            "public_api_confirmation_required",
                            "Usage narrowing rewrites " + narrowing.apiVisibleSites().size()
                                    + " API-visible declaration site(s) to '" + interfaceName
                                    + "'; re-run with confirmPublicApiChange=true to apply after reviewing the listed sites.",
                            narrowing.apiVisibleSites());
                }
                edits.addAll(narrowing.edits());
                if (!narrowing.narrowedSites().isEmpty()) {
                    warnings.add("Usage narrowing rewrites " + narrowing.narrowedSites().size() + " reference site(s) to '"
                            + interfaceName + "' (" + narrowing.apiVisibleSites().size() + " API-visible); review callers "
                            + "and any cast/reflection/serialization sites before applying.");
                }
            }

            String interfaceSource = synthesizer.render(
                    targetPackage, interfaceName, signatures, JavaStyleProfile.infer(source));
            // G025: derive stats/touchedFiles/changedFiles from the REAL edits + file operations via ResponseBuilder so
            // usage-replacement candidate declaration files edited by narrowing are always included in the reported
            // touched-file set (preview review and the session revision guard both rely on it).
            List<ResponseBuilder.FileOperation> fileOperations = List.of(
                    ResponseBuilder.FileOperation.create(
                            PlannerSupport.relative(projectRoot, interfaceFile), interfaceSource));
            String semanticTargetJson = "{\"semanticKey\":" + SemanticKey.from(sourceType.element()).toJson() + "}";
            List<String> preconditions = List.of(
                    "selected members are public instance methods",
                    "target interface file does not exist");
            return ResponseBuilder.acceptedResult(
                    projectRoot,
                    "extractInterface",
                    apply,
                    semanticTargetJson,
                    edits,
                    fileOperations,
                    warnings,
                    preconditions,
                    ResponseBuilder.DiagnosticDelta.unvalidated(),
                    false);
            }
        } catch (InterfaceFileSynthesizer.PathResolutionException violation) {
            return PlannerSupport.refusalJson("extractInterface", apply, violation.code(), violation.getMessage());
        } catch (Refusal refusal) {
            if (!refusal.sites.isEmpty()) {
                return refusalWithSitesJson(apply, refusal.code, refusal.getMessage(), refusal.sites);
            }
            return PlannerSupport.refusalJson("extractInterface", apply, refusal.code, refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return PlannerSupport.refusalJson("extractInterface", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return PlannerSupport.refusalJson("extractInterface", apply, "extract_interface_failed", error.getMessage());
        }
    }

    private Path sourceFile(Map<String, Object> fields) {
        String relative = stringField(fields, "relativePath", "");
        if (relative.isBlank()) {
            throw new Refusal("missing_relative_path", "relativePath is required.");
        }
        try {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, relative, "relativePath");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    /** Project-relative output paths an earlier acknowledged partial apply already committed for this session (G001). */
    private static Set<String> sessionAppliedOutputPaths(Map<String, Object> fields) {
        Object raw = fields.get("__sessionAppliedOutputPaths");
        if (!(raw instanceof List<?> values)) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                paths.add(text);
            }
        }
        return paths;
    }

    private Set<String> requestedMembers(Map<String, Object> fields) {
        Set<String> members = new LinkedHashSet<>();
        Object raw = fields.get("members");
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof String text && !text.isBlank()) {
                    members.add(text.trim());
                } else if (value instanceof Map<?, ?> map) {
                    Object name = map.get("name");
                    if (name instanceof String text && !text.isBlank()) {
                        members.add(text.trim());
                    }
                }
            }
        }
        return members;
    }

    private List<InterfaceFileSynthesizer.MethodSignature> methodSignatures(
            InterfaceFileSynthesizer synthesizer, SemanticIndex index, Path file, Set<String> requested, String targetPackage) {
        List<SemanticIndex.SemanticMethod> methods;
        try {
            methods = index.publicInstanceMethods(file, requestedNames(requested));
        } catch (IllegalArgumentException refusal) {
            throw new Refusal("unsupported_members", refusal.getMessage());
        }
        List<SemanticIndex.SemanticMethod> selectedMethods = selectRequestedMethods(index, methods, requested);
        try {
            return synthesizer.signatures(selectedMethods, targetPackage);
        } catch (InterfaceFileSynthesizer.UnsupportedSignatureException inaccessible) {
            throw new Refusal("private_type_unsupported",
                    "Selected method exposes a private or package-inaccessible return, parameter, type-parameter bound, or thrown type: '"
                            + inaccessible.methodName() + "'. " + inaccessible.getMessage());
        }
    }

    private Set<String> requestedNames(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String selector : requested) {
            names.add(selectorName(normalizeSelector(selector)));
        }
        return names;
    }

    private List<SemanticIndex.SemanticMethod> selectRequestedMethods(SemanticIndex index, List<SemanticIndex.SemanticMethod> methods, Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return methods;
        }
        Map<String, List<SemanticIndex.SemanticMethod>> byName = new LinkedHashMap<>();
        Map<String, SemanticIndex.SemanticMethod> bySignature = new LinkedHashMap<>();
        for (SemanticIndex.SemanticMethod method : methods) {
            byName.computeIfAbsent(method.name(), ignored -> new ArrayList<>()).add(method);
            String signature = index.methodSignatureKey(method);
            putSignatureSelector(bySignature, signature, method);
            putSignatureSelector(bySignature, simpleSignatureKey(signature), method);
        }
        List<SemanticIndex.SemanticMethod> selected = new ArrayList<>();
        Set<String> selectedKeys = new LinkedHashSet<>();
        for (String rawSelector : requested) {
            String selector = normalizeSelector(rawSelector);
            SemanticIndex.SemanticMethod method;
            if (selector.contains("(")) {
                if (!bySignature.containsKey(selector)) {
                    throw new Refusal("member_not_found", "No public instance method matches selected member signature '" + rawSelector + "'.");
                }
                method = bySignature.get(selector);
                if (method == null) {
                    throw new Refusal("ambiguous_member_selection", "Selected member signature '" + rawSelector
                            + "' matches multiple overloads; use the fully qualified erased signature.");
                }
            } else {
                List<SemanticIndex.SemanticMethod> matches = byName.getOrDefault(selector, List.of());
                if (matches.isEmpty()) {
                    throw new Refusal("member_not_found", "No public instance method matches selected member '" + rawSelector + "'.");
                }
                if (matches.size() > 1) {
                    throw new Refusal("ambiguous_member_selection", "Selected member '" + rawSelector
                            + "' matches multiple overloads; select a full signature such as '" + index.methodSignatureKey(matches.get(0)) + "'.");
                }
                method = matches.get(0);
            }
            String key = index.methodSignatureKey(method);
            if (selectedKeys.add(key)) {
                selected.add(method);
            }
        }
        return selected;
    }

    private void putSignatureSelector(Map<String, SemanticIndex.SemanticMethod> bySignature, String selector, SemanticIndex.SemanticMethod method) {
        if (!bySignature.containsKey(selector)) {
            bySignature.put(selector, method);
            return;
        }
        SemanticIndex.SemanticMethod existing = bySignature.get(selector);
        if (existing != method) {
            bySignature.put(selector, null);
        }
    }

    private String normalizeSelector(String selector) {
        return selector == null ? "" : selector.replaceAll("\\s+", "");
    }

    private String selectorName(String selector) {
        int parameters = selector.indexOf('(');
        return parameters >= 0 ? selector.substring(0, parameters) : selector;
    }

    private String simpleSignatureKey(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < open) {
            return signature;
        }
        String parameters = signature.substring(open + 1, close);
        if (parameters.isBlank()) {
            return signature.substring(0, open) + "()";
        }
        List<String> simpleParameters = new ArrayList<>();
        for (String parameter : parameters.split(",")) {
            String trimmed = parameter.trim();
            int lastDot = trimmed.lastIndexOf('.');
            simpleParameters.add(lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed);
        }
        return signature.substring(0, open) + "(" + String.join(",", simpleParameters) + ")";
    }

    private void rejectDuplicateSignatures(List<InterfaceFileSynthesizer.MethodSignature> signatures) {
        Set<String> seen = new LinkedHashSet<>();
        for (InterfaceFileSynthesizer.MethodSignature signature : signatures) {
            String key = signature.name() + "(" + signature.parameters().replaceAll("\\s+", " ").trim() + ")";
            if (!seen.add(key)) {
                throw new Refusal("duplicate_signatures", "Selected members contain duplicate interface signatures.");
            }
        }
    }

    /** Whether the caller explicitly confirmed the API-visible usage-narrowing change (G024). */
    private static boolean confirmPublicApiChange(Map<String, Object> fields) {
        return boolField(fields, "confirmPublicApiChange") || boolField(fields, "confirmPublicApi");
    }

    private static boolean boolField(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** A zero-width insertion that adds the extracted interface to a type's inheritance clause. */
    record InheritanceInsertion(int offset, String text) {
    }

    /**
     * Computes the precise insertion for adding {@code interfaceReference} to a type's inheritance clause, given the
     * javac-proven inheritance-tail text (the source between the type name and its opening brace) and that text's
     * absolute start offset. The scan is type-parameter aware: angle-bracket depth is tracked so an {@code extends}
     * inside a generic bound (e.g. {@code <T extends Comparable<T>>}) is never mistaken for the inheritance clause.
     *
     * <ul>
     *   <li>If a top-level {@code implements} (class) or {@code extends} (interface) clause already exists, the new
     *       interface is appended to the list as {@code ", Ref"} after the last listed type.</li>
     *   <li>Otherwise a fresh {@code " implements Ref"} / {@code " extends Ref"} clause is inserted after the last
     *       non-whitespace token (an existing {@code extends Super} for a class), leaving the trailing layout before
     *       the brace untouched.</li>
     * </ul>
     */
    static InheritanceInsertion inheritanceInsertion(
            String sourceKind, String tailText, int tailStart, String interfaceReference) {
        String keyword = "interface".equals(sourceKind) ? "extends" : "implements";
        int lastNonWhitespace = lastNonWhitespaceIndex(tailText);
        int insertAt = tailStart + (lastNonWhitespace < 0 ? 0 : lastNonWhitespace + 1);
        boolean clauseExists = topLevelKeywordIndex(tailText, keyword) >= 0;
        String text = clauseExists
                ? ", " + interfaceReference
                : " " + keyword + " " + interfaceReference;
        return new InheritanceInsertion(insertAt, text);
    }

    /** Index of the first whole-word {@code keyword} at angle-bracket depth zero in {@code text}, or {@code -1}. */
    private static int topLevelKeywordIndex(String text, String keyword) {
        int depth = 0;
        for (int i = 0; i + keyword.length() <= text.length(); i++) {
            char current = text.charAt(i);
            if (current == '<') {
                depth++;
                continue;
            }
            if (current == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth != 0 || !text.regionMatches(i, keyword, 0, keyword.length())) {
                continue;
            }
            boolean leftBoundary = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
            int after = i + keyword.length();
            boolean rightBoundary = after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonWhitespaceIndex(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Refusal JSON that additionally carries a structured {@code refusal.sites} array enumerating every blocking
     * usage / called method (G026) or every API-visible reference site awaiting confirmation (G024). Mirrors
     * {@link PlannerSupport#refusalJson} so stats are still centrally derived from an empty edit (G021).
     */
    private static String refusalWithSitesJson(boolean apply, String code, String message, List<String> sites) {
        // G002: route through the one canonical refusal envelope, honoring the ACTUAL requested mode (the previous shape
        // hard-coded mode:"preview", so a direct apply=true usage-narrowing refusal misreported its mode). The structured
        // refusal.sites array is surfaced as an additive extra field; every canonical invariant (applied:false, empty
        // stats/edit, placeholder diagnosticDelta, diagnosticDeltaValidated:false) is preserved by the builder.
        return ResponseBuilder.refusedResult("extractInterface", apply, code, message, null, List.of(),
                java.util.Map.of("sites", JsonUtil.array(sites)), List.of());
    }

    private String stringField(Map<String, Object> fields, String name, String fallback) {
        Object value = fields.get(name);
        return value instanceof String text ? text : fallback;
    }

    private static final class Refusal extends RuntimeException {
        private final String code;
        private final List<String> sites;

        private Refusal(String code, String message) {
            this(code, message, List.of());
        }

        private Refusal(String code, String message, List<String> sites) {
            super(message);
            this.code = code;
            this.sites = List.copyOf(sites);
        }
    }
}
