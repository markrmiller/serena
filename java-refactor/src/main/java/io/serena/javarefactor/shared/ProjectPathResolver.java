package io.serena.javarefactor.shared;

import java.nio.file.Path;

/** Root-confined path resolver for caller-controlled V2 refactor targets. */
public final class ProjectPathResolver {
    private ProjectPathResolver() {
    }

    public static Path resolveProjectRelative(Path projectRoot, String relativePath, String fieldName) throws Violation {
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute()) {
            throw new Violation("path_outside_project", fieldName + " must be a project-relative path.");
        }
        return requireInsideProject(projectRoot, projectRoot.resolve(requested), fieldName);
    }

    public static Path requireInsideProject(Path projectRoot, Path candidate, String fieldName) throws Violation {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path resolved = candidate.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new Violation("path_outside_project", fieldName + " must stay inside the project root.");
        }
        return resolved;
    }

    /** Structured path-boundary refusal that can cross planner-specific refusal types. */
    public static final class Violation extends Exception {
        private final String code;

        public Violation(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
