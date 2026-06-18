package io.serena.javarefactor.v3.frameworks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of built-in {@link FrameworkPlugin}s (refactor-feature-plan-V3.md §16) plus an index from each
 * fully-qualified annotation type to the framework + role that owns it.
 */
final class FrameworkRegistry {

    /** Which framework owns an annotation, and the role it assigns. */
    record Owner(String frameworkId, String role) {
    }

    private final List<FrameworkPlugin> plugins;
    private final Map<String, Owner> annotationOwners;

    FrameworkRegistry() {
        this.plugins = List.of(
                FrameworkPlugins.spring(),
                FrameworkPlugins.jpa(),
                FrameworkPlugins.jackson(),
                FrameworkPlugins.junit());
        Map<String, Owner> owners = new LinkedHashMap<>();
        for (FrameworkPlugin plugin : plugins) {
            for (Map.Entry<String, String> entry : plugin.annotationRoles().entrySet()) {
                owners.putIfAbsent(entry.getKey(), new Owner(plugin.id(), entry.getValue()));
            }
        }
        this.annotationOwners = Map.copyOf(owners);
    }

    List<FrameworkPlugin> plugins() {
        return plugins;
    }

    /** The framework+role that owns {@code annotationFqn}, or {@code null} if no plugin claims it. */
    Owner ownerOf(String annotationFqn) {
        return annotationOwners.get(annotationFqn);
    }
}
