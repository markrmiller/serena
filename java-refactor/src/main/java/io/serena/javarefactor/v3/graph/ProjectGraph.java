package io.serena.javarefactor.v3.graph;

import java.util.List;

/**
 * The project-identity component of a {@link TransformationGraph} (refactor-feature-plan-V3.md §1.2): the project root,
 * the revision the graph was built at, the build system, and the module ids.
 *
 * <p>This is the whole-repo anchor the other six components attach to. The {@code revision} is the same content-addressed
 * project key {@link io.serena.javarefactor.compiler.ReachabilityGraphCache#projectKey} produces, so a {@code
 * ProjectGraph}'s revision changes whenever any source file changes — exactly the signal {@link GraphInvalidation} keys
 * its cache on.
 *
 * @param projectRoot  absolute, normalized project root path
 * @param revision     the content-addressed project revision key the graph was built at
 * @param buildSystem  the build system ({@code maven}/{@code gradle}/{@code plain})
 * @param moduleIds    the ids of the project's modules
 */
public record ProjectGraph(String projectRoot, String revision, String buildSystem, List<String> moduleIds) {
}
