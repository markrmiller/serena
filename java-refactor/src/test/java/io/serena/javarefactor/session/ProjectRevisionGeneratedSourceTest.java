package io.serena.javarefactor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves session-revision safety for generated sources: a content change to a generated {@code .java} file under an
 * UNCHANGED generated-root set must invalidate a captured revision. Without the content-sensitive digest the model
 * hash and touched-file set stay identical (generated content is not part of {@code model.toJson()}), so a stale
 * preview planned against the old generated API would wrongly survive to apply.
 */
final class ProjectRevisionGeneratedSourceTest {

    @Test
    void generatedSourceContentChangeInvalidatesRevisionUnderStableRoot() throws IOException {
        Path root = Files.createTempDirectory("serena-genrev");
        Path genRoot = root.resolve("build/generated/sources/annotationProcessor/java/main");
        Files.createDirectories(genRoot.resolve("demo"));
        Path genFile = genRoot.resolve("demo/Gen.java");
        Files.writeString(genFile, "package demo;\npublic final class Gen { public int v() { return 1; } }\n");

        JavaProjectModel model = modelWithGeneratedRoot(root, genRoot);
        ProjectRevision captured = ProjectRevision.capture(model, List.of());

        // Unchanged generated content re-captures identically.
        assertNull(captured.mismatch(ProjectRevision.capture(model, List.of())),
                "unchanged generated sources must not invalidate the revision");

        // Mutate generated source CONTENT under the SAME root: the model hash and touched files are unchanged, so only
        // the generated-source digest can catch this.
        Files.writeString(genFile,
                "package demo;\npublic final class Gen { public int v() { return 2; } public int w() { return 3; } }\n");
        ProjectRevision afterRegen = ProjectRevision.capture(model, List.of());
        assertEquals("revision invalidation inputs changed", captured.mismatch(afterRegen),
                "a generated-source content change under a stable root must invalidate the revision");
    }

    @Test
    void addingGeneratedSourceFileInvalidatesRevision() throws IOException {
        Path root = Files.createTempDirectory("serena-genrev-add");
        Path genRoot = root.resolve("build/generated/sources/annotationProcessor/java/main");
        Files.createDirectories(genRoot.resolve("demo"));
        Files.writeString(genRoot.resolve("demo/Gen.java"),
                "package demo;\npublic final class Gen { public int v() { return 1; } }\n");

        JavaProjectModel model = modelWithGeneratedRoot(root, genRoot);
        ProjectRevision captured = ProjectRevision.capture(model, List.of());

        // A newly generated sibling type changes the inventory even though every existing file is byte-identical.
        Files.writeString(genRoot.resolve("demo/Extra.java"),
                "package demo;\npublic final class Extra { public int e() { return 9; } }\n");
        ProjectRevision afterRegen = ProjectRevision.capture(model, List.of());
        assertEquals("revision invalidation inputs changed", captured.mismatch(afterRegen),
                "a newly generated source file must invalidate the revision");
    }

    private static JavaProjectModel modelWithGeneratedRoot(Path root, Path genRoot) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(root.resolve("src")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(genRoot),
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
        return new JavaProjectModel(root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
