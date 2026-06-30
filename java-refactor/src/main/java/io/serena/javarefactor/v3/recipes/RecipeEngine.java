package io.serena.javarefactor.v3.recipes;

import io.serena.javarefactor.compiler.RecipeMatchIndex;
import io.serena.javarefactor.compiler.RecipeMatchIndex.RecipeMatch;
import io.serena.javarefactor.compiler.RecipeMatchIndex.RecipeRule;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.operations.change_signature.ChangeSignaturePlanner;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ProjectPathResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V3 compiler-backed <b>API-migration recipe engine</b> (refactor-feature-plan-V3.md §14). This is the spec's standalone
 * semantic match-and-template primitive: it parses a recipe (built-in id or caller-supplied object), resolves every
 * rule's target against javac in {@link RecipeMatchIndex} (never a textual/regex guess), and produces either a grouped
 * <em>scan</em> preview (no edits) or an <em>apply</em> {@code workspaceEdit}. Apply is conservative by default — only
 * {@code safe} edits are emitted; {@code needs_review} edits require {@code apply_needs_review:true}; {@code refused} and
 * report-only findings are never auto-applied. The sidecar's before/after javac validator (run by the caller) is the
 * final backstop on every emitted edit.
 *
 * <p>Refusal codes (registry §14): {@code recipe_not_found}, {@code malformed_recipe}, {@code recipe_unresolved_symbol},
 * {@code recipe_unknown_rule_kind}, {@code recipe_no_matches}, {@code recipe_unsupported_template},
 * {@code recipe_overlapping_edits}.
 */
public final class RecipeEngine {

    private static final Set<String> TYPE_KINDS = Set.of("replaceType", "replaceImport", "replaceAnnotation");

    private final Path projectRoot;
    private final JavaProjectModel model;

    public RecipeEngine(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    // ── recipes.scanMigrationOpportunities (§14: preview, no edits) ───────────────────────────────────────────────

    public String scan(Map<String, Object> fields) {
        try {
            return scanChecked(fields);
        } catch (RecipeRefusal refusal) {
            return PlannerSupport.refusalJson("scanMigrationOpportunities", false, refusal.code(), refusal.getMessage());
        } catch (ProjectPathResolver.Violation violation) {
            return PlannerSupport.refusalJson("scanMigrationOpportunities", false, violation.code(), violation.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("scanMigrationOpportunities", false, "recipe_scan_failed",
                    "Recipe scan failed: " + error.getMessage());
        }
    }

    private String scanChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        Recipe recipe = resolveRecipe(fields);
        String seed = seedRelativePath();
        String scopePackage = scopePackage(fields);
        try (SemanticIndex index = SemanticIndex.open(model, seed)) {
            RecipeMatchIndex matcher = new RecipeMatchIndex(index);
            List<String> warnings = new ArrayList<>(unresolvedWarnings(recipe, matcher));
            List<RecipeMatch> matches = scoped(matcher.scan(recipe.rules()), scopePackage);
            String matchesJson = matchesJson(matcher, matches);
            String groupsJson = groupedMatchesJson(matcher, matches);

            List<String> findingJsons = new ArrayList<>();
            int safe = 0, review = 0, refused = 0;
            Set<String> files = new LinkedHashSet<>();
            for (RecipeMatch match : matches) {
                switch (match.risk()) {
                    case RecipeMatchIndex.RISK_SAFE -> safe++;
                    case RecipeMatchIndex.RISK_REFUSED -> refused++;
                    default -> review++;
                }
                files.add(match.file().toString());
                findingJsons.add(findingJson(matcher, match));
            }
            // changeMethodSignature rules (F13): report the resolved declaration as a needs_review finding (a structural
            // signature change is never auto-"safe"); an unresolved/ambiguous target is reported as a refused finding.
            for (SignatureChangeRule rule : recipe.signatureRules()) {
                RecipeMatchIndex.DeclarationSite site = matcher.resolveExecutableDeclaration(rule.owner(), rule.name(), rule.paramTypes());
                if (!site.resolved()) {
                    refused++;
                    findingJsons.add(signatureFindingJson(rule, null, -1, RecipeMatchIndex.RISK_REFUSED,
                            signatureRefusalDetail(rule, site)));
                    continue;
                }
                if (scopePackage != null) {
                    String pkg = packageOf(site.file());
                    if (pkg == null || !(pkg.equals(scopePackage) || pkg.startsWith(scopePackage + "."))) {
                        continue;
                    }
                }
                review++;
                files.add(site.file().toString());
                findingJsons.add(signatureFindingJson(rule, site.file(), site.line(), RecipeMatchIndex.RISK_NEEDS_REVIEW,
                        "Structural signature change of " + rule.owner() + "." + rule.name()
                                + " via the compiler-backed change-signature operation."));
            }
            String findings = "[" + String.join(",", findingJsons) + "]";

            String stats = "{" + "\"matches\":" + findingJsons.size() + "," + "\"safe\":" + safe + ","
                    + "\"needsReview\":" + review + "," + "\"refused\":" + refused + ","
                    + "\"files\":" + files.size() + "}";
            return "{" + "\"accepted\":true," + "\"operation\":\"scanMigrationOpportunities\"," + "\"recipeId\":"
                    + JsonUtil.quote(recipe.id() == null ? "" : recipe.id()) + "," + "\"findings\":" + findings + ","
                    + "\"stats\":" + stats + "," + "\"warnings\":" + PlannerSupport.warningsJson(warnings) + "}";
        }
    }

    // ── recipes.applyRecipe (§14: validated workspaceEdit) ────────────────────────────────────────────────────────

    public String apply(Map<String, Object> fields) {
        try {
            return applyChecked(fields);
        } catch (RecipeRefusal refusal) {
            return PlannerSupport.refusalJson("applyRecipe", true, refusal.code(), refusal.getMessage());
        } catch (ProjectPathResolver.Violation violation) {
            return PlannerSupport.refusalJson("applyRecipe", true, violation.code(), violation.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("applyRecipe", true, "recipe_apply_failed",
                    "Recipe apply failed: " + error.getMessage());
        }
    }

    private String applyChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        Recipe recipe = resolveRecipe(fields);
        boolean applyNeedsReview = bool(fields, "apply_needs_review", false);
        String seed = seedRelativePath();
        String scopePackage = scopePackage(fields);
        try (SemanticIndex index = SemanticIndex.open(model, seed)) {
            RecipeMatchIndex matcher = new RecipeMatchIndex(index);
            List<String> unresolved = unresolvedTypes(recipe, matcher);
            List<RecipeMatch> matches = scoped(matcher.scan(recipe.rules()), scopePackage);
            String matchesJson = matchesJson(matcher, matches);
            String groupsJson = groupedMatchesJson(matcher, matches);

            List<String> warnings = new ArrayList<>(unresolvedTypesAsWarnings(unresolved));
            // changeMethodSignature rules are structural signature changes driven through the compiler-backed
            // change-signature operation, which enforces its OWN needs_review gate: a public/protected target refuses
            // with PUBLIC_API_CONFIRMATION_REQUIRED unless confirmed, while a package-private target is SAFE and applies.
            // The recipe's apply_needs_review flag is that confirmation — when set it confirms the public-API change so
            // the needs_review work is applied; otherwise the operation's refusal passes through verbatim. The rules are
            // always planned: the operation, not a blanket recipe-level flag, decides what is safe to apply.
            List<TextEdit> signatureEdits =
                    planSignatureEdits(index, matcher, recipe, scopePackage, applyNeedsReview, warnings);

            // A template-only recipe that matched nothing is still a no_matches/unresolved refusal; but a recipe whose
            // signature rules produced edits has matched something, so only refuse when there are no signature rules.
            if (matches.isEmpty() && signatureEdits.isEmpty()) {
                if (!unresolved.isEmpty() && unresolved.size() == referencedTypes(recipe).size()) {
                    throw new RecipeRefusal("recipe_unresolved_symbol",
                            "None of the recipe's referenced types resolve on the project classpath: "
                                    + String.join(", ", unresolved));
                }
                throw new RecipeRefusal("recipe_no_matches", "Recipe '" + recipe.id() + "' matched nothing to apply.");
            }

            // Select editable matches: a concrete replacement, and a risk allowed by the apply policy.
            List<RecipeMatch> editable = new ArrayList<>();
            int skipped = 0;
            for (RecipeMatch match : matches) {
                boolean hasEdit = match.newText() != null;
                if (!hasEdit) {
                    skipped++;
                    continue;
                }
                if (RecipeMatchIndex.RISK_REFUSED.equals(match.risk())) {
                    throw new RecipeRefusal("recipe_refused_match",
                            "Recipe apply matched a refused finding; no partial edit was produced.");
                }
                if (RecipeMatchIndex.RISK_NEEDS_REVIEW.equals(match.risk()) && !applyNeedsReview) {
                    throw new RecipeRefusal("recipe_review_required",
                            "Recipe apply matched review-required findings; pass apply_needs_review:true to produce edits.");
                }
                editable.add(match);
            }
            if (skipped > 0) {
                warnings.add(skipped + " finding(s) were report-only and produced no apply edit.");
            }
            List<TextEdit> edits = buildEdits(matcher, editable, warnings);
            edits.addAll(signatureEdits);
            if (edits.isEmpty()) {
                throw new RecipeRefusal("recipe_no_matches", "Recipe apply produced no in-scope edits.");
            }
            String changes = PlannerSupport.changesJson(projectRoot, edits);
            int totalMatches = matches.size() + signatureEdits.size();
            String stats = "{" + "\"applied\":" + edits.size() + "," + "\"skipped\":" + skipped + ","
                    + "\"matches\":" + totalMatches + "}";
            return "{" + "\"accepted\":true," + "\"operation\":\"applyRecipe\"," + "\"recipeId\":"
                    + JsonUtil.quote(recipe.id() == null ? "" : recipe.id()) + ","
                    + "\"matches\":" + matchesJson + ","
                    + "\"groups\":" + groupsJson + "," + "\"workspaceEdit\":{"
                    + "\"changes\":" + changes + "," + "\"fileOperations\":[]" + "}," + "\"warnings\":"
                    + PlannerSupport.warningsJson(warnings) + "," + "\"stats\":" + stats + "}";
        }
    }

    // ── changeMethodSignature rules (F13: compiler-backed change-signature) ───────────────────────────────────────

    /**
     * Resolves each {@code changeMethodSignature} rule to its declaration and plans the signature change against the open
     * index, returning the merged declaration / override-group / call-site / javadoc edits. Throws a {@link RecipeRefusal}
     * — with the change-signature operation's own code/message — when a rule's target is unresolved/ambiguous or the
     * operation refuses (e.g. {@code PUBLIC_API_CONFIRMATION_REQUIRED}); a rule whose target is outside {@code scope} is
     * skipped with a warning. When {@code confirmPublicApi} is set (the recipe's {@code apply_needs_review} approval),
     * each rule's change fields default {@code confirmPublicApi:true} so a public/protected target is applied rather than
     * refused — a rule that already carries its own confirmation value is left untouched.
     */
    private List<TextEdit> planSignatureEdits(SemanticIndex index, RecipeMatchIndex matcher, Recipe recipe,
                                              String scopePackage, boolean confirmPublicApi, List<String> warnings) {
        if (recipe.signatureRules().isEmpty()) {
            return List.of();
        }
        ChangeSignaturePlanner planner = new ChangeSignaturePlanner(projectRoot, model);
        List<TextEdit> edits = new ArrayList<>();
        for (SignatureChangeRule rule : recipe.signatureRules()) {
            RecipeMatchIndex.DeclarationSite site = matcher.resolveExecutableDeclaration(rule.owner(), rule.name(), rule.paramTypes());
            if (!site.ownerResolved()) {
                throw new RecipeRefusal("recipe_unresolved_symbol", "changeMethodSignature rule '" + rule.id()
                        + "' owner type does not resolve on the project classpath: " + rule.owner());
            }
            if (site.matchCount() == 0) {
                throw new RecipeRefusal("recipe_no_matches", "changeMethodSignature rule '" + rule.id()
                        + "' matched no method '" + rule.name() + "' on " + rule.owner() + ".");
            }
            if (site.matchCount() > 1) {
                throw new RecipeRefusal("malformed_recipe", "changeMethodSignature rule '" + rule.id() + "' matches "
                        + site.matchCount() + " overloads of " + rule.owner() + "." + rule.name()
                        + "; add paramTypes to disambiguate.");
            }
            if (scopePackage != null) {
                String pkg = packageOf(site.file());
                if (pkg == null || !(pkg.equals(scopePackage) || pkg.startsWith(scopePackage + "."))) {
                    warnings.add("changeMethodSignature rule '" + rule.id() + "' skipped: target "
                            + rule.owner() + "." + rule.name() + " is outside scope '" + scopePackage + "'.");
                    continue;
                }
            }
            Map<String, Object> changeFields = new LinkedHashMap<>(rule.change());
            String relativePath = PlannerSupport.relative(projectRoot, site.file());
            changeFields.put("relativePath", relativePath);
            changeFields.put("line", site.line());
            if (confirmPublicApi) {
                changeFields.putIfAbsent("confirmPublicApi", true);
            }
            ChangeSignaturePlanner.RecipeSignaturePlan plan =
                    planner.planRecipeSignatureChange(index, site.file(), relativePath, changeFields);
            if (plan.refused()) {
                throw new RecipeRefusal(plan.refusalCode(), plan.refusalMessage());
            }
            edits.addAll(plan.edits());
            warnings.addAll(plan.warnings());
        }
        return edits;
    }

    // ── edit assembly ────────────────────────────────────────────────────────────────────────────────────────────

    private List<TextEdit> buildEdits(RecipeMatchIndex matcher, List<RecipeMatch> editable, List<String> warnings) {
        // Group by file to (a) detect overlapping edits deterministically and (b) inject each file's required imports once.
        Map<Path, List<RecipeMatch>> byFile = new LinkedHashMap<>();
        for (RecipeMatch match : editable) {
            byFile.computeIfAbsent(match.file(), key -> new ArrayList<>()).add(match);
        }
        List<TextEdit> edits = new ArrayList<>();
        for (Map.Entry<Path, List<RecipeMatch>> entry : byFile.entrySet()) {
            List<RecipeMatch> fileMatches = new ArrayList<>(entry.getValue());
            fileMatches.sort((a, b) -> Integer.compare(a.start(), b.start()));
            int lastEnd = -1;
            RecipeMatch previous = null;
            Set<String> imports = new LinkedHashSet<>();
            // The body replacements actually emitted for this file, so the stale-import scan sees the same post-edit
            // source the caller will apply.
            List<int[]> bodyEdits = new ArrayList<>();
            List<String> bodyTexts = new ArrayList<>();
            for (RecipeMatch match : fileMatches) {
                // Two recipe rules whose edit ranges overlap in one file cannot be applied together without one silently
                // clobbering the other. Rather than drop a subset (a silent partial apply, §14 forbids this), refuse the
                // whole apply with a structured conflict and name BOTH conflicting edits so the caller can resolve it.
                if (match.start() < lastEnd) {
                    throw new RecipeRefusal("recipe_overlapping_edits",
                            "Two recipe rules produce overlapping edits in "
                                    + PlannerSupport.relative(projectRoot, match.file()) + ": rule '" + previous.ruleId()
                                    + "' edits offsets [" + previous.start() + "," + previous.end() + ") and rule '"
                                    + match.ruleId() + "' edits offsets [" + match.start() + "," + match.end()
                                    + "); these ranges overlap and cannot be applied together.");
                }
                edits.add(new TextEdit(match.file(), match.start(), match.end(), match.newText(),
                        "RECIPE_" + match.ruleKind()));
                bodyEdits.add(new int[] {match.start(), match.end()});
                bodyTexts.add(match.newText());
                lastEnd = match.end();
                previous = match;
                imports.addAll(match.addImports());
            }
            RecipeMatch importEdit = matcher.importInsertion(entry.getKey(), new ArrayList<>(imports));
            if (importEdit != null) {
                edits.add(new TextEdit(importEdit.file(), importEdit.start(), importEdit.end(), importEdit.newText(),
                        "RECIPE_addImport"));
            }
            // Remove imports that the replacements left dangling (§14.2 "remove stale imports"), symmetric to the
            // addImport insertion above; an import still referenced after the edits is retained.
            int[] starts = new int[bodyEdits.size()];
            int[] ends = new int[bodyEdits.size()];
            for (int i = 0; i < bodyEdits.size(); i++) {
                starts[i] = bodyEdits.get(i)[0];
                ends[i] = bodyEdits.get(i)[1];
            }
            for (RecipeMatch removal : matcher.staleImportRemovals(entry.getKey(), starts, ends, bodyTexts)) {
                edits.add(new TextEdit(removal.file(), removal.start(), removal.end(), removal.newText(),
                        "RECIPE_removeImport"));
            }
        }
        return edits;
    }

    // ── recipe resolution / referenced-type validation ───────────────────────────────────────────────────────────

    // ── scope restriction (§4.5: scope = "project" | package prefix) ─────────────────────────────────────────────────

    /**
     * The package prefix the recipe is restricted to, or {@code null} for the whole project. {@code "project"} (the
     * default), an empty value, or an absent key all mean "no restriction"; any other value is a package prefix matched
     * package-segment-aware against each matched file's package.
     */
    private static String scopePackage(Map<String, Object> fields) {
        Object raw = fields.get("scope");
        if (raw == null) {
            return null;
        }
        String trimmed = raw.toString().trim();
        if (trimmed.isEmpty() || "project".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    /** Restricts matches to those whose file's package falls within the scope prefix (no-op for a null scope). */
    private List<RecipeMatch> scoped(List<RecipeMatch> matches, String scopePackage) {
        if (scopePackage == null) {
            return matches;
        }
        List<RecipeMatch> kept = new ArrayList<>();
        for (RecipeMatch match : matches) {
            String pkg = packageOf(match.file());
            if (pkg != null && (pkg.equals(scopePackage) || pkg.startsWith(scopePackage + "."))) {
                kept.add(match);
            }
        }
        return kept;
    }

    /**
     * The package of a Java file, derived from its location under the owning source root (the directory layout mirrors
     * the package). Returns {@code ""} for a file directly in a source root (default package) and {@code null} when the
     * file is under no known source root. Both the file and each source root are reduced to project-relative strings via
     * the same {@link PlannerSupport#relative} normalization used to emit the finding {@code path}, so this stays
     * consistent even when javac reports a symlink-resolved source path that would not {@code startsWith} an
     * un-resolved source-root path.
     */
    private String packageOf(Path file) {
        String fileRel = PlannerSupport.relative(projectRoot, file);
        String best = null;
        int bestRootLen = -1;
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                String rootRel = PlannerSupport.relative(projectRoot, root);
                String prefix = rootRel.isEmpty() ? "" : rootRel + "/";
                if (!fileRel.startsWith(prefix) || rootRel.length() <= bestRootLen) {
                    continue;
                }
                String remainder = fileRel.substring(prefix.length());
                int lastSlash = remainder.lastIndexOf('/');
                best = lastSlash < 0 ? "" : remainder.substring(0, lastSlash).replace('/', '.');
                bestRootLen = rootRel.length();
            }
        }
        return best;
    }

    private Recipe resolveRecipe(Map<String, Object> fields) {
        Object recipeId = fields.get("recipeId");
        if (recipeId != null && !recipeId.toString().isBlank()) {
            return BuiltinRecipes.get(recipeId.toString());
        }
        Object recipeObj = fields.get("recipe");
        if (recipeObj instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> recipeMap = (Map<String, Object>) raw;
            return RecipeParser.parse(recipeMap);
        }
        throw new RecipeRefusal("malformed_recipe", "Provide a built-in 'recipeId' or an inline 'recipe' object.");
    }

    private Set<String> referencedTypes(Recipe recipe) {
        Set<String> types = new LinkedHashSet<>();
        for (RecipeRule rule : recipe.rules()) {
            if (TYPE_KINDS.contains(rule.kind())) {
                if (rule.oldType() != null) {
                    types.add(rule.oldType());
                }
            } else if (rule.owner() != null) {
                types.add(rule.owner());
            }
        }
        return types;
    }

    private List<String> unresolvedTypes(Recipe recipe, RecipeMatchIndex matcher) {
        List<String> unresolved = new ArrayList<>();
        for (String type : referencedTypes(recipe)) {
            if (!matcher.typeResolves(type)) {
                unresolved.add(type);
            }
        }
        return unresolved;
    }

    private List<String> unresolvedWarnings(Recipe recipe, RecipeMatchIndex matcher) {
        return unresolvedTypesAsWarnings(unresolvedTypes(recipe, matcher));
    }

    private List<String> unresolvedTypesAsWarnings(List<String> unresolved) {
        if (unresolved.isEmpty()) {
            return List.of();
        }
        return List.of("Recipe references types not on the project classpath (no matches for these): "
                + String.join(", ", unresolved));
    }

    private String seedRelativePath() {
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                return PlannerSupport.relative(projectRoot, file);
            }
        }
        throw new RecipeRefusal("recipe_no_matches", "Project has no Java sources to scan.");
    }

    private String matchesJson(RecipeMatchIndex matcher, List<RecipeMatch> matches) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) out.append(',');
            out.append(findingJson(matcher, matches.get(i)));
        }
        return out.append(']').toString();
    }

    private String groupedMatchesJson(RecipeMatchIndex matcher, List<RecipeMatch> matches) {
        return "{"
                + "\"byRule\":" + groupedMatchesJson(matcher, matches, match -> match.ruleId()) + ","
                + "\"byFile\":" + groupedMatchesJson(matcher, matches, match -> PlannerSupport.relative(projectRoot, match.file())) + ","
                + "\"byRisk\":" + groupedMatchesJson(matcher, matches, RecipeMatch::risk)
                + "}";
    }

    private String groupedMatchesJson(RecipeMatchIndex matcher, List<RecipeMatch> matches,
            java.util.function.Function<RecipeMatch, String> classifier) {
        java.util.Map<String, java.util.List<RecipeMatch>> grouped = new java.util.LinkedHashMap<>();
        for (RecipeMatch match : matches) {
            grouped.computeIfAbsent(classifier.apply(match), key -> new java.util.ArrayList<>()).add(match);
        }
        StringBuilder out = new StringBuilder("[");
        int index = 0;
        for (java.util.Map.Entry<String, java.util.List<RecipeMatch>> entry : grouped.entrySet()) {
            if (index++ > 0) out.append(',');
            out.append("{\"key\":").append(JsonUtil.quote(entry.getKey())).append(",\"matches\":")
                    .append(matchesJson(matcher, entry.getValue())).append('}');
        }
        return out.append(']').toString();
    }

    private String findingJson(RecipeMatchIndex matcher, RecipeMatch match) {
        int line = matcher.lineOf(match.file(), match.start());
        return "{" + "\"ruleId\":" + JsonUtil.quote(match.ruleId()) + "," + "\"ruleKind\":"
                + JsonUtil.quote(match.ruleKind()) + "," + "\"risk\":" + JsonUtil.quote(match.risk()) + ","
                + "\"path\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, match.file())) + "," + "\"line\":"
                + line + "," + "\"startOffset\":" + match.start() + "," + "\"endOffset\":" + match.end() + ","
                + "\"oldText\":" + JsonUtil.quote(match.oldText()) + "," + "\"newText\":"
                + (match.newText() == null ? "null" : JsonUtil.quote(match.newText())) + "," + "\"detail\":"
                + JsonUtil.quote(match.detail()) + "}";
    }

    private String signatureFindingJson(SignatureChangeRule rule, Path file, int line, String risk, String detail) {
        return "{" + "\"ruleId\":" + JsonUtil.quote(rule.id()) + "," + "\"ruleKind\":\"changeMethodSignature\","
                + "\"risk\":" + JsonUtil.quote(risk) + "," + "\"path\":"
                + JsonUtil.quote(file == null ? "" : PlannerSupport.relative(projectRoot, file)) + "," + "\"line\":" + line
                + "," + "\"startOffset\":-1," + "\"endOffset\":-1," + "\"oldText\":\"\"," + "\"newText\":null,"
                + "\"detail\":" + JsonUtil.quote(detail) + "}";
    }

    private static String signatureRefusalDetail(SignatureChangeRule rule, RecipeMatchIndex.DeclarationSite site) {
        if (!site.ownerResolved()) {
            return "Owner type does not resolve on the project classpath: " + rule.owner();
        }
        if (site.matchCount() == 0) {
            return "No method '" + rule.name() + "' on " + rule.owner() + ".";
        }
        if (site.matchCount() > 1) {
            return "Ambiguous: " + site.matchCount() + " overloads of " + rule.owner() + "." + rule.name()
                    + "; add paramTypes to disambiguate.";
        }
        return "Could not locate the declaration source position for " + rule.owner() + "." + rule.name() + ".";
    }

    private static boolean bool(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return fallback;
    }
}
