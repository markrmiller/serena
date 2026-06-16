package io.serena.javarefactor.operations.extract_interface;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.shared.JavaStyleProfile;
import io.serena.javarefactor.shared.ProjectPathResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates the extracted interface file: it renders each lifted method's signature (type parameters, return type,
 * parameters, throws clause), gathers the imports every component type requires, applies the source file's inferred
 * style, and resolves the interface's destination path under the project's configured source root.
 *
 * <p>Member signatures and their imports are produced by {@link SemanticIndex}'s deep type renderers
 * ({@code renderMethodReturnType}, {@code renderMethodParameters}, {@code renderMethodThrowsClause},
 * {@code renderMethodTypeParameters}), which resolve every component of a nested/generic type and emit the matching
 * import set — so the interface body uses simple names while the header imports each component. The interface's own
 * file (package declaration, sorted imports, member layout) is rendered through {@link JavaStyleProfile} to match the
 * source file's brace/indent style.
 */
final class InterfaceFileSynthesizer {

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final SemanticIndex index;

    InterfaceFileSynthesizer(Path projectRoot, JavaProjectModel model, SemanticIndex index) {
        this.projectRoot = projectRoot;
        this.model = model;
        this.index = index;
    }

    /** A rendered interface method signature together with the imports its component types require. */
    record MethodSignature(
            SemanticIndex.SemanticMethod method,
            String methodKey,
            String name,
            String parameters,
            Set<String> imports,
            String declaration) {
    }

    /**
     * Renders interface signatures for the selected {@code methods}, resolving every component type against
     * {@code targetPackage}. Throws {@link UnsupportedSignatureException} if a method exposes a private or
     * package-inaccessible component type.
     */
    List<MethodSignature> signatures(List<SemanticIndex.SemanticMethod> methods, String targetPackage) {
        List<MethodSignature> signatures = new ArrayList<>();
        for (SemanticIndex.SemanticMethod method : methods) {
            try {
                index.requireSignatureTypesAccessible(method, targetPackage);
            } catch (IllegalArgumentException inaccessible) {
                throw new UnsupportedSignatureException(method.name(), inaccessible.getMessage());
            }
            Set<String> imports = new LinkedHashSet<>();
            String typeParameters = index.renderMethodTypeParameters(method, targetPackage, imports);
            String returnType = index.renderMethodReturnType(method, targetPackage, imports);
            String parameters = String.join(", ", index.renderMethodParameters(method, targetPackage, imports));
            String throwsClause = index.renderMethodThrowsClause(method, targetPackage, imports);
            String prefix = typeParameters.isBlank() ? "" : typeParameters + " ";
            signatures.add(new MethodSignature(
                    method,
                    index.methodSignatureKey(method),
                    method.name(),
                    parameters,
                    imports,
                    "    " + prefix + returnType + " " + method.name() + "(" + parameters + ")" + throwsClause + ";"));
        }
        return signatures;
    }

    /** Renders the full interface source for {@code interfaceName} in {@code targetPackage} using {@code style}. */
    String render(
            String targetPackage, String interfaceName, List<MethodSignature> signatures, JavaStyleProfile style) {
        Set<String> imports = new LinkedHashSet<>();
        for (MethodSignature signature : signatures) {
            imports.addAll(signature.imports());
        }
        List<String> importLines = imports.stream().sorted().map(importName -> "import " + importName + ";").toList();
        List<String> signatureLines = new ArrayList<>();
        for (MethodSignature signature : signatures) {
            signatureLines.add(signature.declaration());
        }
        return style.renderInterfaceSource(targetPackage, interfaceName, importLines, signatureLines);
    }

    /** The fully qualified name of the extracted interface. */
    String fqn(String targetPackage, String interfaceName) {
        return targetPackage.isBlank() ? interfaceName : targetPackage + "." + interfaceName;
    }

    /**
     * Resolves the destination file for the extracted interface under the project model's source root (falling back to a
     * package-derived root for build-tool-less fixtures).
     */
    Path interfaceFile(Path sourceFile, String sourcePackage, String targetPackage, String interfaceName) {
        try {
            Path sourceRoot = sourceRoot(sourceFile, sourcePackage);
            Path base = targetPackage.isBlank() ? sourceRoot : sourceRoot.resolve(targetPackage.replace('.', '/'));
            return ProjectPathResolver.resolveProjectRelative(
                    projectRoot,
                    PlannerSupport.relative(projectRoot, base.resolve(interfaceName + ".java")),
                    "targetPackage");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new PathResolutionException(refusal.code(), refusal.getMessage());
        }
    }

    /**
     * Resolves the source root the new interface file should be placed under. Model-aware first: the project model's
     * source sets are consulted and the most specific source root that actually contains {@code sourceFile} is used, so
     * the interface lands in the correct configured source root (e.g. {@code src/main/java}). Falls back to the
     * package-derived heuristic only when the model has no source root covering the file.
     */
    private Path sourceRoot(Path sourceFile, String sourcePackage) {
        Path normalizedFile = sourceFile.toAbsolutePath().normalize();
        Path modelRoot = modelSourceRoot(normalizedFile);
        if (modelRoot != null) {
            return modelRoot;
        }
        return packageDerivedSourceRoot(normalizedFile, sourcePackage);
    }

    /** The most specific configured source root containing {@code normalizedFile}, or {@code null} if none. */
    private Path modelSourceRoot(Path normalizedFile) {
        if (model == null || model.sourceSets() == null) {
            return null;
        }
        Path best = null;
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (normalizedFile.startsWith(normalizedRoot)
                        && (best == null || normalizedRoot.getNameCount() > best.getNameCount())) {
                    best = normalizedRoot;
                }
            }
        }
        return best;
    }

    /** Heuristic fallback: strip the package's directory segments from the file's parent to recover a source root. */
    private Path packageDerivedSourceRoot(Path normalizedFile, String sourcePackage) {
        Path root = normalizedFile.getParent();
        if (sourcePackage.isBlank()) {
            return root == null ? projectRoot : root;
        }
        String[] parts = sourcePackage.split("\\.");
        for (int index = 0; index < parts.length && root != null; index++) {
            root = root.getParent();
        }
        return root == null ? projectRoot : root;
    }

    /** Raised when a selected method exposes a private or package-inaccessible component type. */
    static final class UnsupportedSignatureException extends RuntimeException {
        private final String methodName;

        UnsupportedSignatureException(String methodName, String detail) {
            super(detail);
            this.methodName = methodName;
        }

        String methodName() {
            return methodName;
        }
    }

    /** Raised when the interface destination path escapes the project or is otherwise invalid. */
    static final class PathResolutionException extends RuntimeException {
        private final String code;

        PathResolutionException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
