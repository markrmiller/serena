package io.serena.javarefactor.v3.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Java symbol view of the project (refactor-feature-plan-V3.md §1.2): every declared type and member, plus the two
 * lookup maps consumers need most — {@code package -> source roots} and {@code type FQN -> declaring file}.
 *
 * <p>Every node here is a real javac-resolved declaration (sourced from
 * {@link io.serena.javarefactor.compiler.TransformationGraphFacts}), so the FQNs, file paths, and visibility flags are
 * compiler truth, not name heuristics. The maps are exact: {@link #packageToSourceRoots()} records, for each declared
 * package, the project-relative source roots that contain at least one of its types, and {@link #typeToFile()} maps each
 * top-level type FQN to its declaring file. The {@code resolveSimpleName} resolver is intentionally conservative — an
 * explicit single-type import wins, then a same-package type; anything else yields {@code null} so callers record it as
 * unresolved rather than guessing.
 *
 * @param typesByFqn           every type node keyed by FQN (top-level and nested)
 * @param members             every method/constructor/field declaration
 * @param packageToSourceRoots package name -> project-relative source roots declaring its types
 * @param typeToFile          top-level type FQN -> project-relative declaring file
 * @param filesByPackage      package name -> project-relative declaring files
 * @param publicApiFqns       the subset of type FQNs whose declaration carries public/protected visibility
 */
public record JavaSymbolGraph(
        Map<String, TypeNode> typesByFqn,
        List<MemberNode> members,
        Map<String, Set<String>> packageToSourceRoots,
        Map<String, String> typeToFile,
        Map<String, Set<String>> filesByPackage,
        Set<String> publicApiFqns) {

    /**
     * A declared Java type.
     *
     * @param fqn          fully-qualified name
     * @param simpleName   simple name
     * @param packageName  declaring package (empty for the default package)
     * @param kind         {@code class}/{@code interface}/{@code enum}/{@code record}/{@code annotation}
     * @param relativePath project-relative declaring file
     * @param topLevel     whether this is a top-level (not nested) type
     * @param publicApi    whether the declaration is public/protected
     * @param testSource   whether the declaration lives in a test source set
     */
    public record TypeNode(
            String fqn,
            String simpleName,
            String packageName,
            String kind,
            String relativePath,
            boolean topLevel,
            boolean publicApi,
            boolean testSource) {
    }

    /**
     * A declared member (method/constructor/field).
     *
     * @param key          canonical SemanticKey of the member
     * @param ownerFqn     owning type FQN
     * @param name         member name
     * @param memberKind   {@code method}/{@code constructor}/{@code field}
     * @param arity        parameter count (0 for fields)
     * @param relativePath project-relative declaring file
     * @param publicApi    whether the member is public/protected
     * @param testSource   whether the member lives in a test source set
     */
    public record MemberNode(
            String key,
            String ownerFqn,
            String name,
            String memberKind,
            int arity,
            String relativePath,
            boolean publicApi,
            boolean testSource) {
    }

    /** All top-level types declared in {@code packageName}. */
    public List<TypeNode> typesInPackage(String packageName) {
        return typesByFqn.values().stream()
                .filter(node -> node.topLevel() && node.packageName().equals(packageName))
                .toList();
    }

    /**
     * Resolves a simple type name to an FQN using imports first, then same-package, else {@code null}.
     *
     * <p>Conservative by design: an explicit single-type import wins; otherwise a same-package type that this graph
     * actually declares; a wildcard import or an unknown name yields {@code null} so the caller records it as unresolved.
     */
    public String resolveSimpleName(String simpleName, String inPackage, List<String> imports) {
        for (String imported : imports) {
            if (imported.endsWith("." + simpleName)) {
                return imported;
            }
        }
        String candidate = inPackage == null || inPackage.isEmpty()
                ? simpleName
                : inPackage + "." + simpleName;
        return typesByFqn.containsKey(candidate) ? candidate : null;
    }
}
