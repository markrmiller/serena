package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Which resource file kinds the {@link ResourcePlanner} is allowed to scan (refactor-feature-plan-V3.md §5.5/§15). This
 * mirrors the {@code scan_*} flags of the package-rewrite policy but lives in the resource SPI package so the SPI does
 * not depend on the package-operation layer; callers translate their policy into a scope.
 *
 * @param scanXml           scan {@code *.xml} resources
 * @param scanProperties    scan {@code *.properties} resources
 * @param scanYaml          scan {@code *.yml}/{@code *.yaml} resources
 * @param scanJson          scan {@code *.json} resources
 * @param scanServiceLoader scan {@code META-INF/services/*} ServiceLoader registrations
 */
public record ResourceScanScope(
        boolean scanXml,
        boolean scanProperties,
        boolean scanYaml,
        boolean scanJson,
        boolean scanServiceLoader) {

    /** Every resource kind enabled. */
    public static ResourceScanScope all() {
        return new ResourceScanScope(true, true, true, true, true);
    }

    /** Whether {@code file} is one of the enabled resource kinds. */
    public boolean scannable(Path file) {
        if (file.getFileName() == null) {
            return false;
        }
        if (scanServiceLoader && isUnderMetaInfServices(file)) {
            return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (scanXml && name.endsWith(".xml")) {
            return true;
        }
        if (scanProperties && name.endsWith(".properties")) {
            return true;
        }
        if (scanYaml && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return true;
        }
        return scanJson && name.endsWith(".json");
    }

    static boolean isUnderMetaInfServices(Path file) {
        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("services")
                && parent.getParent() != null && parent.getParent().getFileName() != null
                && parent.getParent().getFileName().toString().equals("META-INF");
    }
}
