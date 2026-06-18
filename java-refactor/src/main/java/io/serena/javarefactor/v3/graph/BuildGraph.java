package io.serena.javarefactor.v3.graph;

import java.util.List;

/**
 * The project's build layout: its build system and the source roots of each module (refactor-feature-plan-V3.md §1.2).
 *
 * <p>A {@code BuildGraph} is the structural backbone the rest of the {@link TransformationGraph} hangs off: package and
 * type placement, resource scanning, and test classification all key off the {@link SourceRoot}s recorded here. It is
 * built directly from the validated {@link io.serena.javarefactor.project.JavaProjectModel} (its discovery kind and its
 * source sets) — never inferred from a directory walk.
 *
 * @param buildSystem the build system that defined the layout ({@code maven}/{@code gradle}/{@code plain})
 * @param modules     the modules of the project, each with its classified source roots
 */
public record BuildGraph(String buildSystem, List<ModuleNode> modules) {

    /** Whether a source root holds {@code main} or {@code test} inputs. */
    public enum RootKind { MAIN, TEST }

    /** Whether a source root holds Java sources or non-Java resources. */
    public enum RootContent { JAVA, RESOURCES }

    /**
     * A single source root within a module.
     *
     * @param relativePath project-relative path of the root (e.g. {@code src/main/java})
     * @param kind         whether it is a main or test root
     * @param content      whether it holds Java sources or resources
     * @param moduleId     the id of the owning module
     */
    public record SourceRoot(String relativePath, RootKind kind, RootContent content, String moduleId) {
    }

    /**
     * A build module: a unit with its own build descriptor and source roots.
     *
     * @param moduleId    the module identifier (its source-set name for single-module projects)
     * @param buildSystem the build system of the module
     * @param sourceRoots the module's classified source roots
     */
    public record ModuleNode(String moduleId, String buildSystem, List<SourceRoot> sourceRoots) {
    }

    private List<SourceRoot> roots(RootKind kind, RootContent content) {
        return modules.stream()
                .flatMap(module -> module.sourceRoots().stream())
                .filter(root -> root.kind() == kind && root.content() == content)
                .toList();
    }

    /** All main Java source roots across every module. */
    public List<SourceRoot> mainJavaRoots() {
        return roots(RootKind.MAIN, RootContent.JAVA);
    }

    /** All test Java source roots across every module. */
    public List<SourceRoot> testJavaRoots() {
        return roots(RootKind.TEST, RootContent.JAVA);
    }

    /** All resource roots (main + test) across every module. */
    public List<SourceRoot> resourceRoots() {
        return modules.stream()
                .flatMap(module -> module.sourceRoots().stream())
                .filter(root -> root.content() == RootContent.RESOURCES)
                .toList();
    }
}
