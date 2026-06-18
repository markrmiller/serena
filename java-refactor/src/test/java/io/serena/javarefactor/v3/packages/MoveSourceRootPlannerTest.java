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
 * Compile-proofed coverage for the V3 {@link MoveSourceRootPlanner} (headline §1.1/§4.1, the {@code moveSourceRoot}
 * workspace step / {@code JavaMoveSourceRootTool}).
 *
 * <p>{@code moveSourceRoot} relocates Java source files from one configured source root to ANOTHER while keeping every
 * moved file's package declaration unchanged, so — unlike {@link MovePackagePlanner} — the plan must carry ONLY rename
 * {@link io.serena.javarefactor.edits.ResponseBuilder.FileOperation}s and NO text edits. The accepted case asserts both
 * that contract (rename ops present, no {@code newText} edits emitted) and a REAL before/after javac diagnostic delta
 * with zero NEW errors: the moved files stay on the merged source set's compile path, so the FQNs and imports that are
 * deliberately left untouched still resolve.
 */
class MoveSourceRootPlannerTest {

    @Test
    void relocatesFilesBetweenRootsWithoutTextEditsAndZeroNewErrors(@TempDir Path tmp) throws IOException {
        Map<String, String> main = new LinkedHashMap<>();
        main.put("com/acme/app/Service.java", ""
                + "package com.acme.app;\n"
                + "public class Service {\n"
                + "    public int answer() { return 42; }\n"
                + "}\n");
        main.put("com/acme/app/util/Helper.java", ""
                + "package com.acme.app.util;\n"
                + "public class Helper {\n"
                + "    public int twice(int n) { return n * 2; }\n"
                + "}\n");
        // A referencing file in a DIFFERENT package: its imports must NOT be rewritten by moveSourceRoot. After the move
        // it resolves only because the moved types keep their packages and stay on the merged source set's compile path.
        main.put("com/acme/client/Caller.java", ""
                + "package com.acme.client;\n"
                + "import com.acme.app.Service;\n"
                + "import com.acme.app.util.Helper;\n"
                + "public class Caller {\n"
                + "    public int run() { return new Helper().twice(new Service().answer()); }\n"
                + "}\n");
        // An existing file under the target root keeps it a non-empty, already-configured source root that stays put.
        Map<String, String> test = new LinkedHashMap<>();
        test.put("com/acme/keep/Keep.java", ""
                + "package com.acme.keep;\n"
                + "public class Keep {}\n");

        JavaProjectModel model = model(tmp, main, test);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/test/java");

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // File moves preserve the within-root relative path (and therefore the package) under the new source root.
        assertRename(json, "src/main/java/com/acme/app/Service.java", "src/test/java/com/acme/app/Service.java");
        assertRename(json, "src/main/java/com/acme/app/util/Helper.java", "src/test/java/com/acme/app/util/Helper.java");
        assertRename(json, "src/main/java/com/acme/client/Caller.java", "src/test/java/com/acme/client/Caller.java");
        // The defining contract: NO text edits at all — package declarations and references are left untouched.
        assertFalse(json.contains("\"newText\":"), "moveSourceRoot must emit no text edits: " + json);
        // The pre-existing file under the target root must NOT be moved.
        assertFalse(json.contains("com/acme/keep/Keep.java"), "target-root file must stay put: " + json);

        JavacSession javac = new JavacSession();
        JavacSession.DiagnosticReport before = javac.collectDiagnosticReport(
                model, FileOverlay.fromProtocol(tmp.toAbsolutePath().normalize(), Map.of(), List.of(), List.of()));
        FileOverlay after = overlayFromPreview(tmp.toAbsolutePath().normalize(), json);
        JavacSession.DiagnosticReport afterReport = javac.collectDiagnosticReport(model, after);

        assertEquals(List.of(), before.errorStrings(), "baseline project must compile cleanly");
        assertEquals(List.of(), difference(afterReport.errorStrings(), before.errorStrings()),
                "moveSourceRoot must not introduce NEW javac errors");
        assertEquals(List.of(), afterReport.errorStrings(), "post-move project must compile cleanly");
    }

    @Test
    void restrictsToRequestedPackages(@TempDir Path tmp) throws IOException {
        Map<String, String> main = new LinkedHashMap<>();
        main.put("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n");
        main.put("com/acme/app/util/Helper.java", "package com.acme.app.util;\npublic class Helper {}\n");
        main.put("com/acme/other/Other.java", "package com.acme.other;\npublic class Other {}\n");
        Map<String, String> test = new LinkedHashMap<>();
        test.put("com/acme/keep/Keep.java", "package com.acme.keep;\npublic class Keep {}\n");

        JavaProjectModel model = model(tmp, main, test);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/test/java");
        fields.put("packages", List.of("com.acme.app"));

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // includeSubpackages defaults true: com.acme.app and its com.acme.app.util subpackage move.
        assertRename(json, "src/main/java/com/acme/app/Service.java", "src/test/java/com/acme/app/Service.java");
        assertRename(json, "src/main/java/com/acme/app/util/Helper.java", "src/test/java/com/acme/app/util/Helper.java");
        // The unrelated com.acme.other package is NOT in the requested set and must stay put.
        assertFalse(json.contains("com/acme/other/Other.java"), "unrequested package must not move: " + json);
    }

    @Test
    void excludesSubpackagesWhenRequested(@TempDir Path tmp) throws IOException {
        Map<String, String> main = new LinkedHashMap<>();
        main.put("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n");
        main.put("com/acme/app/util/Helper.java", "package com.acme.app.util;\npublic class Helper {}\n");
        Map<String, String> test = new LinkedHashMap<>();
        test.put("com/acme/keep/Keep.java", "package com.acme.keep;\npublic class Keep {}\n");

        JavaProjectModel model = model(tmp, main, test);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/test/java");
        fields.put("packages", List.of("com.acme.app"));
        fields.put("includeSubpackages", false);

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertRename(json, "src/main/java/com/acme/app/Service.java", "src/test/java/com/acme/app/Service.java");
        assertFalse(json.contains("com/acme/app/util/Helper.java"),
                "subpackage must be untouched when includeSubpackages=false: " + json);
    }

    @Test
    void refusesUnknownSourceRoot(@TempDir Path tmp) throws IOException {
        Map<String, String> main = new LinkedHashMap<>();
        main.put("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n");
        Map<String, String> test = new LinkedHashMap<>();
        test.put("com/acme/keep/Keep.java", "package com.acme.keep;\npublic class Keep {}\n");

        JavaProjectModel model = model(tmp, main, test);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/generated");
        fields.put("targetSourceRoot", "src/test/java");

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);
        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"source_root_not_found\""), json);
    }

    @Test
    void refusesDestinationCollision(@TempDir Path tmp) throws IOException {
        Map<String, String> main = new LinkedHashMap<>();
        main.put("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n");
        // The target root already owns a file at the destination path the move would occupy.
        Map<String, String> test = new LinkedHashMap<>();
        test.put("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n");

        JavaProjectModel model = model(tmp, main, test);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/test/java");

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);
        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"package_collision\""), json);
    }

    // ── B06: Maven build-helper-maven-plugin add-source registration ─────────────────────────────────────────────────

    @Test
    void registersTargetRootViaMavenBuildHelperOnCleanPom(@TempDir Path tmp) throws IOException {
        // A POM with a <build><plugins> but NO build-helper-maven-plugin: the additive edit must insert a complete
        // plugin block binding add-source for the (unconfigured) target source root, before </plugins>.
        String pom = ""
                + "<project>\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.acme</groupId>\n"
                + "    <artifactId>app</artifactId>\n"
                + "    <version>1.0</version>\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n";
        JavaProjectModel model = mavenModel(tmp, pom,
                Map.of("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n"));
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/main/extra");
        fields.put("rewriteBuildFiles", true);

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The file still relocates between roots.
        assertRename(json, "src/main/java/com/acme/app/Service.java", "src/main/extra/com/acme/app/Service.java");
        // A concrete, parse-verified build-file edit on the POM registers the new root via build-helper-maven-plugin.
        assertTrue(json.contains("\"path\":\"pom.xml\""), "expected a pom.xml build-file edit: " + json);
        assertTrue(json.contains("\"kind\":\"MOVE_SOURCE_ROOT_BUILD_FILE\""), json);
        assertTrue(json.contains("build-helper-maven-plugin"), "expected build-helper plugin block: " + json);
        assertTrue(json.contains("<goal>add-source</goal>"), "expected add-source goal: " + json);
        assertTrue(json.contains("<source>src/main/extra</source>"),
                "expected module-relative source registration: " + json);
    }

    @Test
    void extendsExistingMavenBuildHelperExecution(@TempDir Path tmp) throws IOException {
        // A POM that already declares a build-helper-maven-plugin execution bound to add-source with a <sources> list:
        // the additive edit must append a single <source> to the existing binding rather than duplicating the plugin.
        String pom = ""
                + "<project>\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.acme</groupId>\n"
                + "    <artifactId>app</artifactId>\n"
                + "    <version>1.0</version>\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + "            <plugin>\n"
                + "                <groupId>org.codehaus.mojo</groupId>\n"
                + "                <artifactId>build-helper-maven-plugin</artifactId>\n"
                + "                <executions>\n"
                + "                    <execution>\n"
                + "                        <id>add-source-existing</id>\n"
                + "                        <phase>generate-sources</phase>\n"
                + "                        <goals>\n"
                + "                            <goal>add-source</goal>\n"
                + "                        </goals>\n"
                + "                        <configuration>\n"
                + "                            <sources>\n"
                + "                                <source>src/main/generated</source>\n"
                + "                            </sources>\n"
                + "                        </configuration>\n"
                + "                    </execution>\n"
                + "                </executions>\n"
                + "            </plugin>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n";
        JavaProjectModel model = mavenModel(tmp, pom,
                Map.of("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n"));
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/main/extra");
        fields.put("rewriteBuildFiles", true);

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"path\":\"pom.xml\""), "expected a pom.xml build-file edit: " + json);
        assertTrue(json.contains("\"kind\":\"MOVE_SOURCE_ROOT_BUILD_FILE\""), json);
        assertTrue(json.contains("<source>src/main/extra</source>"),
                "expected the new <source> appended to the existing execution: " + json);
        // Extending the existing execution must NOT re-declare the plugin: the edit is just the <source> insertion.
        assertFalse(json.contains("build-helper-maven-plugin"),
                "extending an existing execution must not re-add the plugin artifactId: " + json);
    }

    @Test
    void refusesMavenBuildHelperWithoutExtendableSources(@TempDir Path tmp) throws IOException {
        // A build-helper-maven-plugin bound to add-source but with NO <sources> container is a genuinely unsupported
        // residual shape: there is nowhere to safely append a <source>, so the planner refuses rather than guessing.
        String pom = ""
                + "<project>\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.acme</groupId>\n"
                + "    <artifactId>app</artifactId>\n"
                + "    <version>1.0</version>\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + "            <plugin>\n"
                + "                <groupId>org.codehaus.mojo</groupId>\n"
                + "                <artifactId>build-helper-maven-plugin</artifactId>\n"
                + "                <executions>\n"
                + "                    <execution>\n"
                + "                        <id>add-source-broken</id>\n"
                + "                        <goals>\n"
                + "                            <goal>add-source</goal>\n"
                + "                        </goals>\n"
                + "                    </execution>\n"
                + "                </executions>\n"
                + "            </plugin>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n";
        JavaProjectModel model = mavenModel(tmp, pom,
                Map.of("com/acme/app/Service.java", "package com.acme.app;\npublic class Service {}\n"));
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceRoot", "src/main/java");
        fields.put("targetSourceRoot", "src/main/extra");
        fields.put("rewriteBuildFiles", true);

        String json = new MoveSourceRootPlanner(tmp.toAbsolutePath().normalize(), model).plan(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"build_file_rewrite_unsupported\""), json);
    }

    // ── Assertions ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static void assertRename(String json, String oldPath, String newPath) {
        assertTrue(json.contains("\"oldPath\":\"" + oldPath + "\",\"newPath\":\"" + newPath + "\""),
                "expected rename " + oldPath + " -> " + newPath + ": " + json);
    }

    // ── Overlay reconstruction (rename-only: moveSourceRoot emits no text edits) ──────────────────────────────────────

    private static FileOverlay overlayFromPreview(Path projectRoot, String json) throws IOException {
        Map<String, Object> changedFiles = new LinkedHashMap<>();
        List<Object> renamedFiles = new ArrayList<>();
        for (String[] rename : parseRenames(json)) {
            String oldPath = rename[0];
            String newPath = rename[1];
            String content = Files.readString(projectRoot.resolve(oldPath), StandardCharsets.UTF_8);
            changedFiles.put(newPath, content);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("oldPath", oldPath);
            pair.put("newPath", newPath);
            renamedFiles.add(pair);
        }
        return FileOverlay.fromProtocol(projectRoot, changedFiles, List.of(), renamedFiles);
    }

    private static final Pattern RENAME_PATTERN = Pattern.compile(
            "\\{\"kind\":\"rename\",\"oldPath\":\"((?:\\\\.|[^\"\\\\])*)\",\"newPath\":\"((?:\\\\.|[^\"\\\\])*)\",\"oldSha256\":\"(?:\\\\.|[^\"\\\\])*\"\\}");

    private static List<String[]> parseRenames(String json) {
        List<String[]> renames = new ArrayList<>();
        Matcher matcher = RENAME_PATTERN.matcher(json);
        while (matcher.find()) {
            renames.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        return renames;
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

    private static JavaProjectModel model(Path root, Map<String, String> mainFiles, Map<String, String> testFiles)
            throws IOException {
        Path mainRoot = root.resolve("src/main/java");
        Path testRoot = root.resolve("src/test/java");
        List<Path> javaFiles = new ArrayList<>();
        writeAll(mainRoot, mainFiles, javaFiles);
        writeAll(testRoot, testFiles, javaFiles);
        // A single merged "main" source set spanning both configured roots: this is exactly the union an explicit
        // two-source-root configuration produces, and the union moveSourceRoot operates across.
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(mainRoot, testRoot),
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

    /**
     * A single-source-root Maven module: a {@code pom.xml} at the project root and one Java source root
     * ({@code src/main/java}). The model configures ONLY that root, so {@code src/main/extra} is an unconfigured target
     * whose registration goes through the build-helper-maven-plugin add-source path.
     */
    private static JavaProjectModel mavenModel(Path root, String pomXml, Map<String, String> mainFiles)
            throws IOException {
        Files.writeString(root.resolve("pom.xml"), pomXml, StandardCharsets.UTF_8);
        Path mainRoot = root.resolve("src/main/java");
        List<Path> javaFiles = new ArrayList<>();
        writeAll(mainRoot, mainFiles, javaFiles);
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(mainRoot),
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

    private static void writeAll(Path sourceRoot, Map<String, String> files, List<Path> javaFiles) throws IOException {
        Files.createDirectories(sourceRoot);
        for (Map.Entry<String, String> entry : new TreeMap<>(files).entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
    }
}
