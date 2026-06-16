package io.serena.javarefactor.shared;

import io.serena.javarefactor.ast.RefactorAnalysisResult;
import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.ast.TargetHints;
import io.serena.javarefactor.compiler.SemanticIndex;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Shared semantic-target identity gate for every V2 refactoring operation (feature plan Blocker&nbsp;3).
 *
 * <p>V2 planners historically selected their target by source LINE only and, when more than one declaration matched,
 * silently picked the narrowest — exactly the lossy-position guess that {@link TargetHints} exists to refuse. This gate
 * gives all V2 operations the same rigor V1 rename already has: it re-resolves the caller's position through javac
 * ({@link SemanticIndex#resolveTarget}), proves the resolved element IS the caller's named symbol (simple name, coarse
 * kind, and — for executables — parameter arity) via {@link TargetHints#mismatch}, refuses non-editable origins, and
 * refuses the residual same-line overload ambiguity that name-hint resolution alone cannot separate. Each failure
 * carries a structured refusal code drawn from the existing inventory:
 * <ul>
 *   <li>{@code target_not_found} — the position resolves to no refactorable symbol (this already covers a loose
 *       position that would otherwise climb to an enclosing declaration, and a same-line sibling the cursor does not
 *       sit inside without a matching name-hint: {@link SemanticIndex#resolveTarget} returns no target there);</li>
 *   <li>{@code target_mismatch} — name-path / kind / overload-arity hint does not match the resolved element;</li>
 *   <li>{@code non_editable_target} — generated, out-of-tree, or classpath-only origin;</li>
 *   <li>{@code ambiguous_member_selection} — two overloads of the same simple name share the requested line and
 *       neither a precise column nor an arity hint pinned one.</li>
 * </ul>
 *
 * <p>Apply-time re-resolution is enforced by the session layer: {@code applySession} re-runs the planner and refuses
 * with {@code target_identity_changed} when the re-resolved {@code target.semanticKey} differs from the previewed one.
 * Because the planner now resolves through THIS gate, that comparison is against a verified key — closing the
 * pre-plan and apply halves of the identity guarantee together.
 */
public final class SemanticTargetGate {
    private SemanticTargetGate() {}

    /** Structured refusal raised when the caller's target cannot be proven. Carries an inventory refusal code. */
    public static final class Refused extends RuntimeException {
        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Builds target-identity hints from flattened request fields, mirroring Main's V1 helper. */
    public static TargetHints hints(Map<String, Object> fields) {
        Object name = fields.get("nameHint");
        Object kind = fields.get("kindHint");
        Object arity = fields.get("arityHint");
        return new TargetHints(
                name instanceof String value ? value : null,
                kind instanceof String value ? value : null,
                arity instanceof Number value ? value.longValue() : -1L);
    }

    /**
     * Resolves and verifies the semantic target named by {@code fields} (its {@code line}/{@code column} and
     * {@code nameHint}/{@code kindHint}/{@code arityHint}), refusing any target that cannot be proven.
     *
     * <p>Returns {@code null} — engaging NO verification — unless the caller supplied BOTH a one-based
     * {@code line}/{@code column} AND at least one identity hint ({@code nameHint}/{@code kindHint}/{@code arityHint}).
     * This mirrors V1 exactly: "absent hints verify nothing — position-only callers keep working." A raw position
     * carries no claim about WHICH symbol was intended, so verifying it against javac resolution would only second-guess
     * the operation's own (line-range) selection and could refuse a valid target whose column sits just outside the
     * identifier span. Whenever Serena resolves a {@code name_path} through the language server, a precise line, column,
     * AND the resolved symbol's name/kind/arity all travel together, so the standard targeting path always engages the
     * gate; hand-built position-only protocol requests defer to the operation, which then emits its own specific
     * refusal (never a generic {@code target_not_found} from this gate).
     *
     * @throws Refused when a hinted target is missing, mismatched, non-editable, or ambiguous
     */
    public static ResolvedTarget require(SemanticIndex index, String relativePath, Map<String, Object> fields)
            throws IOException {
        return require(index, relativePath, longField(fields, "line"), longField(fields, "column"), hints(fields));
    }

    /**
     * Resolves and verifies the semantic target at {@code (line, column)} against {@code hints}, or returns {@code null}
     * when there is nothing to verify (see {@link #require(SemanticIndex, String, Map)}).
     */
    public static ResolvedTarget require(
            SemanticIndex index, String relativePath, long line, long column, TargetHints hints) throws IOException {
        if (line <= 0 || column <= 0 || hints.isEmpty()) {
            // No verifiable identity claim (position-only or name-only caller): leave the operation's own target
            // selection and refusal logic untouched, exactly as V1 leaves un-hinted callers unverified.
            return null;
        }
        RefactorAnalysisResult analysis = index.resolveTarget(relativePath, line, column, hints.nameHint());
        ResolvedTarget target = analysis.target();
        if (target == null) {
            throw new Refused(
                    "target_not_found", "No refactorable Java symbol was found at the requested position.");
        }
        // Identity gate (before any op-specific logic): prove the position-resolved element IS the named symbol.
        String mismatch = hints.mismatch(target);
        if (mismatch != null) {
            throw new Refused("target_mismatch", "Refused to plan against an unverified target: " + mismatch);
        }
        // Centralized origin/editability gate: refuse generated roots, external source attachments, classpath binaries.
        String origin = index.targetOriginRefusal(target);
        if (origin != null) {
            throw new Refused("non_editable_target", origin);
        }
        requireUnambiguousOverload(index, relativePath, line, column, hints, target);
        return target;
    }

    /**
     * Confirms a planner's independently selected element is the SAME semantic element the gate verified, refusing
     * {@code target_mismatch} otherwise. Use after a planner performs its own line-based selection so the operation can
     * only ever edit the proven target. Canonical keys are compared the same way every V2 preview emits them
     * ({@link SemanticKey#from(Element)}), so the two sides are directly comparable within one compiler task.
     */
    public static void confirmSelection(ResolvedTarget verified, Element selected) {
        if (verified == null) {
            return; // no positional target was verified (name-only selection); nothing to cross-check
        }
        if (selected == null) {
            throw new Refused("target_mismatch", "The planner resolved no element for the verified semantic target.");
        }
        String want = SemanticKey.from(verified.element()).canonical();
        String got = SemanticKey.from(selected).canonical();
        if (!want.equals(got)) {
            throw new Refused(
                    "target_mismatch",
                    "The declaration selected by position (" + got + ") is not the verified semantic target (" + want
                            + ").");
        }
    }

    /**
     * Refuses when the resolved executable's simple name has more than one overload on the requested line and neither a
     * precise column nor an arity hint singled one out. A name-hint resolves both overloads equally, so without arity
     * or a column that lands inside one identifier, picking the narrowest would be a silent guess.
     */
    private static void requireUnambiguousOverload(
            SemanticIndex index, String relativePath, long line, long column, TargetHints hints, ResolvedTarget resolved)
            throws IOException {
        if (line <= 0 || !(resolved.element() instanceof ExecutableElement)) {
            return;
        }
        if (hints.arityHint() >= 0) {
            return; // arity pins the overload
        }
        // Did the column alone (ignoring the name hint) land precisely inside the resolved identifier?
        RefactorAnalysisResult byColumn = index.resolveTarget(relativePath, line, column, null);
        if (byColumn.target() != null
                && byColumn.target().key().canonical().equals(resolved.key().canonical())) {
            return; // precise column pinned the overload
        }
        String simpleName = resolved.element().getSimpleName().toString();
        long sameNameOnLine = index.declarationsOnLine(relativePath, line).stream()
                .filter(candidate -> candidate.element() instanceof ExecutableElement)
                .filter(candidate -> candidate.element().getSimpleName().contentEquals(simpleName))
                .count();
        if (sameNameOnLine > 1) {
            throw new Refused(
                    "ambiguous_member_selection",
                    "Refused to plan against an ambiguous target: multiple overloads of '" + simpleName
                            + "' are declared on line " + line
                            + "; pass the parameter arity (arityHint) or a precise column to select one.");
        }
    }

    private static long longField(Map<String, Object> fields, String key) {
        return fields.get(key) instanceof Number value ? value.longValue() : -1L;
    }
}
