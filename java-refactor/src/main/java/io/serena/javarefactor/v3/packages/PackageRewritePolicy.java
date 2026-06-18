package io.serena.javarefactor.v3.packages;

import java.util.Map;

/**
 * The configuration policy that governs the non-Java side effects of the v3 package planners
 * (refactor-feature-plan-V3.md §5.4/§5.5): whether a package rename/move also rewrites {@code module-info.java}
 * directives and resource files, whether reflective string literals are rewritten, and which resource kinds are scanned.
 *
 * <p>The defaults mirror the authoritative Python schema ({@code V3PackagesConfig}/{@code V3ResourcesConfig}): module-info
 * and resource rewriting are ON, reflective-string rewriting is OFF (only surfaced as a warning), and every resource kind
 * is scanned. {@link #fromConfig(Map)} reads the effective {@code java_refactor} configuration tree the sidecar already
 * parses, so an operator can disable any of these from project configuration; an absent/empty configuration yields
 * {@link #defaults()}.
 *
 * @param rewriteModuleInfo        rewrite matching {@code exports/opens/uses/provides} directives in {@code module-info.java}
 * @param rewriteResources         rewrite exact fully-qualified names in scanned resource files
 * @param rewriteReflectiveStrings rewrite dynamic/reflective string literals (default {@code false}: surfaced as warnings)
 * @param reportReflectionCandidates surface reflective/string-based references that were NOT rewritten as warnings
 * @param scanXml                  scan {@code *.xml} resources
 * @param scanProperties           scan {@code *.properties} resources
 * @param scanYaml                 scan {@code *.yml}/{@code *.yaml} resources
 * @param scanJson                 scan {@code *.json} resources
 * @param scanServiceLoader        scan {@code META-INF/services/*} ServiceLoader registrations
 * @param rewriteExactClassNames   rewrite an exact dotted FQCN token that names a moved type ({@code com.old.Foo})
 * @param rewritePackagePrefixes   rewrite a standalone package-name token ({@code base-package="com.old"}); default
 *                                 {@code false} because a bare package prefix in a resource is ambiguous (it may be a
 *                                 scanning root or an unrelated string) and is surfaced for review rather than rewritten
 */
public record PackageRewritePolicy(
        boolean rewriteModuleInfo,
        boolean rewriteResources,
        boolean rewriteReflectiveStrings,
        boolean reportReflectionCandidates,
        boolean scanXml,
        boolean scanProperties,
        boolean scanYaml,
        boolean scanJson,
        boolean scanServiceLoader,
        boolean rewriteExactClassNames,
        boolean rewritePackagePrefixes) {

    /** The all-defaults policy, matching the Python {@code V3PackagesConfig}/{@code V3ResourcesConfig} field defaults. */
    public static PackageRewritePolicy defaults() {
        return new PackageRewritePolicy(true, true, false, true, true, true, true, true, true, true, false);
    }

    /**
     * Resolves the policy from the effective (already-parsed) {@code java_refactor} configuration map — the same map
     * {@code Main.effectiveConfigurationMap()} produces. Reads {@code java_refactor.v3.packages} (rewrite_module_info,
     * rewrite_resources, rewrite_reflective_strings) and {@code java_refactor.v3.resources} (enabled, scan_*,
     * report_reflection_candidates). Any missing key falls back to its {@link #defaults()} value; an empty/foreign config
     * therefore yields the defaults.
     */
    public static PackageRewritePolicy fromConfig(Map<?, ?> effectiveConfig) {
        PackageRewritePolicy d = defaults();
        if (effectiveConfig == null || effectiveConfig.isEmpty()) {
            return d;
        }
        Map<?, ?> javaRefactor = asMap(effectiveConfig.get("java_refactor"));
        if (javaRefactor.isEmpty()) {
            javaRefactor = asMap(effectiveConfig.get("javaRefactor"));
        }
        Map<?, ?> v3 = asMap(javaRefactor.get("v3"));
        Map<?, ?> packages = asMap(v3.get("packages"));
        Map<?, ?> resources = asMap(v3.get("resources"));
        // A disabled resources block turns resource rewriting off regardless of the packages.rewrite_resources flag.
        boolean resourcesEnabled = boolValue(resources.get("enabled"), true);
        return new PackageRewritePolicy(
                boolValue(packages.get("rewrite_module_info"), d.rewriteModuleInfo),
                resourcesEnabled && boolValue(packages.get("rewrite_resources"), d.rewriteResources),
                boolValue(packages.get("rewrite_reflective_strings"), d.rewriteReflectiveStrings),
                boolValue(resources.get("report_reflection_candidates"), d.reportReflectionCandidates),
                boolValue(resources.get("scan_xml"), d.scanXml),
                boolValue(resources.get("scan_properties"), d.scanProperties),
                boolValue(resources.get("scan_yaml"), d.scanYaml),
                boolValue(resources.get("scan_json"), d.scanJson),
                boolValue(resources.get("scan_service_loader"), d.scanServiceLoader),
                boolValue(packages.get("rewrite_exact_class_names"), d.rewriteExactClassNames),
                boolValue(packages.get("rewrite_package_prefixes"), d.rewritePackagePrefixes));
    }

    /**
     * Layers per-request boolean overrides over this (config-derived) policy. The package operation tools expose
     * {@code rewriteResources} and {@code rewriteModuleInfo} as explicit per-call parameters (refactor-feature-plan-V3.md
     * §5.4/§5.5); when present in the request {@code fields} they take precedence over the project-configuration value,
     * letting a single rename/move opt out of resource or module-info rewriting without changing project configuration. An
     * absent key leaves the corresponding config value untouched.
     */
    public PackageRewritePolicy withRequestOverrides(Map<?, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return this;
        }
        boolean module = boolValue(fields.get("rewriteModuleInfo"), this.rewriteModuleInfo);
        boolean resources = boolValue(fields.get("rewriteResources"), this.rewriteResources);
        if (module == this.rewriteModuleInfo && resources == this.rewriteResources) {
            return this;
        }
        return new PackageRewritePolicy(
                module,
                resources,
                rewriteReflectiveStrings,
                reportReflectionCandidates,
                scanXml,
                scanProperties,
                scanYaml,
                scanJson,
                scanServiceLoader,
                rewriteExactClassNames,
                rewritePackagePrefixes);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }
}
