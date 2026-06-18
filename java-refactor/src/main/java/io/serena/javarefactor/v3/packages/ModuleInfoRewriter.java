package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Java Platform Module System descriptor ({@code module-info.java}) when a package is renamed or moved
 * (refactor-feature-plan-V3.md §5.4). A {@code module-info.java} carries no type bodies — only directives — so it is
 * excluded from the planners' generic reference rewriting and handled here exactly once, avoiding double edits and the
 * over-broad prefix rewrite the generic scanner would otherwise apply to an {@code exports com.old.api;} of a NON-moved
 * subpackage.
 *
 * <p>The following directives are rewritten when their referent actually moves:
 * <ul>
 *   <li>{@code exports <pkg>;} / {@code exports <pkg> to <modules>;} — the package token, preserving the {@code to} list;</li>
 *   <li>{@code opens <pkg>;} / {@code opens <pkg> to <modules>;} — the package token, preserving the {@code to} list;</li>
 *   <li>{@code uses <Service>;} — the service type's fully-qualified name;</li>
 *   <li>{@code provides <Service> with <Impl>, <Impl>;} — the service AND every implementation FQN.</li>
 * </ul>
 * {@code requires <module>} and the {@code to <module>} target lists name MODULES, not packages, and are never touched.
 * A package is rewritten via {@code packageMapper} (which returns the destination package, or {@code null} when the
 * package does not move); a {@code uses}/{@code provides} type is rewritten via {@code typeFqcnMap} (an exact
 * old-FQN → new-FQN map of the moved types), so a service/impl in a NON-moved (sub)package is left untouched.
 *
 * <p><b>§5.4 module-aware rules.</b> Beyond the mechanical rewrite this enforces:
 * <ul>
 *   <li><b>Preserve {@code to} target lists</b> — the {@code exports/opens} rewrite only replaces the package token, so an
 *       {@code exports com.old.api to some.module;} keeps {@code to some.module} (the regex never captures the target list).</li>
 *   <li><b>Remove a stale/duplicate directive when safe</b> — when a package MOVE merges {@code com.a} into an existing
 *       {@code com.b} that the descriptor ALSO exports/opens, naively rewriting the token would emit a SECOND
 *       {@code exports com.b;} (a {@code duplicate export} javac error). Instead the now-redundant source directive is
 *       DELETED. If the surviving target directive has a different {@code to} target list, the deletion is still performed
 *       (a duplicate export cannot compile) but flagged with a warning so the changed exposure can be reviewed.</li>
 *   <li><b>Warn on a partially-moved {@code provides} list</b> — when a {@code provides … with} clause ends up spanning
 *       both moved and non-moved implementation packages, the (compilable) result is surfaced as a warning for review.</li>
 * </ul>
 * The remaining §5.4 rule — <i>refuse a package that is split (owned) across more than one module descriptor unless an
 * explicit module strategy is supplied</i> — is enforced by the planners using {@link #ownedPackages(String)} (the check
 * needs the full set of descriptors and the request's {@code moduleStrategy} field, which live at the planner level).
 */
final class ModuleInfoRewriter {
    /** A dotted Java name (package or fully-qualified type) as it appears in a module directive. */
    private static final Pattern DOTTED = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    /**
     * A whole {@code exports}/{@code opens} statement: indent (1), keyword (2), package (3), an optional {@code to …}
     * target clause (4, including the leading {@code to}), and the trailing newline (5). Capturing the full statement lets
     * a redundant directive be deleted line-and-all, while group 3 alone is replaced for the ordinary in-place rewrite.
     */
    private static final Pattern EXPORTS_OPENS = Pattern.compile(
            "(?m)^([ \\t]*)(exports|opens)[ \\t]+([A-Za-z_$][A-Za-z0-9_$.]*)((?:[ \\t]+to\\b[^;]*)?)[ \\t]*;[ \\t]*(\\r?\\n)?");
    private static final Pattern USES = Pattern.compile(
            "(?m)^[ \\t]*uses[ \\t]+([A-Za-z_$][A-Za-z0-9_$.]*)");
    private static final Pattern PROVIDES = Pattern.compile(
            "(?m)^[ \\t]*provides[ \\t]+([A-Za-z_$][A-Za-z0-9_$.]*)[ \\t]+with[ \\t]+([^;]*);");

    private ModuleInfoRewriter() {
    }

    /** The text edits and caveats produced for one {@code module-info.java}. */
    record Result(List<PlannerSupport.TextEdit> edits, List<String> warnings) {
    }

    /** Whether a file is a module descriptor (matched by name; it carries no normal package declaration). */
    static boolean isModuleInfo(Path file) {
        return file.getFileName() != null && file.getFileName().toString().equals("module-info.java");
    }

    /**
     * The set of packages this descriptor OWNS — i.e. names in an {@code exports} or {@code opens} directive. JPMS allows
     * a package to be exported/opened by exactly one module, so an intersection of this set with the moved packages, found
     * in more than one descriptor, identifies a package split across modules (refactor-feature-plan-V3.md §5.4).
     */
    static Set<String> ownedPackages(String source) {
        Set<String> owned = new LinkedHashSet<>();
        Matcher exportsOpens = EXPORTS_OPENS.matcher(source);
        while (exportsOpens.find()) {
            owned.add(exportsOpens.group(3));
        }
        return owned;
    }

    static Result rewrite(Path file, String source, Function<String, String> packageMapper,
            Map<String, String> typeFqcnMap) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        rewriteExportsOpens(file, source, packageMapper, edits, warnings);
        rewriteUses(file, source, typeFqcnMap, edits);
        rewriteProvides(file, source, typeFqcnMap, edits, warnings);

        if (!edits.isEmpty()) {
            warnings.add(0, "Updated the module descriptor '" + file.getFileName()
                    + "': rewrote " + edits.size() + " directive reference(s) for the moved package(s).");
        }
        return new Result(edits, warnings);
    }

    /**
     * Rewrites each {@code exports}/{@code opens} package token that moves, preserving the {@code to} target list. When the
     * destination package is ALREADY exported/opened by another directive of the same kind (a MOVE that merges into an
     * existing package), the source directive is deleted instead of rewritten — a second {@code exports <dest>;} would be a
     * {@code duplicate export} javac error (§5.4: remove a stale directive when safe).
     */
    private static void rewriteExportsOpens(Path file, String source, Function<String, String> packageMapper,
            List<PlannerSupport.TextEdit> edits, List<String> warnings) {
        // Pass 1: index the (kind, package) -> target-list of every existing directive, so a rewrite can detect when its
        // destination package is already declared by a sibling directive of the same kind.
        Map<String, String> presentTargets = new java.util.HashMap<>();
        Matcher present = EXPORTS_OPENS.matcher(source);
        while (present.find()) {
            presentTargets.put(present.group(2) + ' ' + present.group(3), normalizeTargets(present.group(4)));
        }

        Matcher matcher = EXPORTS_OPENS.matcher(source);
        while (matcher.find()) {
            String kind = matcher.group(2);
            String pkg = matcher.group(3);
            String mapped = packageMapper.apply(pkg);
            if (mapped == null || mapped.equals(pkg)) {
                continue;
            }
            if (presentTargets.containsKey(kind + ' ' + mapped)) {
                // The destination package is already exported/opened by another directive: deleting this now-redundant
                // source directive keeps the descriptor compilable (a duplicate export/opens does not compile).
                edits.add(new PlannerSupport.TextEdit(
                        file, matcher.start(), matcher.end(), "", "MODULE_DIRECTIVE"));
                String thisTargets = normalizeTargets(matcher.group(4));
                String survivingTargets = presentTargets.get(kind + ' ' + mapped);
                if (thisTargets.equals(survivingTargets)) {
                    warnings.add("Removed redundant '" + kind + ' ' + pkg + "' from the module descriptor '"
                            + file.getFileName() + "': the moved package merges into '" + mapped
                            + "', which is already " + kind + "ed (§5.4).");
                } else {
                    warnings.add("Removed '" + kind + ' ' + pkg + "' from the module descriptor '" + file.getFileName()
                            + "' because the moved package merges into the already-" + kind + "ed '" + mapped
                            + "'; the removed directive's 'to' target list differed from the surviving one, so review the "
                            + "module exposure of '" + mapped + "' (§5.4).");
                }
                continue;
            }
            edits.add(new PlannerSupport.TextEdit(
                    file, matcher.start(3), matcher.end(3), mapped, "MODULE_DIRECTIVE"));
        }
    }

    private static void rewriteUses(Path file, String source, Map<String, String> typeFqcnMap,
            List<PlannerSupport.TextEdit> edits) {
        Matcher uses = USES.matcher(source);
        while (uses.find()) {
            addTypeEdit(edits, file, source, uses.start(1), uses.end(1), typeFqcnMap);
        }
    }

    private static void rewriteProvides(Path file, String source, Map<String, String> typeFqcnMap,
            List<PlannerSupport.TextEdit> edits, List<String> warnings) {
        Matcher provides = PROVIDES.matcher(source);
        while (provides.find()) {
            addTypeEdit(edits, file, source, provides.start(1), provides.end(1), typeFqcnMap);
            // The `with` clause is a comma-separated list of implementation FQNs; rewrite each moved one in place and
            // track whether the list ends up spanning both moved and non-moved implementation packages (§5.4 rule 1).
            int movedImpls = 0;
            int unmovedImpls = 0;
            Matcher impls = DOTTED.matcher(source).region(provides.start(2), provides.end(2));
            while (impls.find()) {
                String fqcn = source.substring(impls.start(), impls.end());
                String mapped = typeFqcnMap.get(fqcn);
                if (mapped != null && !mapped.equals(fqcn)) {
                    edits.add(new PlannerSupport.TextEdit(file, impls.start(), impls.end(), mapped, "MODULE_DIRECTIVE"));
                    movedImpls++;
                } else {
                    unmovedImpls++;
                }
            }
            if (movedImpls > 0 && unmovedImpls > 0) {
                warnings.add("The 'provides " + source.substring(provides.start(1), provides.end(1))
                        + "' directive in '" + file.getFileName() + "' now lists implementations from both moved and "
                        + "non-moved packages (partial move); the descriptor still compiles but review whether all "
                        + "implementations should move together (§5.4).");
            }
        }
    }

    private static void addTypeEdit(List<PlannerSupport.TextEdit> edits, Path file, String source, int start, int end,
            Map<String, String> typeFqcnMap) {
        String fqcn = source.substring(start, end);
        String mapped = typeFqcnMap.get(fqcn);
        if (mapped != null && !mapped.equals(fqcn)) {
            edits.add(new PlannerSupport.TextEdit(file, start, end, mapped, "MODULE_DIRECTIVE"));
        }
    }

    /** Normalizes a {@code to} target clause to a stable, comparable key (sorted module names, or {@code ""} when absent). */
    private static String normalizeTargets(String toClause) {
        if (toClause == null || toClause.isBlank()) {
            return "";
        }
        String list = toClause.trim();
        if (list.startsWith("to")) {
            list = list.substring(2);
        }
        Set<String> modules = new TreeSet<>();
        for (String module : list.split(",")) {
            String trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                modules.add(trimmed);
            }
        }
        return String.join(",", modules);
    }
}
