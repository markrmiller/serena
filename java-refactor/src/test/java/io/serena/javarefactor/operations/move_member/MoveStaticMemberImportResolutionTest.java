package io.serena.javarefactor.operations.move_member;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G007 (hard blocker 7) — compile-proofed coverage for the static member move's javac-backed body-dependency import
 * resolution ({@link MoveStaticMemberPlanner}, reached via {@link MoveMemberPlanner#moveStaticMember}).
 *
 * <p>Each case moves a static member whose body depends on some import surface — a single-type import, a static import,
 * a wildcard import, a nested/generic type, a same-package type, or a project type that conflicts by simple name — runs
 * the real planner against a javac-backed temp project, then APPLIES the planned preview edits to the on-disk sources
 * and recompiles the whole project with the system Java compiler. The assertion is therefore not a substring check on
 * the preview but a proof that the relocated body still resolves in its new home.
 */
class MoveStaticMemberImportResolutionTest {

    /** Single-type import: the moved body uses {@code List}/{@code ArrayList}, imported only in the source. */
    @Test
    void singleTypeImportTransplantedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Source.java", ""
                + "package a;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "public class Source {\n"
                + "    public static List<String> make() {\n"
                + "        List<String> result = new ArrayList<>();\n"
                + "        result.add(\"x\");\n"
                + "        return result;\n"
                + "    }\n"
                + "}\n");
        files.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "b.Target", "src/b/Target.java", true);
    }

    /** Static import used inside the body: {@code helper()} resolves through {@code import static a.Util.helper;}. */
    @Test
    void staticImportTransplantedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Util.java", ""
                + "package a;\n"
                + "public class Util {\n"
                + "    public static int helper() { return 9; }\n"
                + "}\n");
        files.put("a/Source.java", ""
                + "package a;\n"
                + "import static a.Util.helper;\n"
                + "public class Source {\n"
                + "    public static int make() {\n"
                + "        return helper() + 1;\n"
                + "    }\n"
                + "}\n");
        files.put("c/Target.java", ""
                + "package c;\n"
                + "public class Target {\n"
                + "}\n");
        String json = assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "c.Target", "src/c/Target.java", true);
        assertTrue(json.contains("import static a.Util.helper;"), json);
    }

    /** Wildcard import: the moved body uses {@code List}/{@code Map}, brought in by {@code import java.util.*;}. */
    @Test
    void wildcardImportDependencyResolvedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Source.java", ""
                + "package a;\n"
                + "import java.util.*;\n"
                + "public class Source {\n"
                + "    public static Map<String, List<String>> make() {\n"
                + "        return new HashMap<>();\n"
                + "    }\n"
                + "}\n");
        files.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "b.Target", "src/b/Target.java", true);
    }

    /** Nested/generic type surface: the moved body's return type is {@code Map.Entry<String, Outer.Inner>}. */
    @Test
    void nestedAndGenericTypeSurfaceResolvedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Outer.java", ""
                + "package a;\n"
                + "public class Outer {\n"
                + "    public static class Inner {\n"
                + "    }\n"
                + "}\n");
        files.put("a/Source.java", ""
                + "package a;\n"
                + "import java.util.Map;\n"
                + "import a.Outer.Inner;\n"
                + "public class Source {\n"
                + "    public static Map<String, Inner> make() {\n"
                + "        return null;\n"
                + "    }\n"
                + "}\n");
        files.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "b.Target", "src/b/Target.java", true);
    }

    /** Same-package source type: the moved body references {@code Helper}, a sibling in the source package. */
    @Test
    void samePackageSourceTypeImportedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Helper.java", ""
                + "package a;\n"
                + "public class Helper {\n"
                + "    public static int v() { return 3; }\n"
                + "}\n");
        files.put("a/Source.java", ""
                + "package a;\n"
                + "public class Source {\n"
                + "    public static int make() {\n"
                + "        return new Helper().hashCode() + a.Helper.v();\n"
                + "    }\n"
                + "}\n");
        files.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        // After the move out of package a, the same-package visibility of Helper is lost, so an explicit import a.Helper;
        // must be transplanted for the body to still resolve in package b.
        String json = assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "b.Target", "src/b/Target.java", true);
        assertTrue(json.contains("import a.Helper;"), json);
    }

    /** Project simple-name conflict: the target package declares its own {@code Helper}; the moved use stays FQN. */
    @Test
    void projectSimpleNameConflictLeftFullyQualifiedAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Helper.java", ""
                + "package a;\n"
                + "public class Helper {\n"
                + "    public static int v() { return 3; }\n"
                + "}\n");
        files.put("b/Helper.java", ""
                + "package b;\n"
                + "public class Helper {\n"
                + "    public static int other() { return 7; }\n"
                + "}\n");
        files.put("a/Source.java", ""
                + "package a;\n"
                + "public class Source {\n"
                + "    public static int make() {\n"
                + "        return a.Helper.v();\n"
                + "    }\n"
                + "}\n");
        files.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        // The moved body already qualifies a.Helper, and the target package owns a DIFFERENT b.Helper, so the planner
        // must NOT import a.Helper (that would clash with b.Helper). The fully-qualified use compiles as-is.
        String json = assertMovedStaticMemberCompiles(tmp, files, "a/Source.java", "make(", "b.Target", "src/b/Target.java", true);
        assertTrue(!json.contains("import a.Helper;"), "must not import a.Helper into a package that already has its own Helper: " + json);
    }

    /**
     * Across source roots: the source lives in {@code main} and the target in a second {@code extra} source root. The
     * moved body uses {@code List} (imported only in the source), proving the body-dependency import is transplanted
     * across the source-root boundary and the relocated source still compiles.
     */
    @Test
    void crossSourceRootMoveTransplantsImportAndCompiles(@TempDir Path tmp) throws IOException {
        Map<String, String> mainFiles = new LinkedHashMap<>();
        mainFiles.put("a/Source.java", ""
                + "package a;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "public class Source {\n"
                + "    public static List<String> make() {\n"
                + "        return new ArrayList<>();\n"
                + "    }\n"
                + "}\n");
        Map<String, String> extraFiles = new LinkedHashMap<>();
        extraFiles.put("b/Target.java", ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n");
        JavaProjectModel model = twoSourceRootModel(tmp, mainFiles, extraFiles);
        Map<String, Object> fields = fields("src/a/Source.java", mainFiles.get("a/Source.java"), "make(");
        fields.put("targetType", "b.Target");
        fields.put("targetRelativePath", "extra/b/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("import java.util.List;"), json);

        Path applied = applyAcrossRoots(tmp, json, List.of("src", "extra"));
        assertCompiles(applied);
    }

    // ── Compile-proof harness ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the static move, applies the planned preview edits to the on-disk sources, recompiles the whole project, and
     * asserts the recompile succeeds. Returns the planner's preview JSON for additional targeted assertions.
     */
    private static String assertMovedStaticMemberCompiles(
            Path tmp,
            Map<String, String> files,
            String sourceRelativeToRoot,
            String token,
            String targetType,
            String targetRelativePath,
            boolean allowAccessWidening)
            throws IOException {
        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = fields("src/" + sourceRelativeToRoot, files.get(sourceRelativeToRoot), token);
        fields.put("targetType", targetType);
        fields.put("targetRelativePath", targetRelativePath);
        if (allowAccessWidening) {
            fields.put("allowAccessWidening", true);
        }

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);

        Path applied = applyEdits(tmp, json);
        assertCompiles(applied);
        return json;
    }

    /** Applies the {@code workspaceEdit.changes[]} to a fresh copy of {@code tmp/src} and returns the new source root. */
    private static Path applyEdits(Path tmp, String json) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Path appliedRoot = tmp.resolve("applied");
        // Map of project-relative path -> ordered (descending startOffset) edits.
        Map<String, List<int[]>> editOffsetsByPath = new LinkedHashMap<>();
        Map<String, List<String>> editTextsByPath = new LinkedHashMap<>();
        parseChanges(json, editOffsetsByPath, editTextsByPath);

        // Copy every source file, applying any edits keyed by its project-relative path.
        try (var paths = Files.walk(sourceRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = "src/" + sourceRoot.relativize(file).toString().replace('\\', '/');
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (editOffsetsByPath.containsKey(relative)) {
                    content = applyToContent(content, editOffsetsByPath.get(relative), editTextsByPath.get(relative));
                }
                Path destination = appliedRoot.resolve(sourceRoot.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, content, StandardCharsets.UTF_8);
            }
        }
        return appliedRoot;
    }

    /**
     * Applies the planned edits to sources spread across several source roots ({@code roots}, each a directory under
     * {@code tmp}) and flattens every relocated file into one compile root keyed by package, so cross-source-root moves
     * recompile as a single unit. Project-relative paths in the preview are keyed as {@code <root>/<pkgPath>/File.java}.
     */
    private static Path applyAcrossRoots(Path tmp, String json, List<String> roots) throws IOException {
        Path appliedRoot = tmp.resolve("applied");
        Map<String, List<int[]>> editOffsetsByPath = new LinkedHashMap<>();
        Map<String, List<String>> editTextsByPath = new LinkedHashMap<>();
        parseChanges(json, editOffsetsByPath, editTextsByPath);
        for (String root : roots) {
            Path rootDir = tmp.resolve(root);
            if (!Files.isDirectory(rootDir)) {
                continue;
            }
            try (var paths = Files.walk(rootDir)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String relativeWithinRoot = rootDir.relativize(file).toString().replace('\\', '/');
                    String key = root + "/" + relativeWithinRoot;
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (editOffsetsByPath.containsKey(key)) {
                        content = applyToContent(content, editOffsetsByPath.get(key), editTextsByPath.get(key));
                    }
                    // Flatten into one compile tree keyed by package path (the directory under the root).
                    Path destination = appliedRoot.resolve(relativeWithinRoot);
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, content, StandardCharsets.UTF_8);
                }
            }
        }
        return appliedRoot;
    }

    /** Applies a file's edits to its content, descending by startOffset so earlier offsets stay valid. */
    private static String applyToContent(String content, List<int[]> offsets, List<String> texts) {
        // Sort indices by descending startOffset.
        Integer[] order = new Integer[offsets.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (l, r) -> Integer.compare(offsets.get(r)[0], offsets.get(l)[0]));
        StringBuilder builder = new StringBuilder(content);
        for (int index : order) {
            int start = offsets.get(index)[0];
            int end = offsets.get(index)[1];
            builder.replace(start, end, texts.get(index));
        }
        return builder.toString();
    }

    private static final Pattern GROUP_PATTERN = Pattern.compile("\\{\"path\":\"((?:\\\\.|[^\"\\\\])*)\",\"oldSha256\":\"(?:\\\\.|[^\"\\\\])*\",\"edits\":\\[");
    private static final Pattern EDIT_PATTERN = Pattern.compile(
            "\\{\"startOffset\":(\\d+),\"endOffset\":(\\d+),\"newText\":\"((?:\\\\.|[^\"\\\\])*)\",\"kind\":\"(?:\\\\.|[^\"\\\\])*\"\\}");

    /** Parses the {@code changes[]} array out of the preview JSON into per-path offset/text lists. */
    private static void parseChanges(String json, Map<String, List<int[]>> offsetsByPath, Map<String, List<String>> textsByPath) {
        int changesIndex = json.indexOf("\"changes\":");
        String region = changesIndex < 0 ? json : json.substring(changesIndex);
        Matcher groups = GROUP_PATTERN.matcher(region);
        List<int[]> groupSpans = new ArrayList<>();
        List<String> groupPaths = new ArrayList<>();
        while (groups.find()) {
            groupPaths.add(unescapeJson(groups.group(1)));
            groupSpans.add(new int[] {groups.end()});
        }
        for (int g = 0; g < groupPaths.size(); g++) {
            int start = groupSpans.get(g)[0];
            int end = g + 1 < groupSpans.size() ? region.lastIndexOf("{\"path\":", groupSpans.get(g + 1)[0]) : region.length();
            String slice = region.substring(start, Math.max(start, end));
            Matcher edits = EDIT_PATTERN.matcher(slice);
            List<int[]> offsets = offsetsByPath.computeIfAbsent(groupPaths.get(g), key -> new ArrayList<>());
            List<String> texts = textsByPath.computeIfAbsent(groupPaths.get(g), key -> new ArrayList<>());
            while (edits.find()) {
                offsets.add(new int[] {Integer.parseInt(edits.group(1)), Integer.parseInt(edits.group(2))});
                texts.add(unescapeJson(edits.group(3)));
            }
        }
    }

    private static String unescapeJson(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Compiles every {@code .java} file under {@code sourceRoot}, asserting zero errors. */
    private static void assertCompiles(Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<Path> javaFiles;
        try (var paths = Files.walk(sourceRoot)) {
            javaFiles = paths.filter(file -> file.toString().endsWith(".java")).toList();
        }
        Path classes = sourceRoot.getParent().resolve("classes");
        Files.createDirectories(classes);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            boolean ok = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-d", classes.toString(), "-source", "17", "-target", "17"),
                    null,
                    units).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(diagnostic.toString());
                }
            }
            assertEquals(List.of(), errors, "Relocated sources must compile cleanly");
            assertTrue(ok, "javac reported failure");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> fields(String relativePath, String source, String token) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", relativePath);
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    private static int[] positionOf(String source, String token) {
        int from = source.indexOf(token);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < from; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, from - lineStart + 1};
    }

    private static JavaProjectModel model(Path root, Map<String, String> files) throws IOException {
        Path sourceRoot = root.resolve("src");
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(files).entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.copyOf(javaFiles),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }

    /** A two-source-root model: {@code mainFiles} under {@code src/}, {@code extraFiles} under {@code extra/}. */
    private static JavaProjectModel twoSourceRootModel(Path root, Map<String, String> mainFiles, Map<String, String> extraFiles)
            throws IOException {
        SourceSet main = sourceSet(root, "main", "src", mainFiles);
        SourceSet extra = sourceSet(root, "extra", "extra", extraFiles);
        return new JavaProjectModel(
                root, "test", List.of(main, extra), List.of(), List.of(), List.of(), false, false, List.of());
    }

    private static SourceSet sourceSet(Path root, String name, String rootDir, Map<String, String> files) throws IOException {
        Path sourceRoot = root.resolve(rootDir);
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(files).entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
        return new SourceSet(
                name,
                List.of(sourceRoot),
                List.copyOf(javaFiles),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
    }
}
