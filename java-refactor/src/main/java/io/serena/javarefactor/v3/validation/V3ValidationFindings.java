package io.serena.javarefactor.v3.validation;

import io.serena.javarefactor.compiler.DeclaredTypeNames;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipation;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator;
import io.serena.javarefactor.v3.frameworks.SymbolChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared non-compiler V3 validation producer for {@code validateEdit} and direct preview validation.
 *
 * <p>Python treats {@code ready:false} plus any non-empty finding bucket as authoritative. Keep all non-javac
 * validation channels here so the sidecar producer and Python consumer stay in one contract: resources, framework
 * participation, and build graph/classpath safety all contribute to the readiness bit.</p>
 */
public final class V3ValidationFindings {

    private V3ValidationFindings() {}

    public record Result(List<String> resourceFindings, List<String> frameworkFindings, List<String> buildFindings) {
        public Result {
            resourceFindings = List.copyOf(resourceFindings);
            frameworkFindings = List.copyOf(frameworkFindings);
            buildFindings = List.copyOf(buildFindings);
        }

        public boolean isReady() {
            return resourceFindings.isEmpty() && frameworkFindings.isEmpty();
        }

        public List<String> blockingFindings() {
            List<String> findings = new ArrayList<>(resourceFindings.size() + frameworkFindings.size() + buildFindings.size());
            findings.addAll(resourceFindings);
            findings.addAll(frameworkFindings);
            findings.addAll(buildFindings);
            return findings;
        }
    }

    public static Result collect(
            JavaProjectModel model,
            Map<String, Object> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles) {
        List<String> resourceFindings = ResourceReferenceValidation.findings(model, changedFiles, deletedFiles, renamedFiles);
        List<String> frameworkFindings = frameworkFindings(model, changedFiles, deletedFiles, renamedFiles);
        List<String> buildFindings = buildFindings(changedFiles.keySet(), deletedFiles, renamedFiles);
        return new Result(resourceFindings, frameworkFindings, buildFindings);
    }

    private static List<String> frameworkFindings(
            JavaProjectModel model,
            Map<String, Object> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles) {
        Set<String> removedFqns = removedFqns(model.projectRoot(), changedFiles, deletedFiles, renamedFiles);
        if (removedFqns.isEmpty()) {
            return List.of();
        }
        FrameworkParticipationCoordinator coordinator = new FrameworkParticipationCoordinator();
        List<String> findings = new ArrayList<>();
        for (String fqn : removedFqns) {
            try {
                FrameworkParticipationCoordinator.Result result = coordinator.participate(model, SymbolChange.safeDelete(fqn));
                for (FrameworkParticipation.Block block : result.blocks()) {
                    findings.add("Framework blocks change for " + block.symbol() + ": " + block.reason());
                }
                // Participation warnings and boundary-change notes are planner risk evidence, not post-state
                // validation failures. Validation findings are blocking because they drive ready=false, so only
                // framework vetoes/errors belong here; dangling exact XML/service-loader/JPA references are covered
                // by ResourceReferenceValidation.
            } catch (IOException | RuntimeException error) {
                findings.add("Framework validation failed for " + fqn + ": " + error.getMessage());
            }
        }
        return findings;
    }

    private static Set<String> removedFqns(
            Path projectRoot,
            Map<String, Object> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles) {
        Map<String, String> changedByRel = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changedFiles.entrySet()) {
            changedByRel.put(normalizeRel(entry.getKey()), String.valueOf(entry.getValue()));
        }

        Set<String> renamedOldRel = new LinkedHashSet<>();
        for (Object pairObj : renamedFiles) {
            if (pairObj instanceof Map<?, ?> pair && pair.get("oldPath") != null) {
                renamedOldRel.add(normalizeRel(String.valueOf(pair.get("oldPath"))));
            }
        }
        Set<String> candidateBeforeRel = new LinkedHashSet<>();
        for (Object deleted : deletedFiles) {
            String rel = normalizeRel(String.valueOf(deleted));
            if (!renamedOldRel.contains(rel)) {
                candidateBeforeRel.add(rel);
            }
        }
        // Renames/moves and changed Java files are not safe-delete candidates. Exact old-FQN
        // resource dangling is checked by ResourceReferenceValidation; framework ownership vetoes
        // here are reserved for true source deletion.
        List<String> beforeSources = new ArrayList<>();
        for (String rel : candidateBeforeRel) {
            if (rel.endsWith(".java")) {
                beforeSources.add(readDisk(projectRoot.resolve(rel)));
            }
        }
        Set<String> declaredBefore = new LinkedHashSet<>(DeclaredTypeNames.from(beforeSources));
        if (declaredBefore.isEmpty()) {
            return Set.of();
        }

        List<String> afterSources = new ArrayList<>();
        for (Map.Entry<String, String> entry : changedByRel.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                afterSources.add(entry.getValue());
            }
        }
        for (Object renamed : renamedFiles) {
            if (!(renamed instanceof Map<?, ?> pair)) {
                continue;
            }
            Object oldPath = pair.get("oldPath");
            Object newPath = pair.get("newPath");
            if (oldPath == null || newPath == null) {
                continue;
            }
            String newRel = normalizeRel(String.valueOf(newPath));
            if (newRel.endsWith(".java") && !changedByRel.containsKey(newRel)) {
                afterSources.add(readDisk(projectRoot.resolve(normalizeRel(String.valueOf(oldPath)))));
            }
        }

        Set<String> declaredAfter = new LinkedHashSet<>(DeclaredTypeNames.from(afterSources));
        declaredBefore.removeAll(declaredAfter);
        return declaredBefore;
    }

    private static List<String> buildFindings(
            Collection<String> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles) {
        Set<String> touched = new LinkedHashSet<>();
        for (String rel : changedFiles) {
            touched.add(normalizeRel(rel));
        }
        for (Object deleted : deletedFiles) {
            touched.add(normalizeRel(String.valueOf(deleted)));
        }
        for (Object renamed : renamedFiles) {
            if (renamed instanceof Map<?, ?> pair) {
                if (pair.get("oldPath") != null) {
                    touched.add(normalizeRel(String.valueOf(pair.get("oldPath"))));
                }
                if (pair.get("newPath") != null) {
                    touched.add(normalizeRel(String.valueOf(pair.get("newPath"))));
                }
            }
        }

        List<String> findings = new ArrayList<>();
        for (String rel : touched) {
            if (isBuildDescriptor(rel)) {
                findings.add("Build validation required for changed build descriptor " + rel
                        + "; validateEdit cannot prove build graph/classpath safety from javac overlay alone.");
            }
        }
        return findings;
    }

    private static boolean isBuildDescriptor(String rel) {
        String normalized = normalizeRel(rel);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts")
                || name.equals("gradle.properties");
    }

    private static String readDisk(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String normalizeRel(String path) {
        return path.replace('\\', '/');
    }
}
