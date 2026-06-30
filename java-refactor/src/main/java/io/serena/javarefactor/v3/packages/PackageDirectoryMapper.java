package io.serena.javarefactor.v3.packages;

import java.nio.file.Path;

/** Planned V3 package-directory mapper facade. */
public final class PackageDirectoryMapper {
    private PackageDirectoryMapper() {}

    public static Path packagePath(Path sourceRoot, String packageName) {
        Path current = sourceRoot;
        if (packageName == null || packageName.isBlank()) {
            return current;
        }
        for (String segment : packageName.split("\\.")) {
            current = current.resolve(segment);
        }
        return current;
    }
}
