package io.serena.javarefactor.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectModelDiscovererBuildToolTest {

    @Test
    void buildToolModelPreservesCustomSourceSetsGeneratedRootsAndCompilerPaths(@TempDir Path root) throws IOException {
        writeJava(root.resolve("app/src/main/java/demo/App.java"), "package demo; public class App {}\n");
        writeJava(root.resolve("app/src/integrationTest/java/demo/AppIT.java"), "package demo; public class AppIT extends App {}\n");
        writeJava(root.resolve("lib/src/main/java/lib/Lib.java"), "package lib; public class Lib {}\n");
        Files.createDirectories(root.resolve("app/build/classes/kotlin/main"));
        Files.createDirectories(root.resolve("app/build/generated/sources/openapi/src/main/java"));
        Files.writeString(root.resolve("settings.gradle"), "include ':app', ':lib'\nincludeBuild 'conventions'\n");

        BuildModel model = new BuildModel(List.of(
                new BuildModel.Module(":lib", List.of(new BuildModel.ModelSourceSet(
                        "main",
                        List.of(root.resolve("lib/src/main/java").toString()),
                        List.of(),
                        List.of(root.resolve("lib/build/classes/java/main").toString()),
                        List.of(),
                        List.of(),
                        List.of(),
                        "21",
                        null,
                        null,
                        "UTF-8",
                        List.of(),
                        List.of("--enable-preview")))),
                new BuildModel.Module(":app", List.of(
                        new BuildModel.ModelSourceSet(
                                "main",
                                List.of(root.resolve("app/src/main/java").toString()),
                                List.of(
                                        root.resolve("app/build/generated/sources/openapi/src/main/java").toString(),
                                        root.resolve("app/build/generated/sources/annotationProcessor/java/main").toString()),
                                List.of(
                                        root.resolve("app/build/classes/java/main").toString(),
                                        root.resolve("app/build/classes/kotlin/main").toString()),
                                List.of(root.resolve("deps/compile.jar").toString()),
                                List.of(root.resolve("mods/java.base.patch").toString()),
                                List.of(root.resolve("deps/processor.jar").toString()),
                                "17",
                                "17",
                                "17",
                                "UTF-8",
                                List.of(":lib"),
                                List.of("-parameters", "-Amapstruct.defaultComponentModel=spring")),
                        new BuildModel.ModelSourceSet(
                                "integrationTest",
                                List.of(root.resolve("app/src/integrationTest/java").toString()),
                                List.of(),
                                List.of(root.resolve("app/build/classes/java/integrationTest").toString()),
                                List.of(root.resolve("deps/test.jar").toString()),
                                List.of(),
                                List.of(root.resolve("deps/test-processor.jar").toString()),
                                "17",
                                "17",
                                "17",
                                "UTF-8",
                                List.of(":lib"),
                                List.of("--add-exports", "java.base/sun.security.x509=ALL-UNNAMED"))))));
        ProjectModelDiscoverer discoverer = new ProjectModelDiscoverer((buildKind, projectRoot, config) -> BuildModelExtractor.Result.ok(model));

        JavaProjectModel projectModel = discoverer.buildUnvalidatedModel(root, "{\"buildToolMode\":\"gradle\",\"annotationProcessing\":\"project\"}");

        assertTrue(projectModel.errors().isEmpty(), () -> String.join("\n", projectModel.errors()));
        SourceSet appMain = sourceSet(projectModel, ":app:main");
        SourceSet appIntegration = sourceSet(projectModel, ":app:integrationTest");
        assertEquals(List.of(":lib:main"), appMain.dependsOn());
        assertEquals(List.of(":app:main", ":lib:main"), appIntegration.dependsOn());
        assertTrue(appMain.outputDirs().contains(root.resolve("app/build/classes/kotlin/main").toAbsolutePath().normalize()));
        assertTrue(appMain.generatedRoots().contains(root.resolve("app/build/generated/sources/annotationProcessor/java/main").toAbsolutePath().normalize()));
        assertTrue(appMain.annotationProcessorPath().contains(root.resolve("deps/processor.jar").toAbsolutePath().normalize()));
        assertTrue(appMain.javacOptions().contains("-parameters"));
        assertTrue(appIntegration.javacOptions().contains("--add-exports"));
        assertFalse(appMain.javaFiles().stream().anyMatch(path -> path.toString().contains("integrationTest")));
    }

    @Test
    void mavenProfilesAreParsedAndPassedToBuildExtraction(@TempDir Path root) throws IOException {
        writeJava(root.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        List<String> capturedProfiles = new ArrayList<>();
        ProjectModelDiscoverer discoverer = new ProjectModelDiscoverer((buildKind, projectRoot, config) -> {
            capturedProfiles.addAll(config.mavenProfiles());
            return BuildModelExtractor.Result.ok(new BuildModel(List.of(new BuildModel.Module("root", List.of(
                    new BuildModel.ModelSourceSet(
                            "main",
                            List.of(root.resolve("src/main/java").toString()),
                            List.of(root.resolve("target/generated-sources/profile").toString()),
                            List.of(root.resolve("target/classes").toString()),
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            "21",
                            "21",
                            "UTF-8",
                            List.of(),
                            List.of("--enable-preview")))))));
        });

        JavaProjectModel projectModel = discoverer.buildUnvalidatedModel(root, "{\"buildToolMode\":\"maven\",\"mavenProfiles\":[\"dev\",\"it\"]}");

        assertTrue(projectModel.errors().isEmpty(), () -> String.join("\n", projectModel.errors()));
        assertEquals(List.of("dev", "it"), capturedProfiles);
        SourceSet main = sourceSet(projectModel, "main");
        assertTrue(main.generatedRoots().contains(root.resolve("target/generated-sources/profile").toAbsolutePath().normalize()));
        assertTrue(main.javacOptions().contains("--enable-preview"));
    }

    @Test
    void generatedSourceReadPolicyAndLombokJarFeedCompilerModel(@TempDir Path root) throws IOException {
        writeJava(root.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        writeJava(root.resolve("build/generated/sources/custom/demo/GeneratedApi.java"), "package demo; public class GeneratedApi { App app; }\n");
        Path lombok = root.resolve("lib/lombok.jar");
        Files.createDirectories(lombok.getParent());
        Files.writeString(lombok, "fake jar marker\n");

        ProjectModelDiscoverer discoverer = new ProjectModelDiscoverer((buildKind, projectRoot, config) -> BuildModelExtractor.Result.ok(
                new BuildModel(List.of(new BuildModel.Module("root", List.of(new BuildModel.ModelSourceSet(
                        "main",
                        List.of(root.resolve("src/main/java").toString()),
                        List.of(root.resolve("build/generated/sources/custom").toString()),
                        List.of(root.resolve("build/classes/java/main").toString()),
                        List.of(),
                        List.of(),
                        List.of(),
                        "17",
                        "17",
                        "17",
                        "UTF-8",
                        List.of(),
                        List.of())))))));

        JavaProjectModel readEnabled = discoverer.buildUnvalidatedModel(root,
                "{\"buildToolMode\":\"gradle\",\"generated_sources\":{\"read\":true,\"edit\":false},\"lombokJar\":\"lib/lombok.jar\"}");
        SourceSet enabledMain = sourceSet(readEnabled, "main");
        assertTrue(enabledMain.javaFiles().stream().anyMatch(path -> path.endsWith("GeneratedApi.java")));
        assertTrue(enabledMain.classpath().contains(lombok.toAbsolutePath().normalize()));
        assertTrue(enabledMain.annotationProcessorPath().contains(lombok.toAbsolutePath().normalize()));

        JavaProjectModel readDisabled = discoverer.buildUnvalidatedModel(root,
                "{\"buildToolMode\":\"gradle\",\"generated_sources\":{\"read\":false,\"edit\":false}}");
        SourceSet disabledMain = sourceSet(readDisabled, "main");
        assertFalse(disabledMain.javaFiles().stream().anyMatch(path -> path.endsWith("GeneratedApi.java")));
        assertTrue(disabledMain.generatedRoots().contains(root.resolve("build/generated/sources/custom").toAbsolutePath().normalize()));
    }

    @Test
    void explicitModelOverridePreservesConfiguredSourceSet(@TempDir Path root) throws IOException {
        writeJava(root.resolve("app/custom-src/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(root.resolve("app/build/generated/custom"));
        Files.createDirectories(root.resolve("app/build/classes/custom"));
        Files.createDirectories(root.resolve("libs"));
        Files.writeString(root.resolve("libs/api.jar"), "placeholder");
        Files.writeString(root.resolve("libs/module.jar"), "placeholder");
        Files.writeString(root.resolve("libs/lombok.jar"), "placeholder");

        String config = """
                {
                  "model": {
                    "modules": [
                      {
                        "project": ":app",
                        "sourceSets": [
                          {
                            "name": "main",
                            "srcDirs": ["app/custom-src"],
                            "generatedRoots": ["app/build/generated/custom"],
                            "outputDirs": ["app/build/classes/custom"],
                            "classpath": ["libs/api.jar"],
                            "modulePath": ["libs/module.jar"],
                            "annotationProcessorPath": ["libs/lombok.jar"],
                            "release": "21",
                            "source": "21",
                            "target": "21",
                            "encoding": "UTF-16",
                            "compilerArgs": ["-parameters"]
                          }
                        ]
                      }
                    ]
                  },
                  "release": "17",
                  "source": "17",
                  "target": "17",
                  "encoding": "UTF-8"
                }
                """;

        JavaProjectModel projectModel = new ProjectModelDiscoverer().buildUnvalidatedModel(root, config);

        assertTrue(projectModel.errors().isEmpty(), String.join("\n", projectModel.errors()));
        assertEquals("explicit", projectModel.discoveryKind());
        SourceSet main = sourceSet(projectModel, "main");
        assertEquals(List.of(root.resolve("app/custom-src").toAbsolutePath().normalize()), main.sourceRoots());
        assertTrue(main.generatedRoots().contains(root.resolve("app/build/generated/custom").toAbsolutePath().normalize()));
        assertTrue(main.outputDirs().contains(root.resolve("app/build/classes/custom").toAbsolutePath().normalize()));
        assertTrue(main.classpath().contains(root.resolve("libs/api.jar").toAbsolutePath().normalize()));
        assertTrue(main.modulePath().contains(root.resolve("libs/module.jar").toAbsolutePath().normalize()));
        assertTrue(main.annotationProcessorPath().contains(root.resolve("libs/lombok.jar").toAbsolutePath().normalize()));
        assertEquals("17", main.releaseVersion());
        assertEquals("17", main.sourceVersion());
        assertEquals("17", main.targetVersion());
        assertEquals("UTF-8", main.encoding());
        assertTrue(main.javacOptions().contains("-parameters"));
    }

    private static SourceSet sourceSet(JavaProjectModel model, String name) {
        return model.sourceSets().stream()
                .filter(sourceSet -> sourceSet.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing source set " + name + " in " + model.sourceSets()));
    }

    private static void writeJava(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }
}
