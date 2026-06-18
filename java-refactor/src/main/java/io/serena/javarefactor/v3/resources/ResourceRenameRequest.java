package io.serena.javarefactor.v3.resources;

import java.util.Map;

/**
 * A batch request to rewrite resource references for one or more moved Java types/packages (refactor-feature-plan-V3.md
 * §15, "planEdits" half of the SPI). A package rename/move resolves to BOTH a per-type FQN map (every type whose
 * fully-qualified name changed) and a package map (every package prefix that changed); a provider rewrites exact class
 * tokens from {@code typeFqnMap} and, only when {@code rewritePackagePrefixes} is enabled, bare package tokens from
 * {@code packageMap}.
 *
 * @param typeFqnMap             old fully-qualified class name -> new fully-qualified class name
 * @param packageMap             old package name -> new package name
 * @param rewriteExactClassNames rewrite a dotted token equal to a moved type's old FQN (HIGH confidence)
 * @param rewritePackagePrefixes rewrite a bare token equal to a moved package's old name (MEDIUM confidence)
 */
public record ResourceRenameRequest(
        Map<String, String> typeFqnMap,
        Map<String, String> packageMap,
        boolean rewriteExactClassNames,
        boolean rewritePackagePrefixes) {

    public ResourceRenameRequest {
        typeFqnMap = typeFqnMap == null ? Map.of() : Map.copyOf(typeFqnMap);
        packageMap = packageMap == null ? Map.of() : Map.copyOf(packageMap);
    }
}
