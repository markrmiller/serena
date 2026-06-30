package io.serena.javarefactor.v3.frameworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of built-in {@link FrameworkPlugin}s (refactor-feature-plan-V3.md §16). */
public final class FrameworkRegistry {
    record Owner(String frameworkId, String role) {
    }

    private static volatile Map<?, ?> configuredFrameworks = Map.of();

    private final List<FrameworkPlugin> plugins;
    private final Map<String, Owner> annotationOwners = new LinkedHashMap<>();

    public static void configure(Map<?, ?> effectiveConfig) {
        Map<?, ?> javaRefactor = asMap(effectiveConfig.get("java_refactor"));
        if (javaRefactor.isEmpty()) {
            javaRefactor = asMap(effectiveConfig.get("javaRefactor"));
        }
        Map<?, ?> v3 = asMap(javaRefactor.get("v3"));
        configuredFrameworks = asMap(v3.get("frameworks"));
    }

    FrameworkRegistry() {
        Map<?, ?> config = configuredFrameworks;
        if (Boolean.FALSE.equals(config.get("enabled"))) {
            this.plugins = List.of();
            return;
        }
        List<FrameworkPlugin> enabled = new ArrayList<>();
        add(enabled, "spring", FrameworkPlugins.spring(), config);
        add(enabled, "jakarta_persistence", FrameworkPlugins.jpa(), config);
        add(enabled, "jackson", FrameworkPlugins.jackson(), config);
        add(enabled, "junit", FrameworkPlugins.junit(), config);
        this.plugins = List.copyOf(enabled);
        for (FrameworkPlugin plugin : plugins) {
            for (Map.Entry<String, String> entry : plugin.annotationRoles().entrySet()) {
                annotationOwners.put(entry.getKey(), new Owner(plugin.id(), entry.getValue()));
            }
        }
    }

    List<FrameworkPlugin> plugins() {
        return plugins;
    }

    Owner ownerOf(String annotationFqn) {
        return annotationOwners.get(annotationFqn);
    }

    private static void add(List<FrameworkPlugin> plugins, String key, FrameworkPlugin plugin, Map<?, ?> config) {
        Object mode = config.get(key);
        if (!"off".equals(String.valueOf(mode))) {
            plugins.add(plugin);
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
