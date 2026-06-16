package io.serena.javarefactor.operations.hierarchy;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Computes <em>true</em> method override groups over a set of project types using the canonical javac override
 * relation ({@link Elements#overrides}) rather than name-only heuristics.
 *
 * <p>Per JLS §8.4.8, two methods belong to the same override group iff they are connected through the "overrides"
 * relation across the supertype graph. This collapses covariant-return overrides and generic substitutions
 * ({@code List<String>} vs {@code List<T>}) into one group while keeping overloads (same name, different erased
 * parameter types) and statically-hidden methods apart, exactly as {@link Elements#overrides} decides.
 *
 * <p>Groups are reported as sets of caller-supplied stable keys (typically {@code SemanticKey} canonical strings) so a
 * caller can map them onto {@link MemberDescriptor}s without holding {@link Element} identity. Only multi-member
 * groups are returned — a method that overrides nothing and is overridden by nothing carries no grouping information.
 */
public final class OverrideGroupComputer {
    private OverrideGroupComputer() {}

    /**
     * @param projectTypes the project's resolved {@link TypeElement}s (top-level and nested, flattened)
     * @param elements     the compiler-task {@link Elements} used to evaluate the override relation
     * @param keyFn        maps a method element to its stable cross-task key
     * @return the non-singleton override groups, each a set of stable keys
     */
    public static List<Set<String>> compute(
            Collection<? extends TypeElement> projectTypes,
            Elements elements,
            Function<ExecutableElement, String> keyFn) {
        // Bucket candidate methods by (name, arity): only methods sharing both can possibly override one another, so
        // this bounds the pairwise Elements.overrides() probes to within a bucket.
        Map<String, List<ExecutableElement>> buckets = new LinkedHashMap<>();
        for (TypeElement type : projectTypes) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD) {
                    String bucketKey = method.getSimpleName().toString() + "/" + method.getParameters().size();
                    buckets.computeIfAbsent(bucketKey, ignored -> new ArrayList<>()).add(method);
                }
            }
        }

        List<Set<String>> groups = new ArrayList<>();
        for (List<ExecutableElement> bucket : buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            // Union-find over the override relation within the bucket.
            int[] parent = new int[bucket.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
            }
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    if (overrideRelated(elements, bucket.get(i), bucket.get(j))) {
                        union(parent, i, j);
                    }
                }
            }
            Map<Integer, Set<String>> byRoot = new HashMap<>();
            for (int i = 0; i < bucket.size(); i++) {
                String key = keyFn.apply(bucket.get(i));
                byRoot.computeIfAbsent(find(parent, i), ignored -> new LinkedHashSet<>()).add(key);
            }
            for (Set<String> component : byRoot.values()) {
                if (component.size() > 1) {
                    groups.add(component);
                }
            }
        }
        return groups;
    }

    private static boolean overrideRelated(Elements elements, ExecutableElement a, ExecutableElement b) {
        if (a.equals(b)
                || !(a.getEnclosingElement() instanceof TypeElement at)
                || !(b.getEnclosingElement() instanceof TypeElement bt)) {
            return false;
        }
        // Elements.overrides already encodes subtype semantics from the viewpoint type; probe both directions so either
        // orientation of the supertype/subtype pair establishes the edge.
        return elements.overrides(a, b, at) || elements.overrides(b, a, bt);
    }

    private static int find(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot != rightRoot) {
            parent[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
        }
    }
}
