package io.serena.javarefactor.operations.change_signature;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G002 integration coverage proving change-signature wires the DEEP import planner: a parameter typed as a nested
 * generic with an array argument ({@code java.util.Map<com.a.Foo, com.b.Bar[]>}) imports every component (Map, Foo, Bar)
 * and renders the declaration with simple names, rather than importing only the outer Map and leaving the arguments
 * fully qualified (the old shallow behaviour).
 */
class ChangeSignatureDeepImportTest {

    @Test
    void parameterWithNestedGenericArrayTypeImportsEveryComponentAndRendersSimple(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/demo/Svc.java", ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "}\n");
        files.put("src/com/a/Foo.java", "package com.a;\npublic class Foo {}\n");
        files.put("src/com/b/Bar.java", "package com.b;\npublic class Bar {}\n");

        int[] pos = positionOf(files.get("src/demo/Svc.java"), "compute(int a)");
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Svc.java");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        fields.put("parameters", new ArrayList<>(List.of(
                param("int", "a", 0),
                // A nested generic whose argument is itself an array of another package's type.
                paramWithDefault("java.util.Map<com.a.Foo, com.b.Bar[]>", "lookup", "null"))));

        String json = run(tmp, files, fields);

        assertAccepted(json);
        // Every component is imported.
        assertTrue(json.contains("import java.util.Map;"), json);
        assertTrue(json.contains("import com.a.Foo;"), json);
        assertTrue(json.contains("import com.b.Bar;"), json);
        // The declaration renders with simple names, not the fully-qualified nested type.
        assertTrue(json.contains("Map<Foo, Bar[]> lookup"), json);
        assertFalse(json.contains("java.util.Map<com.a.Foo, com.b.Bar[]> lookup"), json);
    }

    // --- harness -----------------------------------------------------------------------------------------------------

    private static Map<String, Object> param(String type, String name, Integer oldIndex) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", type);
        spec.put("name", name);
        if (oldIndex != null) {
            spec.put("oldIndex", oldIndex);
        }
        return spec;
    }

    private static Map<String, Object> paramWithDefault(String type, String name, String defaultValue) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", type);
        spec.put("name", name);
        spec.put("defaultValue", defaultValue);
        return spec;
    }

    private String run(Path tmp, Map<String, String> files, Map<String, Object> fields) throws IOException {
        JavaProjectModel model = multiFileModel(tmp, files);
        ChangeSignaturePlanner planner = new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model);
        return planner.changeSignature(fields, false);
    }

    private static void assertAccepted(String json) {
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("\"accepted\":false"), json);
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

    private static JavaProjectModel multiFileModel(Path root, Map<String, String> files) throws IOException {
        Path sourceRoot = root.resolve("src");
        List<Path> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path javaFile = root.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(javaFile);
        }
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                sourceFiles,
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
