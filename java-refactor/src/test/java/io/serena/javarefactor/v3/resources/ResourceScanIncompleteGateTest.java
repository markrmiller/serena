package io.serena.javarefactor.v3.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story R06: an incomplete resource scan (a resource file in scope the scanner could not fully examine — unreadable, or
 * over the size cap) is NOT downgraded to a benign warning on an auto-applied edit. It is a hard gate: the resource
 * {@code planEdits} op marks {@code resourceScanIncomplete}, partitions NO edit into {@code autoApply} (every edit drops
 * to {@code preview}/{@code PREVIEW}), and still surfaces the specific incomplete file. This proves criteria #1–#3 at the
 * unit/SPI seam ({@link ResourceEditPlanner} over the unified {@link ResourcePlanner}).
 */
final class ResourceScanIncompleteGateTest {

    @Test
    void unreadableResourceInScopeBlocksAutoApplyAndForcesReview(@TempDir Path root) throws IOException {
        seedMovedType(root);
        // beans.xml references the moved type and would normally yield a HIGH exact-class auto-apply edit.
        Path beans = writeResource(root, "beans.xml",
                "<beans>\n  <bean id=\"impl\" class=\"com.acme.MyServiceImpl\"/>\n</beans>\n");
        // A second resource in scope is made unreadable, so the scan cannot determine whether it references the type.
        Path secret = writeResource(root, "secret.xml",
                "<config>\n  <handler>com.acme.MyServiceImpl</handler>\n</config>\n");
        assumeTrue(makeUnreadable(secret), "cannot make a file unreadable on this platform/user (likely root)");

        JavaProjectModel model = model(root);
        String json = new ResourceEditPlanner(root, model)
                .planEdits(Map.of("typeFqnMap", Map.of("com.acme.MyServiceImpl", "com.other.MyServiceImpl")));
        Map<String, Object> result = Json.parseObject(json);

        assertGateBlocked(result, "secret.xml");
        // beans.xml's HIGH edit still exists in the full edit list — it is just withheld from auto-apply (preview only).
        assertFalse(((List<?>) result.get("edits")).isEmpty(), "the HIGH edit is still planned, only never auto-applied: " + json);
        // sanity: beans.xml was the readable, edit-yielding file.
        assertTrue(Files.exists(beans));
    }

    @Test
    void overCapResourceInScopeBlocksAutoApplyAndForcesReview(@TempDir Path root) throws IOException {
        seedMovedType(root);
        writeResource(root, "beans.xml",
                "<beans>\n  <bean id=\"impl\" class=\"com.acme.MyServiceImpl\"/>\n</beans>\n");
        // A large resource in scope: over the cap, so its content is never read and its references can't be determined.
        StringBuilder big = new StringBuilder("<config>\n");
        big.append(" ".repeat(4096));
        big.append("\n</config>\n");
        writeResource(root, "huge.xml", big.toString());

        JavaProjectModel model = model(root);
        // A tiny cap (64 bytes) makes huge.xml over-cap while leaving beans.xml scannable.
        String json = new ResourceEditPlanner(root, model, 64L)
                .planEdits(Map.of("typeFqnMap", Map.of("com.acme.MyServiceImpl", "com.other.MyServiceImpl")));
        Map<String, Object> result = Json.parseObject(json);

        assertGateBlocked(result, "huge.xml");
    }

    /** Asserts the R06 gate: scan flagged incomplete, the file is surfaced, nothing auto-applies, every edit is preview. */
    private static void assertGateBlocked(Map<String, Object> result, String incompleteFileName) {
        assertEquals(Boolean.TRUE, result.get("resourceScanIncomplete"),
                "an incomplete scan must mark resourceScanIncomplete: " + result);

        List<?> incomplete = (List<?>) result.get("incompleteResources");
        assertTrue(incomplete.stream().anyMatch(p -> String.valueOf(p).endsWith(incompleteFileName)),
                "the specific incomplete file must still be surfaced: " + result);

        List<?> autoApply = (List<?>) result.get("autoApply");
        assertTrue(autoApply.isEmpty(), "no edit may be auto-applied on an incomplete scan: " + result);

        Map<?, ?> stats = (Map<?, ?>) result.get("stats");
        assertEquals(0, ((Number) stats.get("autoApply")).intValue(), "stats.autoApply must be 0: " + result);

        // Every planned edit drops to PREVIEW disposition; no AUTO_APPLY survives behind an incomplete scan.
        for (Object edit : (List<?>) result.get("edits")) {
            assertEquals("PREVIEW", ((Map<?, ?>) edit).get("disposition"),
                    "an incomplete scan must downgrade every edit to PREVIEW: " + edit);
        }
        // The full edit count equals the preview count: nothing is auto-applied, everything previews.
        List<?> preview = (List<?>) result.get("preview");
        assertEquals(((List<?>) result.get("edits")).size(), preview.size(),
                "every edit must be in the preview partition on an incomplete scan: " + result);
    }

    // ── fixture helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private static void seedMovedType(Path root) throws IOException {
        Path javaFile = root.resolve("src/main/java/com/acme/MyServiceImpl.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package com.acme;\npublic class MyServiceImpl {}\n", StandardCharsets.UTF_8);
    }

    private static Path writeResource(Path root, String name, String content) throws IOException {
        Path file = root.resolve("src/main/resources").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static boolean makeUnreadable(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.<PosixFilePermission>of());
            // If we can still read it (e.g. running as root), the unreadable simulation does not hold.
            try {
                Files.readString(file);
                return false;
            } catch (IOException expected) {
                return true;
            }
        } catch (UnsupportedOperationException | IOException notPosix) {
            return false;
        }
    }

    private static JavaProjectModel model(Path root) {
        Path sourceRoot = root.resolve("src/main/java");
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.of(sourceRoot.resolve("com/acme/MyServiceImpl.java")),
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
