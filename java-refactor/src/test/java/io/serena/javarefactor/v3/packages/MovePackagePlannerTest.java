package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.compiler.FileOverlay;
import io.serena.javarefactor.compiler.JavacSession;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compile-proofed coverage for the V3 {@link MovePackagePlanner} (headline §5/§26, G003 second half).
 *
 * <p>Each case builds a tiny javac-backed temp project, runs the real planner in preview mode, then reconstructs the
 * exact post-edit overlay the sidecar would validate (apply the {@code changes[]} text edits and the {@code rename}
 * file operations) and runs the SAME {@link JavacSession} before/after the project did/does. The accepted case asserts a
 * REAL before/after diagnostic delta with zero NEW javac errors — the package move, its subpackage relocation, the moved
 * files' rename ops, and the referencing files' import rewrites all hold together under the compiler.
 */
class MovePackagePlannerTest {

    @Test
    void movesPackageAndSubpackagesRewritesImportsWithZeroNewErrors(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("com/acme/app/Service.java", ""
                + "package com.acme.app;\n"
                + "public class Service {\n"
                + "    public int answer() { return 42; }\n"
                + "}\n");
        // A subpackage under the moved package: by default includeSubpackages=true relocates it under the target prefix.
        files.put("com/acme/app/util/Helper.java", ""
                + "package com.acme.app.util;\n"
                + "public class Helper {\n"
                + "    public int twice(int n) { return n * 2; }\n"
                + "}\n");
        files.put("com/acme/client/Caller.java", ""
                + "package com.acme.client;\n"
                + "import com.acme.app.Service;\n"
                + "import com.acme.app.util.Helper;\n"
                + "public class Caller {\n"
                + "    public int run() { return new Helper().twice(new Service().answer()); }\n"
                + "}\n");
        // A sibling package sharing the "com.acme.app" prefix must NOT be corrupted by token-boundary-correct rewriting.
        files.put("com/acme/application/Other.java", ""
                + "package com.acme.application;\n"
                + "public class Other {\n"
                + "    public int n() { return 1; }\n"
                + "}\n");

        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourcePackage", "com.acme.app");
        fields.put("targetPackage", "com.acme.core");

        String json = new MovePackagePlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // Moved file rename ops: app -> core and app/util -> core/util under the same source root.
        assertTrue(json.contains("\"oldPath\":\"src/com/acme/app/Service.java\""), json);
        assertTrue(json.contains("\"newPath\":\"src/com/acme/core/Service.java\""), json);
        assertTrue(json.contains("\"oldPath\":\"src/com/acme/app/util/Helper.java\""), json);
        assertTrue(json.contains("\"newPath\":\"src/com/acme/core/util/Helper.java\""), json);
        // Package-declaration edits (newText is the bare new package name on each moved file).
        assertTrue(json.contains("\"newText\":\"com.acme.core\""), json);
        assertTrue(json.contains("\"newText\":\"com.acme.core.util\""), json);
        // Import rewrites in the referencing file: each import's OWNING package span is rewritten to its moved target.
        // com.acme.app.Service -> com.acme.core.Service (owner com.acme.app) and com.acme.app.util.Helper ->
        // com.acme.core.util.Helper (owner com.acme.app.util), so both newTexts appear and there are exactly two edits.
        assertImportRewritten(json, "src/com/acme/client/Caller.java", "com.acme.core");
        assertImportRewritten(json, "src/com/acme/client/Caller.java", "com.acme.core.util");
        assertEquals(2, countEdits(json, "src/com/acme/client/Caller.java"),
                "both imports on Caller must be rewritten: " + json);
        // The sibling com.acme.application file must carry NO edits at all.
        assertFalse(json.contains("src/com/acme/application/Other.java"),
                "sibling package com.acme.application must not be touched: " + json);

        // REAL before/after diagnostic delta: reconstruct the validated overlay and run javac, proving zero NEW errors.
        JavacSession javac = new JavacSession();
        JavacSession.DiagnosticReport before = javac.collectDiagnosticReport(
                model, FileOverlay.fromProtocol(tmp.toAbsolutePath().normalize(), Map.of(), List.of(), List.of()));
        FileOverlay after = overlayFromPreview(tmp.toAbsolutePath().normalize(), json);
        JavacSession.DiagnosticReport afterReport = javac.collectDiagnosticReport(model, after);

        assertEquals(List.of(), before.errorStrings(), "baseline project must compile cleanly");
        List<String> newErrors = difference(afterReport.errorStrings(), before.errorStrings());
        assertEquals(List.of(), newErrors, "movePackage must not introduce NEW javac errors");
        assertEquals(List.of(), afterReport.errorStrings(), "post-move project must compile cleanly");
    }

    @Test
    void leavesSubpackagesWhenIncludeSubpackagesFalse(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("com/acme/app/Service.java", ""
                + "package com.acme.app;\n"
                + "public class Service {\n"
                + "}\n");
        files.put("com/acme/app/util/Helper.java", ""
                + "package com.acme.app.util;\n"
                + "public class Helper {\n"
                + "}\n");

        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourcePackage", "com.acme.app");
        fields.put("targetPackage", "com.acme.core");
        fields.put("includeSubpackages", false);

        String json = new MovePackagePlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"newPath\":\"src/com/acme/core/Service.java\""), json);
        // With subpackages excluded, the util file must NOT be relocated.
        assertFalse(json.contains("src/com/acme/app/util/Helper.java"),
                "subpackage must be untouched when includeSubpackages=false: " + json);
    }

    @Test
    void refusesPackageCollision(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("com/acme/app/Service.java", ""
                + "package com.acme.app;\n"
                + "public class Service {\n"
                + "}\n");
        // The target package already owns a type with the same simple name as the one being moved.
        files.put("com/acme/core/Service.java", ""
                + "package com.acme.core;\n"
                + "public class Service {\n"
                + "}\n");

        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourcePackage", "com.acme.app");
        fields.put("targetPackage", "com.acme.core");

        String json = new MovePackagePlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);
        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"package_collision\""), json);
    }

    @Test
    void refusesPackageNotFound(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("com/acme/other/Thing.java", ""
                + "package com.acme.other;\n"
                + "public class Thing {\n"
                + "}\n");

        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourcePackage", "com.acme.app");
        fields.put("targetPackage", "com.acme.core");

        String json = new MovePackagePlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);
        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"package_not_found\""), json);
    }

    // ── Overlay reconstruction (mirrors PreviewDiagnosticValidator.overlayFromPreview) ───────────────────────────────

    private static FileOverlay overlayFromPreview(Path projectRoot, String json) throws IOException {
        Map<String, List<int[]>> offsetsByPath = new LinkedHashMap<>();
        Map<String, List<String>> textsByPath = new LinkedHashMap<>();
        parseChanges(json, offsetsByPath, textsByPath);

        Map<String, Object> changedFiles = new LinkedHashMap<>();
        for (String path : offsetsByPath.keySet()) {
            String content = Files.readString(projectRoot.resolve(path), StandardCharsets.UTF_8);
            content = applyToContent(content, offsetsByPath.get(path), textsByPath.get(path));
            changedFiles.put(path, content);
        }

        List<Object> renamedFiles = new ArrayList<>();
        for (String[] rename : parseRenames(json)) {
            String oldPath = rename[0];
            String newPath = rename[1];
            String content = changedFiles.containsKey(oldPath)
                    ? String.valueOf(changedFiles.get(oldPath))
                    : Files.readString(projectRoot.resolve(oldPath), StandardCharsets.UTF_8);
            changedFiles.remove(oldPath);
            changedFiles.put(newPath, content);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("oldPath", oldPath);
            pair.put("newPath", newPath);
            renamedFiles.add(pair);
        }
        return FileOverlay.fromProtocol(projectRoot, changedFiles, List.of(), renamedFiles);
    }

    private static String applyToContent(String content, List<int[]> offsets, List<String> texts) {
        Integer[] order = new Integer[offsets.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingInt((Integer index) -> offsets.get(index)[0]).reversed());
        StringBuilder builder = new StringBuilder(content);
        for (int index : order) {
            builder.replace(offsets.get(index)[0], offsets.get(index)[1], texts.get(index));
        }
        return builder.toString();
    }

    private static final Pattern GROUP_PATTERN = Pattern.compile(
            "\\{\"path\":\"((?:\\\\.|[^\"\\\\])*)\",\"oldSha256\":\"(?:\\\\.|[^\"\\\\])*\",\"edits\":\\[");
    private static final Pattern EDIT_PATTERN = Pattern.compile(
            "\\{\"startOffset\":(\\d+),\"endOffset\":(\\d+),\"newText\":\"((?:\\\\.|[^\"\\\\])*)\",\"kind\":\"(?:\\\\.|[^\"\\\\])*\"\\}");
    private static final Pattern RENAME_PATTERN = Pattern.compile(
            "\\{\"kind\":\"rename\",\"oldPath\":\"((?:\\\\.|[^\"\\\\])*)\",\"newPath\":\"((?:\\\\.|[^\"\\\\])*)\",\"oldSha256\":\"(?:\\\\.|[^\"\\\\])*\"\\}");

    private static void parseChanges(String json, Map<String, List<int[]>> offsetsByPath, Map<String, List<String>> textsByPath) {
        int changesIndex = json.indexOf("\"changes\":");
        String region = changesIndex < 0 ? json : json.substring(changesIndex);
        Matcher groups = GROUP_PATTERN.matcher(region);
        List<String> groupPaths = new ArrayList<>();
        List<Integer> groupStarts = new ArrayList<>();
        while (groups.find()) {
            groupPaths.add(unescapeJson(groups.group(1)));
            groupStarts.add(groups.end());
        }
        for (int g = 0; g < groupPaths.size(); g++) {
            int start = groupStarts.get(g);
            int end = g + 1 < groupStarts.size()
                    ? region.lastIndexOf("{\"path\":", groupStarts.get(g + 1))
                    : region.length();
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

    private static List<String[]> parseRenames(String json) {
        List<String[]> renames = new ArrayList<>();
        Matcher matcher = RENAME_PATTERN.matcher(json);
        while (matcher.find()) {
            renames.add(new String[] {unescapeJson(matcher.group(1)), unescapeJson(matcher.group(2))});
        }
        return renames;
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

    private static void assertImportRewritten(String json, String relativePath, String newPackage) {
        Map<String, List<int[]>> offsetsByPath = new LinkedHashMap<>();
        Map<String, List<String>> textsByPath = new LinkedHashMap<>();
        parseChanges(json, offsetsByPath, textsByPath);
        assertTrue(offsetsByPath.containsKey(relativePath), "expected edits on " + relativePath + ": " + json);
        assertTrue(textsByPath.get(relativePath).contains(newPackage),
                "expected an edit on " + relativePath + " whose newText is " + newPackage + ": " + json);
    }

    private static int countEdits(String json, String relativePath) {
        Map<String, List<int[]>> offsetsByPath = new LinkedHashMap<>();
        Map<String, List<String>> textsByPath = new LinkedHashMap<>();
        parseChanges(json, offsetsByPath, textsByPath);
        return offsetsByPath.getOrDefault(relativePath, List.of()).size();
    }

    private static List<String> difference(List<String> after, List<String> before) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String diagnostic : before) {
            counts.merge(diagnostic, 1, Integer::sum);
        }
        List<String> result = new ArrayList<>();
        for (String diagnostic : after) {
            int count = counts.getOrDefault(diagnostic, 0);
            if (count > 0) {
                counts.put(diagnostic, count - 1);
            } else {
                result.add(diagnostic);
            }
        }
        return result;
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────────────────────

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
}
