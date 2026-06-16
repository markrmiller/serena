package io.serena.javarefactor.operations.extract_interface;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans the optional usage-narrowing pass of extract-interface: rewriting declarations of the concrete type to the
 * newly extracted interface wherever that is provably safe.
 *
 * <p>This unit owns the SEMANTIC classification that the public-API confirmation gate depends on. Every candidate
 * declaration's role (field / parameter / local / record-component / etc.) and whether it is part of a type's visible
 * API surface comes from javac via {@link SemanticIndex.SemanticUsageNarrowing} — the {@code kind} and {@code apiVisible}
 * fields are computed from the resolved {@link javax.lang.model.element.VariableElement}, not from source brace/paren
 * nesting. Consequently fields, parameters, locals, exception parameters, record components, generic declarations and
 * unusually formatted declarations are all classified correctly regardless of layout.
 *
 * <p>For each candidate the rewriter:
 * <ul>
 *   <li>records every unsafe (cast/reflection/serialization-shaped) use and every call to a non-extracted method as a
 *       structured blocking site, collecting ALL blockers across ALL candidates before the planner refuses (G026);</li>
 *   <li>skips emitting an edit for any candidate that has a blocker (it cannot be safely narrowed);</li>
 *   <li>for a fully safe candidate, emits a deep-import-planned type-replacement edit and records the site, additionally
 *       flagging it as API-visible (so the planner's confirmation gate fires) iff the compiler classified the
 *       declaration as part of the type's API surface (G024).</li>
 * </ul>
 */
final class TypeUsageRewriter {

    private final Path projectRoot;
    private final SemanticIndex index;

    TypeUsageRewriter(Path projectRoot, SemanticIndex index) {
        this.projectRoot = projectRoot;
        this.index = index;
    }

    /**
     * The accumulated outcome of planning usage narrowing across every candidate declaration site.
     *
     * @param edits           the narrowing text edits for every safe candidate
     * @param narrowedSites   a description of every narrowed (safe) declaration, API-visible or internal
     * @param apiVisibleSites the subset of narrowed sites the compiler classified as API-visible (and so require
     *                        explicit public-API confirmation under G024)
     * @param blockingSites   every unsafe use / non-extracted call that blocks narrowing (G026)
     */
    record UsageNarrowing(
            List<PlannerSupport.TextEdit> edits,
            List<String> narrowedSites,
            List<String> apiVisibleSites,
            List<String> blockingSites) {
    }

    /**
     * Plans usage-narrowing edits for every candidate declaration of {@code sourceType}, narrowing the declared type to
     * {@code interfaceName} (qualified by {@code targetPackage}) wherever safe. {@code selectedMethodKeys} is the set of
     * erased signature keys of the methods being lifted into the interface; a candidate that calls any method outside
     * this set cannot be narrowed.
     */
    UsageNarrowing plan(
            SemanticIndex.SemanticType sourceType,
            String targetPackage,
            String interfaceName,
            Set<String> selectedMethodKeys) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        List<String> narrowedSites = new ArrayList<>();
        List<String> apiVisibleSites = new ArrayList<>();
        List<String> blockingSites = new ArrayList<>();
        for (SemanticIndex.SemanticUsageNarrowing candidate : index.usageNarrowingCandidates(sourceType)) {
            Path candidateFile = candidate.declarationTypeRange().file();
            String relativePath = PlannerSupport.relative(projectRoot, candidateFile);
            String originalType = candidate.declarationTypeRange().text(index);
            boolean blocked = false;
            for (String unsafeUse : candidate.unsafeUses()) {
                blockingSites.add(relativePath + ": would hide non-interface use (" + unsafeUse + ")");
                blocked = true;
            }
            for (String methodKey : candidate.calledMethodKeys()) {
                if (!selectedMethodKeys.contains(methodKey)) {
                    blockingSites.add(relativePath + ": would hide call to non-extracted method '" + methodKey + "'");
                    blocked = true;
                }
            }
            if (blocked) {
                continue;
            }
            edits.addAll(narrowingEdits(candidate, candidateFile, targetPackage, interfaceName, originalType));
            String kindLabel = candidate.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            narrowedSites.add(relativePath + ": narrows " + kindLabel + " declaration '" + originalType + "' to '"
                    + interfaceName + "'");
            // G024: public-API confirmation is gated on the COMPILER's classification of the declaration, not on source
            // nesting. Only declarations javac marks as API-visible (non-private field / record component / enum
            // constant / parameter of a non-private executable) require confirmation; locals, resources, exception
            // parameters and pattern bindings are internal to one body and proceed without it.
            if (candidate.apiVisible()) {
                apiVisibleSites.add(relativePath + ": narrows API-visible " + kindLabel + " declaration '" + originalType
                        + "' to '" + interfaceName + "'");
            }
        }
        return new UsageNarrowing(edits, narrowedSites, apiVisibleSites, blockingSites);
    }

    /** The deep-import-planned type-replacement edit (plus any required import edits) for one safe candidate. */
    private List<PlannerSupport.TextEdit> narrowingEdits(
            SemanticIndex.SemanticUsageNarrowing candidate,
            Path candidateFile,
            String targetPackage,
            String interfaceName,
            String originalType) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        String candidateSource = index.sourceText(candidateFile).toString();
        ImportManager importPlanner = new ImportManager(candidateSource)
                .withConflictResolver(ImportConflictResolvers.samePackageAndProject(
                        index, candidateFile, index.packageNameOf(candidateFile)));
        ImportManager.TypeUse interfaceUsage = importPlanner.planTypeUsageDeep(
                candidateFile,
                targetPackage.isBlank() ? interfaceName : targetPackage + "." + interfaceName,
                "EXTRACT_INTERFACE_IMPORT");
        edits.add(new PlannerSupport.TextEdit(
                candidate.declarationTypeRange().file(),
                candidate.declarationTypeRange().start(),
                candidate.declarationTypeRange().end(),
                interfaceUsage.renderedType().isBlank() ? originalType : interfaceUsage.renderedType(),
                "EXTRACT_INTERFACE_USAGE"));
        Set<PlannerSupport.TextEdit> seen = new LinkedHashSet<>(edits);
        for (PlannerSupport.TextEdit importEdit : interfaceUsage.importEdits()) {
            if (seen.add(importEdit)) {
                edits.add(importEdit);
            }
        }
        return edits;
    }
}
