package io.serena.javarefactor.v3.resources;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared helpers for the resource-reference SPI: discovering resource directories, walking resource files, and
 * extracting maximal dotted tokens. Mirrors the resource-root discovery used by
 * {@code io.serena.javarefactor.v3.packages.ResourceRewriter} (whose own helper is package-private).
 */
final class ResourceSupport {

    /** A maximal dotted identifier token, e.g. {@code com.example.Foo} or {@code Foo}. */
    static final Pattern DOTTED =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private ResourceSupport() {
    }

    /** A matched dotted token together with its offsets in the source text. */
    record Token(String text, int start, int end) {
    }

    /**
     * Resource directories of the project: each {@code .../resources} source root and the {@code resources} sibling of
     * every Java source root. Matches {@code ResourceRewriter.resourceDirectories}.
     */
    static Set<Path> resourceDirectories(JavaProjectModel model) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (normalized.getFileName() != null
                        && normalized.getFileName().toString().equals("resources")) {
                    addIfDirectory(dirs, normalized);
                }
                Path parent = normalized.getParent();
                if (parent != null) {
                    addIfDirectory(dirs, parent.resolve("resources"));
                }
            }
        }
        return dirs;
    }

    private static void addIfDirectory(Set<Path> dirs, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            dirs.add(normalized);
        }
    }

    /** All regular files under the project's resource directories, in a stable order. */
    static List<Path> resourceFiles(JavaProjectModel model) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : resourceDirectories(model)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .map(p -> p.toAbsolutePath().normalize())
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** Extract every maximal dotted token from {@code content}, with offsets. */
    static List<Token> dottedTokens(String content) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = DOTTED.matcher(content);
        while (m.find()) {
            tokens.add(new Token(m.group(), m.start(), m.end()));
        }
        return tokens;
    }

    /** Whether {@code token} is a class/package reference matching {@code query}. */
    static boolean matches(String token, ResourceQuery query) {
        if (query.isPackage()) {
            return token.equals(query.target()) || token.startsWith(query.target() + ".");
        }
        return token.equals(query.target());
    }

    /**
     * Plans the dotted-token rewrites for one free-text-config file (XML / properties / YAML / JSON): every maximal
     * dotted token equal to a moved type's old FQN becomes that type's new FQN (HIGH confidence, the exact-class rule,
     * gated by {@code request.rewriteExactClassNames()}), and — only when {@code request.rewritePackagePrefixes()} is
     * enabled — a bare token equal to a moved package's old name becomes the new package name (MEDIUM confidence). The
     * exact-class rule wins when a token is in both maps, mirroring the package planners' rewrite policy.
     */
    static List<ResourceEdit> planTokenEdits(Path file, String content, ResourceRenameRequest request, String providerId) {
        List<ResourceEdit> edits = new ArrayList<>();
        for (Token token : dottedTokens(content)) {
            if (request.rewriteExactClassNames()) {
                String mapped = request.typeFqnMap().get(token.text());
                if (mapped != null && !mapped.equals(token.text())) {
                    edits.add(new ResourceEdit(file, token.start(), token.end(), mapped,
                            ResourceReferenceKind.EXACT_CLASS_NAME, ResourceConfidence.HIGH, providerId));
                    continue;
                }
            }
            if (request.rewritePackagePrefixes()) {
                String mapped = request.packageMap().get(token.text());
                if (mapped != null && !mapped.equals(token.text())) {
                    edits.add(new ResourceEdit(file, token.start(), token.end(), mapped,
                            ResourceReferenceKind.PACKAGE_PREFIX, ResourceConfidence.MEDIUM, providerId));
                }
            }
        }
        return edits;
    }
}
